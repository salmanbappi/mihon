package eu.kanade.tachiyomi.data.ai

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class AiManager(
    private val context: Context,
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun resetCircuitBreaker() {
        aiPreferences.isCircuitBreakerTripped().set(false)
        aiPreferences.isRequestPending().set(false)
    }

    suspend fun getStatisticsAnalysis(statsSummary: String): String? {
        val result = StringBuilder()
        getStatisticsAnalysisStream(statsSummary).collect { result.append(it) }
        return result.toString().ifBlank { null }
    }

    fun getStatisticsAnalysisStream(statsSummary: String): Flow<String> = flow {
        if (!aiPreferences.enableAi().get() || !aiPreferences.enableAiStatistics().get()) return@flow
        
        if (isCircuitBreakerTripped()) {
            emit("Stability Alert: AI temporarily disabled due to detected app instability. [RESET_REQUIRED]")
            return@flow
        }

        val engine = aiPreferences.aiEngine().get()
        val apiKey = if (engine == "gemini") {
            aiPreferences.geminiApiKey().get()
        } else {
            aiPreferences.groqApiKey().get()
        }.ifBlank { 
            // Fallback to internal/user provided key from GH secret if available in BuildConfig
            // For now, require user to set it
            emit("Please set an AI API Key in Settings > Advanced")
            return@flow 
        }

        val prompt = """
            Generate a 'System Behavioral Profile' based on the following library data.
            
            DATA INPUT:
            $statsSummary
            
            REPORT STRUCTURE (STRICTLY NO TABLES):
            - **User Classification**: Technical archetype (e.g., 'High-Volume Collector').
            - **Reading Patterns**: Temporal analysis of reading habits.
            - **Source Integrity**: Distribution across extensions.
            - **Strategic Recommendations**: 3-5 manga titles based on data patterns.
            
            Constraint: Use bullet points. Do NOT use Markdown tables.
        """.trimIndent()

        aiPreferences.isRequestPending().set(true)
        try {
            if (engine == "gemini") {
                callGeminiStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
            } else {
                callGroqStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
            }
        } finally {
            aiPreferences.isRequestPending().set(false)
            recordRequestSuccess()
        }
    }

    suspend fun getDiagnosticAnalysis(reportSummary: String): String? {
        val result = StringBuilder()
        getDiagnosticAnalysisStream(reportSummary).collect { result.append(it) }
        return result.toString().ifBlank { null }
    }

    fun getDiagnosticAnalysisStream(reportSummary: String): Flow<String> = flow {
        if (!aiPreferences.enableAi().get()) return@flow
        
        if (isCircuitBreakerTripped()) return@flow

        val engine = aiPreferences.aiEngine().get()
        val apiKey = if (engine == "gemini") aiPreferences.geminiApiKey().get() else aiPreferences.groqApiKey().get()
        if (apiKey.isBlank()) return@flow

        val logs = getSanitizedLogs()
        val prompt = """
            Perform a 'Deep System Diagnosis' on the following infrastructure report and logs.
            
            REPORT SUMMARY:
            $reportSummary
            
            CRITICAL LOGS:
            $logs
            
            DIAGNOSTIC REQUIREMENTS:
            1. Identify failing nodes or degraded extensions.
            2. Analyze log patterns for recurring network or rendering errors.
            3. Provide 3 actionable steps to improve system stability.
            4. If a 'SIGSEGV' or 'FATAL' is found, explain the likely cause.
            
            Constraint: STRICTLY NO TABLES. Use bullet points.
        """.trimIndent()

        aiPreferences.isRequestPending().set(true)
        try {
            if (engine == "gemini") {
                callGeminiStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior systems reliability engineer.").collect { emit(it) }
            } else {
                callGroqStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior systems reliability engineer.").collect { emit(it) }
            }
        } finally {
            aiPreferences.isRequestPending().set(false)
            recordRequestSuccess()
        }
    }

    private fun isCircuitBreakerTripped(): Boolean {
        if (aiPreferences.isRequestPending().get()) {
            aiPreferences.isCircuitBreakerTripped().set(true)
            return true
        }
        return aiPreferences.isCircuitBreakerTripped().get()
    }

    private fun recordRequestSuccess() {
        val count = aiPreferences.hourlyAiRequestCount().get()
        aiPreferences.hourlyAiRequestCount().set(count + 1)
        aiPreferences.lastAiRequestTime().set(System.currentTimeMillis())
    }

    private suspend fun getSanitizedLogs(): String = withIOContext {
        try {
            val logLines = mutableListOf<String>()
            try {
                val process = Runtime.getRuntime().exec("logcat -d -b main -t 300 *:W")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    logLines.add(line)
                }
                process.waitFor(1, TimeUnit.SECONDS)
                process.destroy()
            } catch (e: Exception) {}

            if (logLines.isEmpty()) {
                val storageManager = Injekt.get<StorageManager>()
                val logDir = storageManager.getLogsDirectory()
                val latestLog = logDir?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".log") == true }
                    ?.maxByOrNull { it.lastModified() }
                
                if (latestLog != null) {
                    latestLog.openInputStream().bufferedReader().useLines { lines ->
                        logLines.addAll(lines.toList().takeLast(300))
                    }
                }
            }

            val packagePattern = "(eu\\.kanade|app\\.mihon|AndroidRuntime|libc|DEBUG|System\\.err|FileUtils|ActivityThread)".toRegex()
            val sanitized = logLines.filter { it.contains(packagePattern) }.takeLast(100).joinToString("\n")
            sanitized.ifBlank { "No relevant logs found." }
        } catch (e: Exception) {
            "Log retrieval failed: ${e.message}"
        }
    }

    private suspend fun callGeminiStream(
        messages: List<ChatMessage>, 
        apiKey: String, 
        systemInstruction: String? = null
    ): Flow<String> = flow {
        val geminiContents = messages.map { msg ->
            GeminiContent(parts = listOf(GeminiPart(text = msg.content)), role = if (msg.role == "user") "user" else "model")
        }
        val requestBody = GeminiRequest(
            contents = geminiContents, 
            systemInstruction = systemInstruction?.let { GeminiContent(parts = listOf(GeminiPart(text = it))) }
        )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse&key=$apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GeminiRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()

        try {
            networkHelper.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@flow
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        try {
                            val chunk = json.decodeFromString(GeminiResponse.serializer(), data)
                            val text = chunk.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                            if (text != null) emit(text)
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun callGroqStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null
    ): Flow<String> = flow {
        val groqMessages = mutableListOf<GroqMessage>()
        if (systemInstruction != null) groqMessages.add(GroqMessage(role = "system", content = systemInstruction))
        messages.forEach { msg -> groqMessages.add(GroqMessage(role = if (msg.role == "user") "user" else "assistant", content = msg.content)) }
        
        val requestBody = GroqRequest(messages = groqMessages, model = "llama-3.3-70b-versatile", stream = true)
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()

        try {
            networkHelper.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@flow
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = json.decodeFromString(GroqStreamResponse.serializer(), data)
                            val text = chunk.choices.firstOrNull()?.delta?.content
                            if (text != null) emit(text)
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}
    }

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>, 
        @kotlinx.serialization.SerialName("system_instruction") val systemInstruction: GeminiContent? = null
    )

    @Serializable
    private data class GeminiContent(val parts: List<GeminiPart>, val role: String? = null)

    @Serializable
    private data class GeminiPart(val text: String)

    @Serializable
    private data class GeminiResponse(val candidates: List<GeminiCandidate>)

    @Serializable
    private data class GeminiCandidate(val content: GeminiContent)

    @Serializable
    private data class GroqRequest(
        val messages: List<GroqMessage>, 
        val model: String,
        val stream: Boolean = false
    )

    @Serializable
    private data class GroqMessage(val role: String, val content: String)

    @Serializable
    private data class GroqStreamResponse(val choices: List<GroqStreamChoice>)

    @Serializable
    private data class GroqStreamChoice(val delta: GroqStreamDelta)

    @Serializable
    private data class GroqStreamDelta(val content: String? = null)
}

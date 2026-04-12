package eu.kanade.tachiyomi.data.ai

import android.content.Context
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import com.hippo.unifile.UniFile
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AiManager(
    private val context: Context,
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getLibraryManga: tachiyomi.domain.manga.interactor.GetLibraryManga = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun resetCircuitBreaker() {
        aiPreferences.isCircuitBreakerTripped().set(false)
        aiPreferences.isRequestPending().set(false)
    }

    fun chatWithAssistantStream(query: String, history: List<ChatMessage>): Flow<String> = flow {
        if (!aiPreferences.enableAi().get() || !aiPreferences.enableAiAssistant().get()) return@flow
        
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
            emit("Please set an API Key in Settings > Advanced Analytics")
            return@flow 
        }

        val customPrompt = aiPreferences.aiSystemPrompt().get()
        val defaultSystemInstruction = """
            You are the 'Mihon System Assistant', a senior systems engineer.
            You have access to native diagnostic tools for logs, system maps, and the user's manga library.
            
            OPERATIONAL PROTOCOLS:
            1. FORMATTING: STRICTLY NO TABLES. Use bullet points or lists for structured data. NEVER output Markdown tables.
            2. GROUNDING: Provide actionable manga-centric system insights.
        """.trimIndent()
        
        val systemInstruction = if (customPrompt.isNotBlank()) customPrompt else defaultSystemInstruction

        val messages = history.toMutableList()
        messages.add(ChatMessage(role = "user", content = query))

        aiPreferences.isRequestPending().set(true)
        
        try {
            if (engine == "gemini") {
                callGeminiStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
            } else {
                callGroqStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
            }
        } finally {
            aiPreferences.isRequestPending().set(false)
            recordRequestSuccess()
        }
    }

    private suspend fun getLibrarySummary(): String {
        return try {
            val library = getLibraryManga.await()
            if (library.isEmpty()) return "Library is empty."
            library.take(50).joinToString("\n") { manga ->
                "- ${manga.manga.title} [Status: ${manga.manga.status}, Read: ${manga.readCount}]"
            }
        } catch (e: Exception) {
            "Failed to retrieve library summary."
        }
    }

    fun getStatisticsAnalysisStream(statsSummary: String): Flow<String> = flow {
        if (!aiPreferences.enableAi().get() || !aiPreferences.enableAiStatistics().get()) return@flow
        
        if (isCircuitBreakerTripped()) return@flow

        val engine = aiPreferences.aiEngine().get()
        val apiKey = if (engine == "gemini") {
            aiPreferences.geminiApiKey().get()
        } else {
            aiPreferences.groqApiKey().get()
        }.ifBlank { return@flow }

        val prompt = """
            Generate a 'System Behavioral Profile' based on the following data.
            
            DATA INPUT:
            $statsSummary
            
            REPORT STRUCTURE (STRICTLY NO TABLES):
            - **User Classification**: Technical archetype (e.g., 'High-Volume Archivist').
            - **Temporal Analysis**: Reading habit patterns.
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

    private fun isCircuitBreakerTripped(): Boolean {
        val lastRequestTime = aiPreferences.lastAiRequestTime().get()
        val isPending = aiPreferences.isRequestPending().get()
        
        if (isPending && System.currentTimeMillis() - lastRequestTime > 10000) {
            aiPreferences.isCircuitBreakerTripped().set(true)
            aiPreferences.isRequestPending().set(false)
            return true
        }
        return aiPreferences.isCircuitBreakerTripped().get()
    }

    private fun recordRequestSuccess() {
        val count = aiPreferences.hourlyAiRequestCount().get()
        aiPreferences.hourlyAiRequestCount().set(count + 1)
        aiPreferences.lastAiRequestTime().set(System.currentTimeMillis())
        aiPreferences.isRequestPending().set(false)
    }

    private suspend fun getSanitizedLogs(): String = withIOContext {
        try {
            val logLines = mutableListOf<String>()
            try {
                val process = Runtime.getRuntime().exec("logcat -d -b main -t 500 *:W")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    logLines.add(line)
                }
                process.waitFor(2, TimeUnit.SECONDS)
                process.destroy()
            } catch (e: Exception) {}

            if (logLines.size < 10) {
                val internalLogDir = File(context.cacheDir, "logs")
                if (internalLogDir.exists()) {
                    val latestLog = internalLogDir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".log") }
                        ?.maxByOrNull { it.lastModified() }
                    
                    if (latestLog != null) {
                        try {
                            latestLog.bufferedReader().useLines { lines ->
                                logLines.addAll(lines.toList().takeLast(500))
                            }
                        } catch (e: Exception) {}
                    }
                }
            }

            val packagePattern = "(eu\\.kanade|app\\.mihon|ffmpeg|AndroidRuntime|libc|DEBUG|System\\.err|FileUtils|ActivityThread)".toRegex()
            val sanitized = logLines.filter { it.contains(packagePattern) }.takeLast(100).joinToString("\n")
            sanitized.ifBlank { "No relevant application logs found." }
        } catch (e: Exception) {
            "Diagnostic retrieval failed: ${e.message}"
        }
    }

    suspend fun getErrorCount(): Int = withIOContext {
        try {
            val logLines = mutableListOf<String>()
            val process = Runtime.getRuntime().exec("logcat -d -b main -t 200 *:E")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (true) {
                val line = reader.readLine() ?: break
                logLines.add(line)
            }
            val criticalPatterns = listOf("FATAL EXCEPTION", "OutOfMemoryError", "Native crash", "SIGSEGV", "Check failed")
            logLines.count { line -> criticalPatterns.any { line.contains(it, ignoreCase = true) } }
        } catch (e: Exception) { 0 }
    }

    private suspend fun callGeminiStream(
        messages: List<ChatMessage>, 
        apiKey: String, 
        systemInstruction: String? = null,
        withTools: Boolean = false
    ): Flow<String> = flow {
        val finalMessages = if (withTools) {
            val lastQuery = messages.last().content.lowercase()
            val toolContext = StringBuilder()
            if (lastQuery.contains("""log|error|fail|load|setting|where|how|device|black|broke|froze|slow|crash|die|dead|bug|stuck|lag|hang|freeze""".toRegex())) {
                if (aiPreferences.aiAssistantLogs().get()) {
                    toolContext.append("\n[DIAGNOSTICS_DATA]:\n${getSanitizedLogs()}\n")
                }
            }
            if (lastQuery.contains("""library|manga|collection|have|my|list|recommend""".toRegex())) {
                if (aiPreferences.aiAssistantLibrary().get()) {
                    toolContext.append("\n[USER_LIBRARY_DATA]:\n${getLibrarySummary()}\n")
                }
            }
            messages.dropLast(1) + ChatMessage("user", messages.last().content + "\n\n" + toolContext.toString())
        } else messages

        val geminiContents = finalMessages.map { msg ->
            GeminiContent(parts = listOf(GeminiPart(text = msg.content)), role = if (msg.role == "user") "user" else "model")
        }
        val requestBody = GeminiRequest(
            contents = geminiContents, 
            systemInstruction = systemInstruction?.let { GeminiContent(parts = listOf(GeminiPart(text = it))) },
            safetySettings = listOf(
                GeminiSafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE")
            )
        )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:streamGenerateContent?alt=sse&key=$apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GeminiRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()

        try {
            networkHelper.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit("Gemini Error ${response.code}")
                    return@flow
                }
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = json.decodeFromString(GeminiResponse.serializer(), data)
                            val text = chunk.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                            if (text != null) emit(text)
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) { emit("Gemini Exception: ${e.message}") }
    }

    private suspend fun callGroqStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false
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
                if (!response.isSuccessful) {
                    emit("Groq Error ${response.code}")
                    return@flow
                }
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
        } catch (e: Exception) { emit("Groq Exception: ${e.message}") }
    }

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>, 
        @kotlinx.serialization.SerialName("system_instruction") val systemInstruction: GeminiContent? = null,
        val safetySettings: List<GeminiSafetySetting>? = null
    )

    @Serializable
    private data class GeminiSafetySetting(val category: String, val threshold: String)

    @Serializable
    private data class GeminiContent(val parts: List<GeminiPart>, val role: String? = null)

    @Serializable
    private data class GeminiPart(val text: String)

    @Serializable
    private data class GeminiResponse(val candidates: List<GeminiCandidate>)

    @Serializable
    private data class GeminiCandidate(val content: GeminiContent)

    @Serializable
    private data class GroqRequest(val messages: List<GroqMessage>, val model: String, val stream: Boolean = false)

    @Serializable
    private data class GroqMessage(val role: String, val content: String)

    @Serializable
    private data class GroqStreamResponse(val choices: List<GroqStreamChoice>)

    @Serializable
    private data class GroqStreamChoice(val delta: GroqStreamDelta)

    @Serializable
    private data class GroqStreamDelta(val content: String? = null)
}

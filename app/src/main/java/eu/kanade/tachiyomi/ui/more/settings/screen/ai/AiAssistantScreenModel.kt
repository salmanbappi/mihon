package eu.kanade.tachiyomi.ui.more.settings.screen.ai

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.data.ai.AiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiAssistantScreenModel(
    private val aiManager: AiManager = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
) : StateScreenModel<AiAssistantState>(AiAssistantState()) {

    private val _sessions = MutableStateFlow<List<AiSession>>(emptyList())
    val sessions: StateFlow<List<AiSession>> = _sessions.asStateFlow()

    init {
        _sessions.value = listOf(AiSession(1, "Diagnostic Uplink"))
        mutableState.update { it.copy(activeSessionId = 1) }
    }

    fun sendMessage(query: String) {
        if (query.isBlank() || state.value.isLoading) return

        val userMsg = AiMessage(role = "user", content = query)
        mutableState.update { 
            it.copy(
                messages = it.messages + userMsg,
                isLoading = true,
                streamingMessage = ""
            )
        }

        screenModelScope.launchIO {
            try {
                val history = state.value.messages.dropLast(1).map { AiManager.ChatMessage(it.role, it.content) }
                aiManager.chatWithAssistantStream(query, history).collect { chunk ->
                    mutableState.update { 
                        it.copy(streamingMessage = (it.streamingMessage ?: "") + chunk)
                    }
                }
                
                val finalMsg = AiMessage(role = "assistant", content = state.value.streamingMessage ?: "")
                mutableState.update { 
                    it.copy(
                        messages = it.messages + finalMsg,
                        isLoading = false,
                        streamingMessage = null
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, streamingMessage = null) }
            }
        }
    }

    fun createNewSession() {
        mutableState.update { AiAssistantState(activeSessionId = System.currentTimeMillis()) }
    }

    fun switchSession(id: Long) {
        mutableState.update { it.copy(activeSessionId = id, messages = emptyList()) }
    }

    fun deleteSession(id: Long) {
        // No-op for now
    }
}

data class AiAssistantState(
    val messages: List<AiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val streamingMessage: String? = null,
    val activeSessionId: Long = -1,
)

data class AiMessage(
    val role: String,
    val content: String,
)

data class AiSession(
    val id: Long,
    val title: String,
)

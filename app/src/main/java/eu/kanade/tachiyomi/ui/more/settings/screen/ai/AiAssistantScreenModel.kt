package eu.kanade.tachiyomi.ui.more.settings.screen.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.data.ai.AiManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class AiAssistantScreenModel(
    private val aiPreferences: AiPreferences,
    private val aiManager: AiManager,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private val _sessions = MutableStateFlow<ImmutableList<ChatSession>>(persistentListOf(ChatSession(1, "Diagnostic Uplink")))
    val sessions: StateFlow<ImmutableList<ChatSession>> = _sessions.asStateFlow()

    init {
        state.update { it.copy(activeSessionId = 1) }
    }

    fun createNewSession() {
        val newId = System.currentTimeMillis()
        val newSession = ChatSession(newId, "New Analytic Session")
        _sessions.update { (it + newSession).toImmutableList() }
        switchSession(newId)
    }

    fun switchSession(sessionId: Long) {
        state.update { it.copy(activeSessionId = sessionId, messages = persistentListOf()) }
    }

    fun deleteSession(sessionId: Long) {
        _sessions.update { list -> list.filter { it.id != sessionId }.toImmutableList() }
        if (state.value.activeSessionId == sessionId) {
            val first = _sessions.value.firstOrNull()
            if (first != null) switchSession(first.id) else createNewSession()
        }
    }

    fun resetSystem() {
        aiManager.resetCircuitBreaker()
        state.update { it.copy(messages = persistentListOf(), streamingMessage = null, isLoading = false) }
    }

    fun sendMessage(query: String) {
        if (query.isBlank() || state.value.isLoading) return

        val userMsg = ChatMessage(role = "user", content = query)
        state.update { 
            it.copy(
                messages = (it.messages + userMsg).toImmutableList(),
                isLoading = true,
                streamingMessage = ""
            )
        }

        viewModelScope.launchIO {
            try {
                val history = state.value.messages.dropLast(1).map { AiManager.ChatMessage(it.role, it.content) }
                val fullResponse = StringBuilder()
                
                aiManager.chatWithAssistantStream(query, history).collect { chunk ->
                    fullResponse.append(chunk)
                    state.update { it.copy(streamingMessage = fullResponse.toString()) }
                }

                val finalMsg = ChatMessage(role = "model", content = fullResponse.toString())
                state.update { 
                    it.copy(
                        messages = (it.messages + finalMsg).toImmutableList(),
                        isLoading = false,
                        streamingMessage = null
                    )
                }
                
                if (state.value.messages.count { it.role == "user" } == 1) {
                    val title = if (query.length > 30) query.take(27) + "..." else query
                    _sessions.update { list ->
                        list.map { if (it.id == state.value.activeSessionId) it.copy(title = title) else it }.toImmutableList()
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                val errorMsg = ChatMessage(role = "model", content = "System error during uplink: ${e.message}")
                state.update { it.copy(messages = (it.messages + errorMsg).toImmutableList(), isLoading = false, streamingMessage = null) }
            }
        }
    }

    data class State(
        val activeSessionId: Long? = null,
        val messages: ImmutableList<ChatMessage> = persistentListOf(),
        val isLoading: Boolean = false,
        val selectedSessionIds: Set<Long> = emptySet(),
        val streamingMessage: String? = null,
    )
}

data class ChatMessage(
    val role: String,
    val content: String,
)

data class ChatSession(
    val id: Long,
    val title: String,
)

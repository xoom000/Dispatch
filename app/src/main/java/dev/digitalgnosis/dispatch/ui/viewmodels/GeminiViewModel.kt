package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.GeminiRepository
import dev.digitalgnosis.dispatch.data.GeminiSessionInfo
import dev.digitalgnosis.dispatch.data.GeminiUpdate
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.network.AudioStreamClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class GeminiViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val messageRepository: MessageRepository,
    private val audioStreamClient: AudioStreamClient
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<GeminiSessionInfo>>(emptyList())
    val sessions: StateFlow<List<GeminiSessionInfo>> = _sessions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _activeSession = MutableStateFlow<GeminiSessionDetail?>(null)
    
    private val _liveBuffer = MutableStateFlow("")
    private val _liveThoughts = MutableStateFlow("")

    /**
     * STREAMING UI CORE:
     * Merges the static session history with real-time character chunks.
     */
    val activeSession: StateFlow<GeminiSessionDetail?> = combine(
        _activeSession,
        _liveBuffer,
        _liveThoughts
    ) { session, buffer, thoughts ->
        if (session == null) return@combine null
        
        if (buffer.isNotEmpty() || thoughts.isNotEmpty()) {
            val synthetic = GeminiMessage(
                id = "live-chunk",
                sender = "Gemini CLI",
                body = buffer,
                isGemini = true,
                timestamp = "Just now",
                thoughts = if (thoughts.isNotEmpty()) listOf(thoughts) else emptyList()
            )
            session.copy(messages = session.messages + synthetic)
        } else {
            session
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val agentThoughts: StateFlow<String> = _liveThoughts.asStateFlow()

    init {
        refreshSessions()

        // Observe global chunks from Repository
        viewModelScope.launch {
            messageRepository.liveChunks.collect { chunk ->
                if (chunk.threadId == _activeSession.value?.id) {
                    if (chunk.type == "agent_message_chunk") {
                        _liveBuffer.value += chunk.text
                        _liveThoughts.value = ""
                    } else if (chunk.type == "agent_thought_chunk") {
                        _liveThoughts.value = chunk.text
                    }
                }
            }
        }

        // Reset buffer when thread refreshes
        viewModelScope.launch {
            messageRepository.threadRefreshEvents.collect { threadId ->
                if (threadId == _activeSession.value?.id) {
                    _liveBuffer.value = ""
                    _liveThoughts.value = ""
                }
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            _sessions.value = geminiRepository.fetchSessions()
            _isLoading.value = false
        }
    }

    fun loadSession(id: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _liveBuffer.value = ""
            _liveThoughts.value = ""
            
            val json = geminiRepository.fetchSessionDetail(id)
            if (json != null) {
                val messages = mutableListOf<GeminiMessage>()
                val arr = json.optJSONArray("messages") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val msg = arr.getJSONObject(i)
                    val contentArr = msg.optJSONArray("content")
                    val body = if (contentArr != null && contentArr.length() > 0) {
                        contentArr.getJSONObject(0).optString("text", "")
                    } else msg.optString("content", "")

                    val thoughts = mutableListOf<String>()
                    val thoughtArr = msg.optJSONArray("thoughts")
                    if (thoughtArr != null) {
                        for (j in 0 until thoughtArr.length()) {
                            thoughts.add(thoughtArr.getJSONObject(j).optString("subject", ""))
                        }
                    }

                    messages.add(GeminiMessage(
                        id = msg.optString("id", i.toString()),
                        sender = msg.optString("role", "assistant"),
                        body = body,
                        isGemini = msg.optString("role") != "user",
                        timestamp = msg.optString("timestamp", ""),
                        thoughts = thoughts
                    ))
                }
                _activeSession.value = GeminiSessionDetail(id, messages)
            }
            _isLoading.value = false
        }
    }

    fun clearActiveSession() {
        _activeSession.value = null
        _liveBuffer.value = ""
        _liveThoughts.value = ""
    }

    fun sendMessage(text: String) {
        val session = _activeSession.value ?: return
        
        // Optimistic user message
        val updated = session.messages + GeminiMessage(
            id = "user-${System.currentTimeMillis()}",
            sender = "User",
            body = text,
            isGemini = false,
            timestamp = "Just now"
        )
        _activeSession.value = session.copy(messages = updated)

        viewModelScope.launch {
            _liveThoughts.value = "Connecting..."
            var fullText = ""
            
            geminiRepository.sendNativePrompt(session.id, text).collect { update ->
                // Handled globally by SSE listener, but we track fullText for audio
                if (update is GeminiUpdate.MessageChunk) {
                    fullText += update.text
                }
            }

            if (fullText.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    audioStreamClient.replayMessage("Gemini CLI", fullText, null)
                }
                loadSession(session.id) // Reload to get official history
            }
        }
    }
}

data class GeminiSessionDetail(
    val id: String,
    val messages: List<GeminiMessage>
)

data class GeminiMessage(
    val id: String,
    val sender: String,
    val body: String,
    val isGemini: Boolean,
    val timestamp: String,
    val thoughts: List<String> = emptyList()
)

package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.config.AnthropicAuthManager
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.data.ChatBubbleRepository
import dev.digitalgnosis.dispatch.data.SessionRepository
import dev.digitalgnosis.dispatch.data.StreamEvent
import dev.digitalgnosis.dispatch.network.AudioStreamClient
import dev.digitalgnosis.dispatch.network.SessionsApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for MessagesScreen — real-time chat via Room + SSE.
 *
 * Architecture (mirrors Google Messages):
 *   SSE event → ChatBubbleRepository → Room DAO → InvalidationTracker
 *   Room Flow → ChatBubbleRepository.observeBubbles() → this ViewModel → Compose
 *
 * NO POLLING. Updates arrive via SSE push → Room write → Flow emission.
 * Initial load and scroll-up use one-shot HTTP fetches that write to Room.
 * The Flow handles all UI updates automatically.
 *
 * Streaming: When Anthropic Sessions API is configured, sendStreaming() creates
 * a bridge session and streams the response token-by-token. Falls back to
 * File Bridge SSE relay if Sessions API is not available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val chatBubbleRepository: ChatBubbleRepository,
    private val sessionRepository: SessionRepository,
    private val audioStreamClient: AudioStreamClient,
    private val sessionsApiClient: SessionsApiClient,
    private val authManager: AnthropicAuthManager,
) : ViewModel() {

    // ── Session state ───────────────────────────────────────────────

    private val _sessionId = MutableStateFlow<String?>(null)
    private val _department = MutableStateFlow("")

    // ── Bubbles — collected from Room via Flow ──────────────────────

    /**
     * Reactive bubble list. Switches to the new session's Flow whenever
     * _sessionId changes. Room's InvalidationTracker fires on every
     * insert/upsert, so SSE pushes appear automatically.
     */
    val bubbles: StateFlow<List<ChatBubble>> = _sessionId
        .filterNotNull()
        .flatMapLatest { sid ->
            chatBubbleRepository.observeBubbles(sid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Loading state ───────────────────────────────────────────────

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingEarlier = MutableStateFlow(false)
    val isLoadingEarlier: StateFlow<Boolean> = _isLoadingEarlier.asStateFlow()

    private val _hasEarlier = MutableStateFlow(false)
    val hasEarlier: StateFlow<Boolean> = _hasEarlier.asStateFlow()

    // ── Audio playback ──────────────────────────────────────────────

    private val _playingSequence = MutableStateFlow<Int?>(null)
    val playingSequence: StateFlow<Int?> = _playingSequence.asStateFlow()

    private val _audioQueue = Channel<AudioRequest>(capacity = Channel.BUFFERED)

    // ── Sending state ───────────────────────────────────────────────

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // ── Streaming state ─────────────────────────────────────────────

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _streamingToolStatus = MutableStateFlow<String?>(null)
    val streamingToolStatus: StateFlow<String?> = _streamingToolStatus.asStateFlow()

    /** Active bridge session ID when using Sessions API streaming. */
    private var _activeStreamId: String? = null

    /** Bridge session ID for Sessions API. */
    private var _bridgeSessionId: String? = null

    data class AudioRequest(val text: String, val voice: String?, val sequence: Int)

    init {
        // Single consumer for the audio queue — ensures sequential playback
        viewModelScope.launch(Dispatchers.IO) {
            for (request in _audioQueue) {
                _playingSequence.value = request.sequence
                try {
                    audioStreamClient.replayMessage(
                        _department.value,
                        request.text,
                        request.voice
                    )
                } catch (e: Exception) {
                    Timber.e(e, "MessagesVM: audio playback failed for seq %d", request.sequence)
                } finally {
                    _playingSequence.value = null
                }
            }
        }
    }

    /**
     * Open a session — fetch tail from server, write to Room.
     * The Flow subscription in [bubbles] handles the rest.
     * No polling started. SSE push handles live updates.
     */
    fun loadSession(sessionId: String, department: String = "") {
        _sessionId.value = sessionId
        _department.value = department
        fetchTail(sessionId)
    }

    /**
     * Force refresh — clear Room cache for this session and reload.
     */
    fun refresh() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    chatBubbleRepository.refreshSession(sid)
                }
                _hasEarlier.value = result.hasEarlier
                Timber.d(
                    "MessagesVM: refreshed %d bubbles for %s",
                    result.count, sid.take(8),
                )
            } catch (e: Exception) {
                Timber.e(e, "MessagesVM: refresh failed for %s", sid.take(8))
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load the most recent bubbles (tail of the conversation).
     * Writes to Room — the Flow picks up the change automatically.
     */
    private fun fetchTail(sessionId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    chatBubbleRepository.loadTail(sessionId)
                }
                _hasEarlier.value = result.hasEarlier
                Timber.d(
                    "MessagesVM: loaded %d tail bubbles for %s (min=%d, max=%d, earlier=%b)",
                    result.count, sessionId.take(8),
                    result.minSequence, result.maxSequence, result.hasEarlier,
                )
            } catch (e: Exception) {
                Timber.e(e, "MessagesVM: tail fetch failed for %s", sessionId.take(8))
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load older messages when the user scrolls near the top.
     * Writes to Room — the Flow prepends them automatically.
     */
    fun loadEarlier() {
        val sessionId = _sessionId.value ?: return
        if (_isLoadingEarlier.value || !_hasEarlier.value) return

        viewModelScope.launch {
            _isLoadingEarlier.value = true
            try {
                val minSeq = withContext(Dispatchers.IO) {
                    chatBubbleRepository.getMinSequence(sessionId)
                }
                val result = withContext(Dispatchers.IO) {
                    chatBubbleRepository.loadBefore(sessionId, minSeq)
                }
                if (result.count > 0) {
                    _hasEarlier.value = result.hasEarlier
                    Timber.d(
                        "MessagesVM: prepended %d earlier bubbles (min_seq=%d)",
                        result.count, result.minSequence,
                    )
                } else {
                    _hasEarlier.value = false
                }
            } catch (e: Exception) {
                Timber.e(e, "MessagesVM: loadEarlier failed")
            } finally {
                _isLoadingEarlier.value = false
            }
        }
    }

    /**
     * Send a message to the department via cmail relay.
     * No need to poll after — SSE push will deliver the response.
     */
    fun sendMessage(department: String, message: String) {
        if (message.isBlank()) return
        val sid = _sessionId.value  // The session this screen is displaying
        viewModelScope.launch {
            _isSending.value = true
            try {
                withContext(Dispatchers.IO) {
                    sessionRepository.sendCmailRelay(
                        department = department,
                        message = message,
                        sessionId = sid,
                    )
                }
                // No refresh needed — SSE will push new bubbles via chat_watcher
            } catch (e: Exception) {
                Timber.e(e, "MessagesVM: send failed")
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * Catch up on missed bubbles — called when the screen resumes.
     */
    fun catchUp() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    chatBubbleRepository.loadTail(sid)
                }
                _hasEarlier.value = result.hasEarlier
                Timber.d(
                    "MessagesVM: catchUp loaded %d bubbles for %s (max=%d)",
                    result.count, sid.take(8), result.maxSequence,
                )
            } catch (e: Exception) {
                Timber.v("MessagesVM: catchUp failed (non-fatal): %s", e.message)
            }
        }
    }

    /**
     * Queue a dispatch bubble for audio playback.
     */
    fun replayDispatch(text: String, sequence: Int, voice: String? = null) {
        viewModelScope.launch {
            _audioQueue.send(AudioRequest(text, voice, sequence))
        }
    }

    // ── Streaming ───────────────────────────────────────────────────

    /**
     * Send a message with streaming response.
     *
     * If Anthropic Sessions API is configured (OAuth token + org UUID),
     * creates a bridge session and streams via the Sessions API SSE.
     * Otherwise falls back to File Bridge chat/stream SSE relay.
     *
     * Streaming tokens update [streamingText] in real-time.
     * Tool use events update [streamingToolStatus] for status overlay.
     */
    fun sendStreaming(department: String, message: String) {
        if (message.isBlank() || _isStreaming.value) return

        _isStreaming.value = true
        _streamingText.value = ""
        _streamingToolStatus.value = null
        _isSending.value = true

        val eventFlow: Flow<StreamEvent> = if (authManager.isFullyConfigured) {
            Timber.i("StreamChat: using Sessions API (bridge)")
            bridgeStreamFlow(message)
        } else {
            Timber.i("StreamChat: using File Bridge fallback")
            sessionRepository.streamChat(message, department)
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                eventFlow.collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            _streamingText.value += event.text
                        }
                        is StreamEvent.Thinking -> {
                            // Optionally show thinking
                        }
                        is StreamEvent.Tool -> {
                            _streamingToolStatus.value = mapToolStatus(event.name, event.status)
                        }
                        is StreamEvent.AssistantText -> {
                            _streamingText.value = event.text
                        }
                        is StreamEvent.Done -> {
                            Timber.i("StreamChat: done, cost=$%.4f, %dms",
                                event.costUsd, event.durationMs)
                            _bridgeSessionId = event.sessionId.ifBlank { _bridgeSessionId }
                        }
                        is StreamEvent.Connected -> {
                            _activeStreamId = event.streamId
                            _isSending.value = false
                        }
                        is StreamEvent.Status -> {
                            if (event.sessionId.isNotBlank()) {
                                _bridgeSessionId = event.sessionId
                            }
                        }
                        is StreamEvent.Error -> {
                            Timber.e("StreamChat: error %s — %s", event.errorType, event.result)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "StreamChat: collection failed")
            } finally {
                finalizeStreamingBubble()
            }
        }
    }

    /**
     * Create a streaming Flow via the Anthropic Sessions API bridge.
     * Creates a new session (or reuses existing) and subscribes to SSE events.
     */
    private fun bridgeStreamFlow(message: String): Flow<StreamEvent> = flow {
        // Reuse existing bridge session or create a new one
        val sessionId = _bridgeSessionId ?: sessionsApiClient.createSession(message)
        if (sessionId == null) {
            emit(StreamEvent.Error("session_error", "Failed to create bridge session"))
            return@flow
        }
        _bridgeSessionId = sessionId

        // If reusing session, send the new message as an event
        if (_bridgeSessionId != null && _bridgeSessionId == sessionId) {
            val sent = sessionsApiClient.sendEvent(sessionId, message)
            if (!sent) {
                // Session may be dead — create a new one
                val newSessionId = sessionsApiClient.createSession(message) ?: run {
                    emit(StreamEvent.Error("session_error", "Failed to create new bridge session"))
                    return@flow
                }
                _bridgeSessionId = newSessionId
            }
        }

        // Poll for new events by counting what we have
        val currentEvents = sessionsApiClient.getSessionEvents(sessionId)
        val eventCountBefore = currentEvents?.size ?: 0

        // Subscribe to the session's SSE event stream
        sessionsApiClient.subscribeToSession(sessionId).collect { event ->
            emit(event)
        }
    }

    /**
     * Finalize the streaming bubble — flush accumulated text into Room
     * so it persists after the stream ends.
     */
    private fun finalizeStreamingBubble() {
        val text = _streamingText.value.trim()
        _streamingText.value = ""
        _isStreaming.value = false
        _streamingToolStatus.value = null

        if (text.isBlank()) return
        val sid = _sessionId.value ?: return

        // Refresh from server to pick up the finalized bubble
        viewModelScope.launch {
            try {
                chatBubbleRepository.loadTail(sid)
            } catch (e: Exception) {
                Timber.v("MessagesVM: post-stream refresh failed (non-fatal): %s", e.message)
            }
        }
    }

    companion object {
        /**
         * Map tool name + status to a human-readable status string for the UI overlay.
         */
        private fun mapToolStatus(toolName: String, status: String): String {
            if (status == "completed" || status == "stopped") return ""

            val action = when (toolName) {
                "Bash" -> "Running a command"
                "Read" -> "Reading files"
                "Write" -> "Writing code"
                "Edit" -> "Editing code"
                "Glob" -> "Finding files"
                "Grep" -> "Searching code"
                "Agent" -> "Delegating to a subagent"
                "WebSearch" -> "Searching the web"
                "WebFetch" -> "Fetching a page"
                "TodoWrite" -> "Updating tasks"
                else -> "Using $toolName"
            }
            return "$action..."
        }
    }
}

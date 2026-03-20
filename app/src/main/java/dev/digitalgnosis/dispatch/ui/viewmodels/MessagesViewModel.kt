package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.data.ChatBubbleRepository
import dev.digitalgnosis.dispatch.data.SessionRepository
import dev.digitalgnosis.dispatch.data.StreamEvent
import dev.digitalgnosis.dispatch.network.AudioStreamClient
import dev.digitalgnosis.dispatch.playback.StreamingTtsQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for MessagesScreen — real-time chat via File Bridge SSE.
 *
 * Architecture:
 *   SessionRepository.streamChat() → SSE → StreamEvent flow → this ViewModel → Compose
 *   ChatBubbleRepository → Room DAO → InvalidationTracker → bubbles flow
 *
 * All messages flow through File Bridge POST /chat/stream (SSE).
 * No WebSocket, no Anthropic Sessions API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val chatBubbleRepository: ChatBubbleRepository,
    private val audioStreamClient: AudioStreamClient,
    private val sessionRepository: SessionRepository,
    private val streamingTtsQueue: StreamingTtsQueue,
) : ViewModel() {

    // ── Session state ───────────────────────────────────────────────

    private val _sessionId = MutableStateFlow<String?>(null)
    private val _department = MutableStateFlow("")

    // ── Bubbles — collected from Room via Flow ──────────────────────

    /**
     * Reactive bubble list. Switches to the new session's Flow whenever
     * _sessionId changes. Room's InvalidationTracker fires on every
     * insert/upsert, so pushes appear automatically.
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

    // ── Internal ────────────────────────────────────────────────────

    private var streamJob: Job? = null

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

    // ═══════════════════════════════════════════════════════════════
    //  SESSION LOADING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Open a session — fetch tail from File Bridge, set session context.
     * The bubble Flow handles UI updates automatically via Room.
     */
    fun loadSession(sessionId: String, department: String = "") {
        _sessionId.value = sessionId
        _department.value = department
        fetchTail(sessionId)
    }

    /**
     * Force refresh — clear Room cache for this session and reload from File Bridge.
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
                Timber.d("MessagesVM: refreshed %d bubbles for %s", result.count, sid.take(8))
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

    // ═══════════════════════════════════════════════════════════════
    //  SENDING — VIA FILE BRIDGE SSE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a message via File Bridge SSE (POST /chat/stream).
     *
     * Tokens stream back word-by-word via SSE events into [streamingText].
     * When done, the finalized bubble is loaded from File Bridge into Room.
     */
    fun sendStreaming(department: String, message: String) {
        if (message.isBlank() || _isStreaming.value) return

        _isStreaming.value = true
        _streamingText.value = ""
        _streamingToolStatus.value = null
        _isSending.value = true
        _department.value = department
        streamingTtsQueue.reset()

        streamJob?.cancel()
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.streamChat(
                    message = message,
                    department = department.ifBlank { null },
                ).collect { event ->
                    handleStreamEvent(event)
                }
            } catch (e: Exception) {
                Timber.e(e, "MessagesVM: sendStreaming failed")
                _isStreaming.value = false
                _isSending.value = false
                _streamingToolStatus.value = null
            }
        }
    }

    private fun handleStreamEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.Token -> {
                _streamingText.value += event.text
                _isSending.value = false
            }

            is StreamEvent.Thinking -> {
                Timber.v("MessagesVM: thinking chunk (%d chars)", event.thinking.length)
            }

            is StreamEvent.Tool -> {
                _streamingToolStatus.value = mapToolStatus(event.name, event.status)
            }

            is StreamEvent.AssistantText -> {
                _streamingText.value = event.text
                _isSending.value = false
            }

            is StreamEvent.Sentence -> {
                Timber.v("MessagesVM: sentence → TTS (%d chars)", event.text.length)
                streamingTtsQueue.enqueue(event.text, _department.value)
            }

            is StreamEvent.Done -> {
                Timber.i("MessagesVM: done, cost=$%.4f, %dms, stop=%s",
                    event.costUsd, event.durationMs, event.stopReason)
                finalizeStreamingBubble()
            }

            is StreamEvent.Connected -> {
                _isSending.value = false
                Timber.i("MessagesVM: SSE connected (stream=%s)", event.streamId.take(20))
            }

            is StreamEvent.Status -> {
                if (event.sessionId.isNotBlank()) {
                    _sessionId.value = event.sessionId
                }
                Timber.i("MessagesVM: status=%s model=%s", event.status, event.model)
            }

            is StreamEvent.Error -> {
                Timber.e("MessagesVM: error %s — %s", event.errorType, event.result)
                finalizeStreamingBubble()
            }

            is StreamEvent.PermissionRequest -> {
                // Permission requests not used in File Bridge path
                Timber.w("MessagesVM: unexpected PermissionRequest in File Bridge SSE path")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  STREAMING FINALIZATION
    // ═══════════════════════════════════════════════════════════════

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

        // Refresh from File Bridge to pick up the finalized bubble
        viewModelScope.launch {
            try {
                chatBubbleRepository.loadTail(sid)
            } catch (e: Exception) {
                Timber.v("MessagesVM: post-stream refresh failed (non-fatal): %s", e.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
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

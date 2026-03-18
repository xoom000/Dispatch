package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.data.ChatBubbleRepository
import dev.digitalgnosis.dispatch.data.SessionRepository
import dev.digitalgnosis.dispatch.network.AudioStreamClient
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val chatBubbleRepository: ChatBubbleRepository,
    private val sessionRepository: SessionRepository,
    private val audioStreamClient: AudioStreamClient,
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
                        sessionId = sid,  // Pass the conversation's session ID — Google Messages pattern
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
     *
     * When the app goes to background, SSE disconnects. When it comes
     * back, we need to fetch any bubbles that arrived while we were away.
     * This re-fetches the tail and upserts into Room. The Flow handles
     * the UI update automatically via Room's InvalidationTracker.
     *
     * This is the equivalent of Google Messages' "pull on tickle" —
     * except our tickle is "the app became visible again."
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
}

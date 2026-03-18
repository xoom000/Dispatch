package dev.digitalgnosis.dispatch.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared playback state between AudioPlaybackService and Compose UI.
 *
 * The service writes state here. The MiniPlayerBar composable reads it.
 * Singleton via Hilt so both sides see the same instance.
 */
@Singleton
class PlaybackStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** Called by AudioPlaybackService when a new message starts playing. */
    fun onPlaybackStarted(sender: String, message: String, voice: String) {
        _state.value = PlaybackUiState(
            isActive = true,
            isPlaying = true,
            sender = sender,
            messagePreview = message.take(120),
            voice = voice,
        )
    }

    /** Called when playback is paused (earbud single tap). */
    fun onPlaybackPaused() {
        _state.value = _state.value.copy(isPlaying = false)
    }

    /** Called when playback resumes after pause. */
    fun onPlaybackResumed() {
        _state.value = _state.value.copy(isPlaying = true)
    }

    /** Called when all queued messages finish playing. */
    fun onQueueEmpty() {
        _state.value = _state.value.copy(isPlaying = false, isActive = false)
    }

    /** Called when pending count changes. */
    fun onQueueCountChanged(pending: Int) {
        _state.value = _state.value.copy(pendingCount = pending)
    }

    /** Called when voice reply recording starts. */
    fun onVoiceReplyStarted(targetDept: String) {
        _state.value = _state.value.copy(
            isRecording = true,
            replyTarget = targetDept,
        )
    }

    /** Called when voice reply recording ends. */
    fun onVoiceReplyEnded() {
        _state.value = _state.value.copy(
            isRecording = false,
            replyTarget = null,
        )
    }
}

/**
 * UI-facing playback state. Immutable data class observed by MiniPlayerBar.
 */
data class PlaybackUiState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val sender: String = "",
    val messagePreview: String = "",
    val voice: String = "",
    val pendingCount: Int = 0,
    val isRecording: Boolean = false,
    val replyTarget: String? = null,
)

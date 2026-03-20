package dev.digitalgnosis.dispatch.playback

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedFlow-based event bus for all player state and events.
 *
 * Replaces [dev.digitalgnosis.dispatch.audio.PlaybackStateHolder] which used a single
 * god-state StateFlow. Each dimension of playback state gets its own flow so collectors
 * only wake up for changes they care about.
 *
 * Pattern: KotlinAudio PlayerEventHolder.kt — MutableSharedFlow(replay=1) per event.
 *
 * Two directions:
 *   Service → UI:  stateChange, queueCountChange, nowPlayingChange, audioFocusChange
 *   UI → Service:  Not here — UI sends commands through MediaController (MediaBrowser).
 *
 * All flows use replay=1 so new subscribers immediately receive the current value
 * without waiting for the next emission.
 */
@Singleton
class DispatchPlayerEventBus @Inject constructor() {

    private val scope = MainScope()

    // ── Playback state ─────────────────────────────────────────────

    private val _stateChange = MutableSharedFlow<DispatchPlaybackState>(replay = 1)
    /** Current playback state. Replays the last value to new collectors. */
    val stateChange: SharedFlow<DispatchPlaybackState> = _stateChange.asSharedFlow()

    // ── Queue ──────────────────────────────────────────────────────

    private val _queueCountChange = MutableSharedFlow<Int>(replay = 1)
    /** Number of messages pending in the queue (excluding currently playing). */
    val queueCountChange: SharedFlow<Int> = _queueCountChange.asSharedFlow()

    // ── Now playing ────────────────────────────────────────────────

    private val _nowPlayingChange = MutableSharedFlow<NowPlayingInfo?>(replay = 1)
    /** Metadata about the currently playing message, or null when idle. */
    val nowPlayingChange: SharedFlow<NowPlayingInfo?> = _nowPlayingChange.asSharedFlow()

    // ── Audio focus ────────────────────────────────────────────────

    private val _audioFocusChange = MutableSharedFlow<AudioFocusEvent>(replay = 1)
    /** Emitted when audio focus is gained, lost, or ducked. */
    val audioFocusChange: SharedFlow<AudioFocusEvent> = _audioFocusChange.asSharedFlow()

    // ── Playback errors ────────────────────────────────────────────

    private val _playbackError = MutableSharedFlow<PlaybackErrorEvent>(replay = 1)
    /** Emitted when ExoPlayer reports a fatal playback error. */
    val playbackError: SharedFlow<PlaybackErrorEvent> = _playbackError.asSharedFlow()

    // ── External player actions (earbud / notification / Auto) ───────

    private val _externalAction = MutableSharedFlow<ExternalPlayerAction>()
    /**
     * Emitted when a player command is triggered from outside the app:
     * Bluetooth headset, Android Auto, notification button, Google Assistant.
     * No replay — these are one-shot commands.
     */
    val externalAction: SharedFlow<ExternalPlayerAction> = _externalAction.asSharedFlow()

    // ── Voice reply ────────────────────────────────────────────────

    private val _voiceReplyState = MutableStateFlow<VoiceReplyState>(VoiceReplyState.Idle)
    /** Current state of the voice reply flow. StateFlow — always has a value. */
    val voiceReplyState: StateFlow<VoiceReplyState> = _voiceReplyState.asStateFlow()

    // ── Emit helpers (called by DispatchPlaybackService / PlayerEventListener) ──

    internal fun emitState(state: DispatchPlaybackState) {
        scope.launch { _stateChange.emit(state) }
    }

    internal fun emitQueueCount(count: Int) {
        scope.launch { _queueCountChange.emit(count) }
    }

    internal fun emitNowPlaying(info: NowPlayingInfo?) {
        scope.launch { _nowPlayingChange.emit(info) }
    }

    internal fun emitAudioFocus(event: AudioFocusEvent) {
        scope.launch { _audioFocusChange.emit(event) }
    }

    internal fun emitPlaybackError(event: PlaybackErrorEvent) {
        scope.launch { _playbackError.emit(event) }
    }

    internal fun emitExternalAction(action: ExternalPlayerAction) {
        scope.launch { _externalAction.emit(action) }
    }

    internal fun emitVoiceReplyState(state: VoiceReplyState) {
        _voiceReplyState.value = state
    }
}

/**
 * 9-state playback state machine.
 * Pattern: KotlinAudio AudioPlayerState enum — covers all ExoPlayer states plus ERROR.
 */
enum class DispatchPlaybackState {
    IDLE,       // Player created, no media loaded
    LOADING,    // Media item being prepared / DataSource opening
    BUFFERING,  // Media prepared, waiting for sufficient buffer
    READY,      // Buffer sufficient, will play when play() called
    PLAYING,    // Actively rendering audio
    PAUSED,     // User-paused (or audio focus transient loss)
    STOPPED,    // Explicitly stopped (not just paused)
    ENDED,      // Current item finished, queue may have more
    ERROR,      // Fatal playback error — see playbackError flow
}

/** Metadata about the currently playing voice message. */
data class NowPlayingInfo(
    val sender: String,
    val messagePreview: String,
    val voice: String,
    val traceId: String? = null,
)

/** Audio focus event emitted when focus changes. */
data class AudioFocusEvent(
    val isPaused: Boolean,
    val isPermanent: Boolean,
)

/** Playback error with message for logging/display. */
data class PlaybackErrorEvent(
    val message: String,
    val cause: Throwable? = null,
)

/** Commands triggered externally (earbud, Auto, notification, Assistant). */
sealed class ExternalPlayerAction {
    object Play : ExternalPlayerAction()
    object Pause : ExternalPlayerAction()
    object SkipNext : ExternalPlayerAction()
    object SkipPrevious : ExternalPlayerAction()
    data class SeekTo(val positionMs: Long) : ExternalPlayerAction()
    object VoiceReplyRequested : ExternalPlayerAction()
}

/** State of the voice reply flow (recording → sending → done). */
sealed class VoiceReplyState {
    object Idle : VoiceReplyState()
    data class Recording(val targetSender: String) : VoiceReplyState()
    data class Sending(val targetSender: String, val transcript: String) : VoiceReplyState()
    object Sent : VoiceReplyState()
    data class Error(val message: String) : VoiceReplyState()
}

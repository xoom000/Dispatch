package dev.digitalgnosis.dispatch.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.digitalgnosis.dispatch.audio.PlaybackStateHolder
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * ExoPlayer event listener for [DispatchPlaybackService].
 *
 * Handles the three events that drive the service's message queue:
 * - [onPlaybackStateChanged] STATE_ENDED: dequeue next message or signal queue empty
 * - [onMediaItemTransition]: decrement count when ExoPlayer advances its internal playlist (WAV path)
 * - [onPlayerError]: recover by skipping to next queued message
 * - [onIsPlayingChanged]: update [PlaybackStateHolder] for UI
 *
 * [callbacks] provides access to queue state and service-level operations
 * without making this class an inner class of [DispatchPlaybackService].
 */
internal class PlayerEventListener(
    private val pendingCount: AtomicInteger,
    private val playbackState: PlaybackStateHolder,
    private val callbacks: Callbacks,
) : Player.Listener {

    interface Callbacks {
        val messageQueueSize: Int
        fun playNextFromQueue()
        fun flushLogs()
        fun onQueueBecameEmpty() {}
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            // Previous item finished, next started automatically
            val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
            Timber.i("DispatchPlaybackService: media transition, pending=%d", remaining)
            playbackState.onQueueCountChanged(remaining)
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_ENDED -> {
                val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
                Timber.i("DispatchPlaybackService: playback ended, pending=%d, queued=%d",
                    remaining, callbacks.messageQueueSize)
                callbacks.flushLogs()

                // Play next queued message if any
                if (callbacks.messageQueueSize > 0) {
                    callbacks.playNextFromQueue()
                } else if (remaining <= 0) {
                    callbacks.onQueueBecameEmpty()
                    playbackState.onQueueEmpty()
                }
            }
            Player.STATE_READY -> {
                Timber.d("DispatchPlaybackService: player ready")
            }
            Player.STATE_BUFFERING -> {
                Timber.d("DispatchPlaybackService: player buffering")
            }
            else -> {}
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "DispatchPlaybackService: ExoPlayer error — %s", error.message)
        pendingCount.decrementAndGet().coerceAtLeast(0)

        // Try next queued message if any
        if (callbacks.messageQueueSize > 0) {
            Timber.i("DispatchPlaybackService: error recovery — playing next from queue")
            callbacks.playNextFromQueue()
        } else {
            pendingCount.set(0)
            callbacks.onQueueBecameEmpty()
            playbackState.onQueueEmpty()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            playbackState.onPlaybackResumed()
        } else {
            playbackState.onPlaybackPaused()
        }
    }
}

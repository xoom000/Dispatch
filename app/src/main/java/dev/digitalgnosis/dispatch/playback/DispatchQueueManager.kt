package dev.digitalgnosis.dispatch.playback

import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clean queue API wrapping Dispatch's in-process message queue and ExoPlayer's playlist.
 *
 * Dispatch uses a two-layer queue:
 *   Layer 1 — [pendingMessages] (ArrayDeque<PendingMessage>): in-process queue for streaming
 *             items that cannot be pre-buffered by ExoPlayer (each item is a one-shot HTTP POST).
 *   Layer 2 — ExoPlayer's internal playlist: used for WAV/file items (status messages).
 *             For streaming items, ExoPlayer's playlist is always length 1.
 *
 * This class owns Layer 1. Layer 2 is managed directly by ExoPlayer.
 *
 * Pattern: KotlinAudio QueuedAudioPlayer.kt — matches the API surface exactly.
 *   Key insight: remove(indexes) sorts descending before removing to preserve index stability.
 *
 * Thread safety: all methods must be called from the main thread (ExoPlayer's contract).
 */
@Singleton
class DispatchQueueManager @Inject constructor(
    private val player: ExoPlayer,
) {
    /** Messages waiting to play once the current item finishes. */
    private val pendingMessages = ArrayDeque<PendingMessage>()

    /** Total messages in the pipeline: playing + queued. Updated atomically by the event listener. */
    private val pendingCount = AtomicInteger(0)

    // ── Pending message data ───────────────────────────────────────

    /** A voice message waiting in the software queue. */
    data class PendingMessage(
        val text: String,
        val voice: String,
        val sender: String,
        val traceId: String?,
        val fcmReceiveTime: Long = System.currentTimeMillis(),
    )

    // ── Queue state ────────────────────────────────────────────────

    val size: Int get() = pendingMessages.size

    val isEmpty: Boolean get() = pendingMessages.isEmpty()

    val isNotEmpty: Boolean get() = pendingMessages.isNotEmpty()

    val pendingTotal: Int get() = pendingCount.get()

    /** All messages currently waiting (not the currently playing one). */
    val snapshot: List<PendingMessage> get() = pendingMessages.toList()

    // ── Add ────────────────────────────────────────────────────────

    /**
     * Enqueue a message. Returns the new queue size after adding.
     * The caller is responsible for calling [dequeue] if the player is idle
     * (i.e., this is the first item).
     */
    fun enqueue(message: PendingMessage): Int {
        pendingMessages.addLast(message)
        val total = pendingCount.incrementAndGet()
        Timber.d("DispatchQueueManager: enqueued '%s', queue=%d pending=%d",
            message.sender, pendingMessages.size, total)
        return pendingMessages.size
    }

    // ── Remove ─────────────────────────────────────────────────────

    /**
     * Remove messages at the given indexes (1-based into the pending queue, not the playing item).
     * Sorts descending before removal to preserve index stability.
     * Pattern: KotlinAudio QueuedAudioPlayer.remove(indexes: List<Int>)
     */
    fun remove(indexes: List<Int>) {
        val sorted = indexes.toMutableList()
        sorted.sortDescending()
        sorted.forEach { idx ->
            if (idx in 0 until pendingMessages.size) {
                pendingMessages.removeAt(idx)
                pendingCount.decrementAndGet().coerceAtLeast(0)
            }
        }
    }

    /** Remove all pending messages (does not stop the currently playing item). */
    fun clear() {
        val removed = pendingMessages.size
        pendingMessages.clear()
        if (removed > 0) {
            // Adjust pendingCount: keep 1 for the currently playing item (if any)
            val current = pendingCount.get()
            val newCount = (current - removed).coerceAtLeast(if (current > 0) 1 else 0)
            pendingCount.set(newCount)
            Timber.d("DispatchQueueManager: cleared %d pending messages", removed)
        }
    }

    // ── Dequeue (internal, called by service on STATE_ENDED) ───────────

    /**
     * Remove and return the next pending message, or null if the queue is empty.
     * Called by [dev.digitalgnosis.dispatch.playback.DispatchPlaybackService] when
     * ExoPlayer fires STATE_ENDED and the service is ready to play the next item.
     */
    fun dequeue(): PendingMessage? = pendingMessages.removeFirstOrNull()

    // ── Count management (called by PlayerEventListener) ───────────

    /**
     * Decrement the pending count after an item finishes playing.
     * Returns the new count (clamped to 0).
     */
    fun decrementPending(): Int = pendingCount.decrementAndGet().coerceAtLeast(0)

    /**
     * Reset pending count to 0. Called on error recovery when the queue is drained.
     */
    fun resetPending() {
        pendingCount.set(0)
    }

    // ── ExoPlayer playlist delegation ─────────────────────────────

    /**
     * Current index in ExoPlayer's internal playlist (Layer 2).
     * For streaming-only operation, this is always 0 or C.INDEX_UNSET.
     */
    val currentExoIndex: Int get() = player.currentMediaItemIndex

    /**
     * Number of items in ExoPlayer's playlist.
     * For streaming, this is 0 or 1.
     */
    val exoPlaylistSize: Int get() = player.mediaItemCount

    /**
     * Jump to a specific index in ExoPlayer's playlist.
     * Pattern: KotlinAudio QueuedAudioPlayer.jumpToItem()
     */
    fun jumpToExoItem(index: Int) {
        try {
            player.seekTo(index, androidx.media3.common.C.TIME_UNSET)
            player.prepare()
        } catch (e: Exception) {
            Timber.e(e, "DispatchQueueManager: jumpToItem(%d) failed, playlist size=%d",
                index, player.mediaItemCount)
        }
    }

    /**
     * Remove upcoming items from ExoPlayer's playlist.
     * Pattern: KotlinAudio QueuedAudioPlayer.removeUpcomingItems()
     */
    fun removeExoUpcomingItems() {
        val fromIndex = player.currentMediaItemIndex + 1
        val toIndex = player.mediaItemCount
        if (fromIndex < toIndex) {
            player.removeMediaItems(fromIndex, toIndex)
        }
    }

    /**
     * Remove previous items from ExoPlayer's playlist.
     * Pattern: KotlinAudio QueuedAudioPlayer.removePreviousItems()
     */
    fun removeExoPreviousItems() {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex > 0) {
            player.removeMediaItems(0, currentIndex)
        }
    }
}

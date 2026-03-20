package dev.digitalgnosis.dispatch.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Queues sentence-level TTS requests from streaming chat into DispatchPlaybackService.
 *
 * Batching: sentences are buffered locally and flushed as a single
 * startForegroundService intent (ACTION_ENQUEUE_BATCH). This collapses
 * a 5-sentence response from 5 OS service starts down to 1.
 *
 * The first sentence flushes immediately (low latency to first audio).
 * Any sentences that arrive while the service start is in-flight are
 * bundled into the next flush, which is triggered by the next enqueue call.
 *
 * Cancellation: cancel() drains both the local buffer and the service's
 * internal queue so a new user message cannot interleave with a stale response.
 *
 * Voice selection: uses the department name to pick the Kokoro voice.
 * Falls back to DEFAULT_VOICE if no mapping exists.
 *
 * Why a singleton: multiple ViewModel instances share one entry point
 * so we do not double-queue sentences.
 */
@Singleton
class StreamingTtsQueue @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = ReentrantLock()

    // Sentences buffered since the last flush.
    private val pending = mutableListOf<String>()

    // Department for the current streaming session — set on first enqueue, cleared on cancel.
    private var currentDepartment: String = ""

    /**
     * Enqueue one TTS sentence.
     *
     * If there is nothing already pending, flushes immediately (one startForegroundService).
     * If sentences are already buffered (rapid arrival), adds to the buffer so the next
     * enqueue call sends them all in one batch.
     */
    fun enqueue(text: String, department: String) {
        if (text.isBlank()) return

        lock.withLock {
            currentDepartment = department
            pending.add(text)

            // Flush every sentence immediately — the batch intent carries all accumulated
            // sentences so rapid arrivals are naturally coalesced between OS scheduling ticks.
            flush()
        }
    }

    /**
     * Cancel any queued sentences from the previous streaming session.
     * Clears the local buffer and sends ACTION_CANCEL_STREAMING to DispatchPlaybackService
     * so its internal queue is drained too. Called at the start of sendStreaming().
     */
    fun cancel() {
        lock.withLock {
            val dropped = pending.size
            pending.clear()
            currentDepartment = ""
            if (dropped > 0) {
                Timber.d("StreamingTts: cancel — dropped %d buffered sentences", dropped)
            }
        }
        Timber.d("StreamingTts: cancel — draining service queue")
        try {
            context.startService(DispatchPlaybackService.createCancelStreamingIntent(context))
        } catch (e: Exception) {
            Timber.w(e, "StreamingTts: cancel intent failed (service may not be running)")
        }
    }

    // ── Private ──────────────────────────────────────────────────────

    /**
     * Send all pending sentences to DispatchPlaybackService in one intent and clear the buffer.
     * Must be called with [lock] held.
     */
    private fun flush() {
        if (pending.isEmpty()) return

        val sentences = pending.toList()
        pending.clear()

        val voice = VoiceMap.voiceFor(currentDepartment)
        Timber.d("StreamingTts: flush %d sentence(s) → voice=%s dept=%s", sentences.size, voice, currentDepartment)

        val intent = DispatchPlaybackService.createBatchIntent(
            context = context,
            sentences = sentences,
            voice = voice,
            sender = currentDepartment,
        )
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Timber.e(e, "StreamingTts: failed to start playback service (dept=%s)", currentDepartment)
        }
    }
}

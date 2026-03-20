package dev.digitalgnosis.dispatch.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queues sentence-level TTS requests from streaming chat into DispatchPlaybackService.
 *
 * Called by MessagesViewModel each time a StreamEvent.Sentence arrives. Each sentence
 * fires a startForegroundService intent — DispatchPlaybackService's internal messageQueue
 * serialises them so they play in order, one after the other.
 *
 * Voice selection: uses the department name to pick the Kokoro voice. Falls back to
 * DEFAULT_VOICE if no mapping exists.
 *
 * Why a singleton: multiple ViewModel instances (MessagesViewModel, future screens)
 * should share one entry point so we don't double-queue sentences.
 */
@Singleton
class StreamingTtsQueue @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Enqueue one TTS sentence. Fires a startForegroundService intent immediately. */
    fun enqueue(text: String, department: String) {
        if (text.isBlank()) return

        val voice = VoiceMap.voiceFor(department)
        Timber.d("StreamingTts: enqueue %d chars → voice=%s dept=%s", text.length, voice, department)

        val intent = DispatchPlaybackService.createIntent(
            context = context,
            text = text,
            voice = voice,
            sender = department,
            message = text,
            traceId = null,
            fcmReceiveTime = 0L,
        )
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Timber.e(e, "StreamingTts: failed to start playback service (dept=%s)", department)
        }
    }

    /**
     * Cancel any queued sentences from the previous streaming session.
     * Called at the start of sendStreaming() to prevent the previous response's
     * remaining sentences from interleaving with the new session's sentences.
     *
     * Sends ACTION_CANCEL_STREAMING to DispatchPlaybackService, which drains
     * all queued items that came from streaming TTS (fcmReceiveTime == 0L).
     */
    fun cancel() {
        Timber.d("StreamingTts: cancel — draining queued sentences")
        try {
            context.startService(DispatchPlaybackService.createCancelStreamingIntent(context))
        } catch (e: Exception) {
            Timber.w(e, "StreamingTts: cancel intent failed (service may not be running)")
        }
    }

    companion object {
        // Voice mapping is in VoiceMap.kt — single source of truth.
    }
}

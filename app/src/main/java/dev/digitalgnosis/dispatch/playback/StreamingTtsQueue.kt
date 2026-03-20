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

        val voice = voiceForDepartment(department)
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
     * Reset state between streaming sessions.
     * No internal queue here — DispatchPlaybackService owns the queue.
     * This is a hook for future rate-limiting or cancellation logic.
     */
    fun reset() {
        Timber.d("StreamingTts: reset")
    }

    private fun voiceForDepartment(department: String): String {
        return when (department.lowercase()) {
            "eng", "engineering" -> "am_puck"
            "pm", "product" -> "am_michael"
            "design", "ux" -> "af_sky"
            "boardroom" -> "am_puck"
            "data", "analytics" -> "am_michael"
            else -> DEFAULT_VOICE
        }
    }

    companion object {
        private const val DEFAULT_VOICE = "am_puck"
    }
}

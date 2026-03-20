package dev.digitalgnosis.dispatch.playback

/**
 * Parameters for a pending TTS stream request.
 * Stored in [DispatchPlaybackService.streamRegistry] so that [TtsStreamDataSource.open]
 * can retrieve them when ExoPlayer opens the tts:// URI (which may happen on a different thread).
 */
internal data class StreamRequest(
    val text: String,
    val voice: String,
    val speed: Float,
    val traceId: String?,
    val startTime: Long = System.currentTimeMillis(),
)

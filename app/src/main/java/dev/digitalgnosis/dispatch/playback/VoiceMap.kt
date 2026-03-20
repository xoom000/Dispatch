package dev.digitalgnosis.dispatch.playback

/**
 * Single source of truth for sender/department → Kokoro voice mapping.
 *
 * Used by DispatchFcmService (FCM voice messages) and StreamingTtsQueue
 * (streaming chat TTS sentences). The FCM-side mapping was authoritative
 * (13 entries, mirrors CLI dispatch config.py); the streaming-side had 7
 * entries with some conflicting values. This object consolidates both,
 * using FCM values where they conflict.
 */
object VoiceMap {

    private const val DEFAULT_VOICE = "am_michael"

    /**
     * Resolve a Kokoro voice for a sender or department name.
     * Case-insensitive. Falls back to DEFAULT_VOICE.
     */
    fun voiceFor(sender: String): String = when (sender.lowercase()) {
        "engineering", "eng" -> "am_michael"
        "watchman" -> "am_adam"
        "boardroom", "ceo" -> "am_eric"
        "dispatch" -> "am_puck"
        "aegis" -> "am_fenrir"
        "research" -> "bm_george"
        "hunter" -> "am_onyx"
        "alchemist" -> "am_liam"
        "prompt-engine" -> "am_echo"
        "trinity" -> "af_nova"
        "it" -> "am_adam"
        "cipher" -> "bm_lewis"
        "axiom" -> "bm_fable"
        // Streaming-chat-only departments
        "pm", "product" -> "am_michael"
        "design", "ux" -> "af_sky"
        "data", "analytics" -> "am_michael"
        else -> DEFAULT_VOICE
    }
}

package dev.digitalgnosis.dispatch.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-played voice message context for playback resumption.
 *
 * Pattern: Gramophone LastPlayedManager.kt (simplified for Dispatch's use case).
 *   Gramophone persists full MediaItem playlists with rich metadata. Dispatch's
 *   voice messages are ephemeral TTS streams — there is no file URI to persist.
 *   Instead we store enough context to:
 *     1. Resume the UI state (MiniPlayerBar shows "last playing: {sender}")
 *     2. Respond to [onPlaybackResumption] when BT headset connects after app kill
 *     3. Log which message was playing at the time of a crash/kill
 *
 * [onPlaybackResumption] in [DispatchPlaybackService] reads this and can replay
 * the last message's TTS via the GPU endpoint if the Kokoro server is reachable.
 *
 * Storage: SharedPreferences (not DataStore) — synchronous reads needed in
 * onPlaybackResumption which runs on the ExoPlayer looper thread.
 */
@Singleton
class DispatchLastPlayedManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "dispatch_last_played"
        private const val KEY_SENDER = "last_sender"
        private const val KEY_TEXT = "last_text"
        private const val KEY_VOICE = "last_voice"
        private const val KEY_TIMESTAMP = "last_timestamp"
        private const val KEY_TRACE_ID = "last_trace_id"
        private const val KEY_POSITION_MS = "last_position_ms"

        /** Stale threshold: don't resume messages older than 15 minutes. */
        private const val STALE_THRESHOLD_MS = 15L * 60 * 1000
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Save ───────────────────────────────────────────────────────

    /**
     * Store the currently playing message. Called when a new streaming item starts.
     * This is a fast write (apply, async) — safe to call on the main thread.
     */
    fun save(info: LastPlayedInfo) {
        prefs.edit {
            putString(KEY_SENDER, info.sender)
            putString(KEY_TEXT, info.text)
            putString(KEY_VOICE, info.voice)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            putString(KEY_TRACE_ID, info.traceId)
            putLong(KEY_POSITION_MS, info.positionMs)
        }
        Timber.d("DispatchLastPlayedManager: saved last played for sender='%s'", info.sender)
    }

    /**
     * Update only the playback position (called periodically during playback).
     * Lightweight — only writes the position field.
     */
    fun savePosition(positionMs: Long) {
        prefs.edit { putLong(KEY_POSITION_MS, positionMs) }
    }

    // ── Restore ────────────────────────────────────────────────────

    /**
     * Read back the last played info.
     * Returns null if nothing was saved or the entry is stale (>15 minutes old).
     *
     * Intentionally synchronous — [onPlaybackResumption] may be called on the
     * ExoPlayer playback thread where suspending is not available.
     */
    fun restore(): LastPlayedInfo? {
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        if (timestamp == 0L) {
            Timber.d("DispatchLastPlayedManager: no saved state")
            return null
        }

        val age = System.currentTimeMillis() - timestamp
        if (age > STALE_THRESHOLD_MS) {
            Timber.d("DispatchLastPlayedManager: saved state is stale (%dms old), ignoring", age)
            return null
        }

        val sender = prefs.getString(KEY_SENDER, null) ?: return null
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        val voice = prefs.getString(KEY_VOICE, null) ?: return null

        return LastPlayedInfo(
            sender = sender,
            text = text,
            voice = voice,
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
            traceId = prefs.getString(KEY_TRACE_ID, null),
        ).also {
            Timber.d("DispatchLastPlayedManager: restored last played sender='%s' age=%dms",
                sender, age)
        }
    }

    // ── Erase ──────────────────────────────────────────────────────

    /**
     * Erase the saved state. Called when the queue empties cleanly so we don't
     * offer stale resumption after normal playback completion.
     */
    fun erase() {
        prefs.edit { clear() }
        Timber.d("DispatchLastPlayedManager: erased saved state")
    }
}

/**
 * Last-played voice message context.
 *
 * Note: [text] is the TTS text (not a URI). On resumption, the service re-synthesizes
 * the text via the Kokoro GPU endpoint rather than seeking to a file position.
 * This means [positionMs] is informational only (for logging/UI) — not used for seeking.
 */
data class LastPlayedInfo(
    val sender: String,
    val text: String,
    val voice: String,
    val positionMs: Long = 0L,
    val traceId: String? = null,
)

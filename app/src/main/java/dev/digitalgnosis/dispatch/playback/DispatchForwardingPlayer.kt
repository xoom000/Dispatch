package dev.digitalgnosis.dispatch.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import timber.log.Timber

/**
 * Intercepts skip commands from the MediaSession (earbud gestures) for custom behavior:
 * - seekToNext()     → double tap on Pixel Buds → triggers voice reply
 * - seekToPrevious() → triple tap → reserved
 * - pause()          → user-initiated pause → notifies [onUserPaused] so new messages don't auto-resume
 * - play()           → user-initiated play → clears the pause flag via [onUserPlayed]
 *
 * [onVoiceReplyRequested] is called on the main thread when a double-tap is received.
 */
internal class DispatchForwardingPlayer(
    player: Player,
    private val onVoiceReplyRequested: () -> Unit,
    private val onUserPaused: () -> Unit = {},
    private val onUserPlayed: () -> Unit = {},
) : ForwardingPlayer(player) {

    override fun seekToNext() {
        Timber.i("MediaSession: double tap — starting voice reply")
        onVoiceReplyRequested()
    }

    override fun seekToPrevious() {
        Timber.i("MediaSession: triple tap — reserved")
        // Reserved for future use
    }

    override fun pause() {
        Timber.i("MediaSession: user pause")
        onUserPaused()
        super.pause()
    }

    override fun play() {
        Timber.i("MediaSession: user play")
        onUserPlayed()
        super.play()
    }
}

package dev.digitalgnosis.dispatch.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Wraps ExoPlayer to fix a known Media3 bug: when the player is in STATE_ENDED and
 * seekTo(0) is called (e.g., to replay), the state briefly becomes STATE_READY before
 * a play() call moves it to STATE_ENDED again — only then does Media3 wrap the playlist.
 *
 * This workaround keeps isEnded = true until a real discontinuity (seek) clears it,
 * making Media3 see STATE_ENDED consistently so it wraps the playlist immediately.
 *
 * Source: Gramophone GramophonePlaybackService / EndedWorkaroundPlayer.kt
 * (adapted — removed CircularShuffleOrder dependency, removed BuildConfig.DEBUG guard)
 */
class EndedWorkaroundPlayer(exoPlayer: ExoPlayer) : ForwardingSimpleBasePlayer(exoPlayer),
    Player.Listener {

    private val remoteDeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build()

    init {
        player.addListener(this)
    }

    val exoPlayer: ExoPlayer
        get() = player as ExoPlayer

    var isEnded = false

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == DISCONTINUITY_REASON_SEEK) {
            isEnded = false
        }
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
    }

    override fun getState(): State {
        if (isEnded) {
            val superState = super.state
            if (superState.playerError != null) {
                isEnded = false
                return superState
            }
            return superState.buildUpon().setPlaybackState(STATE_ENDED).setIsLoading(false).build()
        }
        if (player.currentTimeline.isEmpty) {
            return super.state.buildUpon().setDeviceInfo(remoteDeviceInfo).build()
        }
        return super.getState()
    }
}

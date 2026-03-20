package dev.digitalgnosis.dispatch.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaSession callback implementing the trusted/untrusted controller permission split.
 *
 * Pattern: Media3 DemoMediaLibrarySessionCallback.onConnect()
 *
 * Trusted controllers (same app, signed with same certificate):
 *   → Full DEFAULT_SESSION_AND_LIBRARY_COMMANDS + DEFAULT_PLAYER_COMMANDS
 *
 * Untrusted controllers (Android Auto, Wear OS, third-party apps):
 *   → Read-only: COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TRACKS, COMMAND_GET_METADATA
 *   → No play/pause/seek commands accepted from external sources
 *
 * This split addresses GAP-S6 (exported service without permission protection) at the
 * MediaSession layer: untrusted callers cannot inject play commands even though
 * the service is android:exported="true" for Bluetooth routing.
 */
@Singleton
class DispatchSessionCallback @Inject constructor() : MediaSession.Callback {

    @OptIn(UnstableApi::class)
    private val restrictedAccessPlayerCommands: Player.Commands =
        Player.Commands.Builder()
            .addAll(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_GET_METADATA,
            )
            .build()

    @OptIn(UnstableApi::class)
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        return if (controller.isTrusted) {
            Timber.d("DispatchSessionCallback: trusted controller connected: %s",
                controller.packageName)
            // Same-app controllers (MiniPlayerBar MediaController, internal use) get full access.
            MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        } else {
            Timber.d("DispatchSessionCallback: untrusted controller connected: %s — read-only",
                controller.packageName)
            // External controllers (Android Auto, Wear OS, etc.) get read-only metadata access.
            MediaSession.ConnectionResult.accept(
                SessionCommands.EMPTY,
                restrictedAccessPlayerCommands,
            )
        }
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        Timber.d("DispatchSessionCallback: controller disconnected: %s", controller.packageName)
    }
}

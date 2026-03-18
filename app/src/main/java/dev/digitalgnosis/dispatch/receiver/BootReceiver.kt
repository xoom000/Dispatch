package dev.digitalgnosis.dispatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.digitalgnosis.dispatch.network.SseConnectionService
import timber.log.Timber

/**
 * Receives BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts.
 * Starts the SSE connection service so DG Messages stays connected
 * even after device reboot or app update — without the user opening the app.
 *
 * Same pattern as Google Messages' PhoneBootAndPackageReplacedReceiver.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.i("BootReceiver: %s — starting SseConnectionService", intent.action)
                SseConnectionService.start(context)
            }
        }
    }
}

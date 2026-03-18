package dev.digitalgnosis.dispatch

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.logging.BigNickTimberTree
import dev.digitalgnosis.dispatch.logging.FileLogTree
import dev.digitalgnosis.dispatch.logging.InMemoryLogTree
import dev.digitalgnosis.dispatch.network.SseConnectionService
import dev.digitalgnosis.dispatch.tts.ModelManager
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class DispatchApplication : Application() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var modelManager: ModelManager

    override fun onCreate() {
        super.onCreate()

        // Plant logging trees: InMemoryLogTree (in-app viewer) + FileLogTree (SSH tail) + BigNickTimberTree (server xfil)
        Timber.plant(InMemoryLogTree.init())
        val fileLogTree = FileLogTree.init(this)
        Timber.plant(fileLogTree)
        Timber.plant(BigNickTimberTree())

        val storageMode = if (fileLogTree.isUsingSharedStorage()) "SHARED" else "PRIVATE"
        Timber.i("FileLogTree: %s - %s", storageMode, fileLogTree.getLogDirectoryPath())

        Timber.i("=== Dispatch Application starting ===")

        try {
            retrieveFcmToken()
        } catch (e: Throwable) {
            Timber.e(e, "FCM token retrieval failed")
        }

        try {
            modelManager.initialize()
            Timber.i("ModelManager initialization triggered")
        } catch (e: Throwable) {
            Timber.e(e, "ModelManager initialization failed")
        }

        try {
            SseConnectionService.start(this)
            Timber.i("SseConnectionService started")
        } catch (e: Throwable) {
            Timber.e(e, "SseConnectionService start failed")
        }

        // EventStreamClient removed — SseConnectionService is the sole SSE owner.
        // It handles all event types with exponential backoff reconnection.
    }

    private fun retrieveFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Timber.w(task.exception, "FCM token retrieval failed")
                    return@addOnCompleteListener
                }
                val token = task.result
                Timber.i("FCM token: %s", token)
                tokenManager.saveToken(token)
            }
    }
}

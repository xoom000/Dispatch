package dev.digitalgnosis.dispatch

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dev.digitalgnosis.dispatch.appfunctions.AppFunctionEntryPoint
import dev.digitalgnosis.dispatch.appfunctions.DispatchAppFunctions
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.logging.BigNickTimberTree
import dev.digitalgnosis.dispatch.logging.CrashlyticsTree
import dev.digitalgnosis.dispatch.logging.FileLogTree
import dev.digitalgnosis.dispatch.logging.InMemoryLogTree
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.util.ComposeStateWriteGuard
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class DispatchApplication : Application(), AppFunctionConfiguration.Provider {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var modelManager: ModelManager

    override val appFunctionConfiguration: AppFunctionConfiguration
        get() {
            val ep = EntryPointAccessors.fromApplication(this, AppFunctionEntryPoint::class.java)
            return AppFunctionConfiguration.Builder()
                .addEnclosingClassFactory(DispatchAppFunctions::class.java) {
                    DispatchAppFunctions(
                        cmailRepository = ep.cmailRepository(),
                        sessionRepository = ep.sessionRepository(),
                        voiceNotificationRepository = ep.voiceNotificationRepository(),
                        fileBridgeClient = ep.fileBridgeClient(),
                    )
                }
                .build()
        }

    override fun onCreate() {
        super.onCreate()

        // Plant logging trees: InMemoryLogTree (in-app viewer) + FileLogTree (SSH tail) + BigNickTimberTree (server xfil) + CrashlyticsTree (Firebase)
        Timber.plant(InMemoryLogTree.init())
        val fileLogTree = FileLogTree.init(this)
        Timber.plant(fileLogTree)
        Timber.plant(BigNickTimberTree())
        Timber.plant(CrashlyticsTree())

        val storageMode = if (fileLogTree.isUsingSharedStorage()) "SHARED" else "PRIVATE"
        Timber.i("FileLogTree: %s - %s", storageMode, fileLogTree.getLogDirectoryPath())

        Timber.i("=== Dispatch Application starting ===")

        // Debug-only: crash immediately if any MutableState is written from a
        // background thread. Catches the "Unsupported concurrent change during
        // composition" bug at the source instead of during random layout passes.
        if (BuildConfig.DEBUG) {
            ComposeStateWriteGuard.install()
        }

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

        // SSE service started from MainActivity.onCreate() — NOT here.
        // Application.onCreate() can run from background contexts (broadcast,
        // content provider) where foreground service starts are banned on
        // targetSdk 35+. BootReceiver handles the post-boot case.
        // See: ForegroundServiceStartNotAllowedException
    }

    private fun retrieveFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Timber.w(task.exception, "FCM token retrieval failed")
                    return@addOnCompleteListener
                }
                val token = task.result
                Timber.i("FCM token: %s...%s", token.take(8), token.takeLast(4))
                tokenManager.saveToken(token)
            }
    }
}

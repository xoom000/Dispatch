package dev.digitalgnosis.dispatch.logging

import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Timber Tree that forwards logs to Firebase Crashlytics.
 *
 * WARN (priority 5) and above are logged as Crashlytics log entries.
 * ERROR (priority 6) and above with a throwable are recorded as exceptions.
 *
 * Android log priority constants:
 *   VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6, ASSERT=7
 */
class CrashlyticsTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < WARN_PRIORITY) return

        val crashlytics = FirebaseCrashlytics.getInstance()
        val label = tag ?: "Dispatch"
        crashlytics.log("$label: $message")

        if (t != null && priority >= ERROR_PRIORITY) {
            crashlytics.recordException(t)
        }
    }

    companion object {
        private const val WARN_PRIORITY = 5   // android.util.Log.WARN
        private const val ERROR_PRIORITY = 6  // android.util.Log.ERROR
    }
}

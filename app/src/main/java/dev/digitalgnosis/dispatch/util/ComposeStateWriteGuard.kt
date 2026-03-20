package dev.digitalgnosis.dispatch.util

import android.os.Looper
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Debug-only guard that detects MutableState writes from non-main threads.
 *
 * Compose's snapshot system is NOT thread-safe. Writing to mutableStateOf() from
 * a background thread while recomposition is reading it causes:
 *   IllegalStateException: Unsupported concurrent change during composition
 *
 * This guard registers a global write observer that fires on EVERY MutableState
 * write. In debug builds, it checks the calling thread — if it's not main, it
 * crashes immediately with a descriptive error instead of the cryptic exception
 * surfacing randomly during layout.
 *
 * Usage: Call ComposeStateWriteGuard.install() in Application.onCreate()
 *
 * @see <a href="https://issuetracker.google.com/issues/237985810">Google Issue #237985810</a>
 */
object ComposeStateWriteGuard {

    private var installed = false

    /**
     * Install the write observer. Only effective in debug builds.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun install() {
        if (installed) return
        installed = true

        val mainLooper = Looper.getMainLooper()

        // registerGlobalWriteObserver returns a disposable handle.
        // We keep it alive for the lifetime of the app (never dispose).
        Snapshot.registerGlobalWriteObserver {
            val currentLooper = Looper.myLooper()
            if (currentLooper != mainLooper) {
                val threadName = Thread.currentThread().name
                val stackTrace = Thread.currentThread().stackTrace
                    .drop(2) // skip getStackTrace + this lambda
                    .take(15) // enough context without flooding
                    .joinToString("\n    ") { element ->
                        "${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                    }

                val message = buildString {
                    append("STATE WRITE OFF MAIN THREAD\n")
                    append("Thread: $threadName\n")
                    append("Stack:\n    $stackTrace\n\n")
                    append("Fix: Use MutableStateFlow instead of mutableStateOf() for state ")
                    append("touched by background coroutines, or wrap the write in ")
                    append("withContext(Dispatchers.Main) or Snapshot.withMutableSnapshot { }.")
                }

                // CRITICAL: Use android.util.Log, NOT Timber here.
                // Timber → InMemoryLogTree → MutableState write → triggers this observer again → infinite recursion.
                Log.e("ComposeStateWriteGuard", message)

                // Crash in debug so it's impossible to ignore.
                // The message tells you exactly which thread and where.
                throw IllegalStateException(
                    "ComposeStateWriteGuard: MutableState written from thread '$threadName'. $message"
                )
            }
        }

        Log.i("ComposeStateWriteGuard", "Installed — monitoring MutableState writes")
    }
}

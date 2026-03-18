package dev.digitalgnosis.dispatch.logging

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Custom Timber Tree that stores logs in memory for display in LogViewerScreen.
 *
 * Extends DebugTree to inherit automatic tag extraction from stack trace.
 * Uses a thread-safe circular buffer to keep the last N log entries.
 */
class InMemoryLogTree(private val maxEntries: Int = 500) : Timber.DebugTree() {

    data class LogEntry(
        val level: String,      // D, I, W, E, A
        val tag: String,
        val message: String,
        val timestamp: String,
        val timestampMillis: Long
    )

    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val listeners = mutableListOf<(List<LogEntry>) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, message, t)

        val levelChar = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            android.util.Log.ASSERT -> "A"
            else -> "?"
        }

        val now = System.currentTimeMillis()
        val timestamp = timeFormat.format(Date(now))

        val fullMessage = if (t != null) {
            "$message\n${android.util.Log.getStackTraceString(t)}"
        } else {
            message
        }

        val entry = LogEntry(
            level = levelChar,
            tag = tag ?: "Unknown",
            message = fullMessage,
            timestamp = timestamp,
            timestampMillis = now
        )

        logs.add(entry)

        while (logs.size > maxEntries) {
            logs.removeAt(0)
        }

        synchronized(listeners) {
            listeners.forEach { it(listOf(entry)) }
        }
    }

    fun getAllLogs(): List<LogEntry> = logs.toList()

    fun getFilteredLogs(level: String?): List<LogEntry> {
        return if (level == null || level == "ALL") {
            logs.toList()
        } else {
            logs.filter { it.level == level }
        }
    }

    fun clearLogs() {
        logs.clear()
    }

    fun addLogListener(listener: (List<LogEntry>) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeLogListener(listener: (List<LogEntry>) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    companion object {
        private var instance: InMemoryLogTree? = null

        fun getInstance(): InMemoryLogTree {
            return instance ?: throw IllegalStateException(
                "InMemoryLogTree not initialized. Call init() first."
            )
        }

        fun init(): InMemoryLogTree {
            return instance ?: InMemoryLogTree().also { instance = it }
        }
    }
}

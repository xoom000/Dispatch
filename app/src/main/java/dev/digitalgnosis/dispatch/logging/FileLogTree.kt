package dev.digitalgnosis.dispatch.logging

import android.content.Context
import android.os.Environment
import android.os.StatFs
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Timber Tree that writes logs to shared storage for SSH access via Termux.
 *
 * Path priority:
 * 1. Shared storage: /storage/emulated/0/Download/dispatch-logs/ (if MANAGE_EXTERNAL_STORAGE granted)
 * 2. Fallback: context.filesDir/logs/ (app-private, invisible to Termux)
 *
 * From pop-os:
 *   ssh pixel9 'tail -f ~/storage/shared/Download/dispatch-logs/dispatch-$(date +%F).log'
 */
class FileLogTree private constructor(
    private val context: Context,
    private val useSharedStorage: Boolean,
    private val maxFileSizeBytes: Long = 1 * 1024 * 1024
) : Timber.DebugTree() {

    private val logDir: File
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val writeLock = Any()
    private var writer: PrintWriter? = null
    private var currentLogFile: File? = null
    private var linesSinceFlush = 0
    private val flushInterval = 10

    private var writingDisabled = false
    private var linesSinceSpaceCheck = 0
    private val spaceCheckInterval = 100

    init {
        logDir = if (useSharedStorage) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dispatch-logs")
        } else {
            File(context.filesDir, "logs")
        }

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        cleanupOldLogs()
        openWriterForToday()
        installCrashHandler()
    }

    private fun todayLogFile(): File {
        val today = LocalDate.now().format(dateFormat)
        return File(logDir, "dispatch-$today.log")
    }

    private fun openWriterForToday() {
        synchronized(writeLock) {
            try {
                writer?.close()
                val logFile = todayLogFile()
                currentLogFile = logFile
                writer = PrintWriter(FileWriter(logFile, true), false)
            } catch (e: Exception) {
                android.util.Log.e("FileLogTree", "Failed to open log writer: ${e.message}")
            }
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (writingDisabled) {
            linesSinceSpaceCheck++
            if (linesSinceSpaceCheck >= spaceCheckInterval) {
                linesSinceSpaceCheck = 0
                if (hasEnoughSpace()) {
                    writingDisabled = false
                }
            }
            if (writingDisabled) return
        }

        val levelChar = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            android.util.Log.ASSERT -> "A"
            else -> "?"
        }

        val timestamp = timeFormat.format(Date())
        val logLine = "$timestamp $levelChar/${tag ?: "?"}: $message"

        synchronized(writeLock) {
            try {
                val expectedFile = todayLogFile()
                if (currentLogFile?.name != expectedFile.name) {
                    openWriterForToday()
                }

                writer?.println(logLine)

                if (t != null) {
                    writer?.let { w -> t.printStackTrace(w) }
                }

                linesSinceFlush++

                if (linesSinceFlush >= flushInterval || priority >= android.util.Log.ERROR) {
                    writer?.flush()
                    linesSinceFlush = 0
                }

                checkAndRotate()

                linesSinceSpaceCheck++
                if (linesSinceSpaceCheck >= spaceCheckInterval) {
                    linesSinceSpaceCheck = 0
                    if (!hasEnoughSpace()) {
                        writingDisabled = true
                        writer?.println("$timestamp W/FileLogTree: LOW STORAGE - file logging paused")
                        writer?.flush()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FileLogTree", "Failed to write log: ${e.message}")
            }
        }
    }

    private fun checkAndRotate() {
        try {
            val logFile = currentLogFile ?: return
            if (logFile.length() > maxFileSizeBytes) {
                writer?.close()

                val base = logFile.absolutePath
                val file2 = File("$base.2")
                val file1 = File("$base.1")

                if (file2.exists()) file2.delete()
                if (file1.exists()) file1.renameTo(file2)
                logFile.renameTo(file1)

                openWriterForToday()
                writer?.println("=== LOG ROTATED AT ${timeFormat.format(Date())} ===")
                writer?.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e("FileLogTree", "Failed to rotate logs: ${e.message}")
        }
    }

    private fun cleanupOldLogs() {
        try {
            val cutoff = LocalDate.now().minusDays(2)
            val datePattern = Regex("""dispatch-(\d{4}-\d{2}-\d{2})\.log.*""")

            logDir.listFiles()?.forEach { file ->
                datePattern.matchEntire(file.name)?.let { match ->
                    val fileDate = LocalDate.parse(match.groupValues[1])
                    if (fileDate.isBefore(cutoff)) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileLogTree", "Failed to cleanup old logs: ${e.message}")
        }
    }

    private fun hasEnoughSpace(): Boolean {
        return try {
            val stat = StatFs(logDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes > 10 * 1024 * 1024
        } catch (e: Exception) {
            true
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            synchronized(writeLock) {
                try {
                    val timestamp = timeFormat.format(Date())
                    val stackTrace = android.util.Log.getStackTraceString(throwable)

                    writer?.println("$timestamp A/CRASH: ============================================================")
                    writer?.println("$timestamp A/CRASH: !!! APPLICATION CRASH !!!")
                    writer?.println("$timestamp A/CRASH: Thread: ${thread.name}")
                    writer?.println("$timestamp A/CRASH: Exception: ${throwable.javaClass.simpleName}")
                    writer?.println("$timestamp A/CRASH: Message: ${throwable.message}")
                    writer?.println("$timestamp A/CRASH: ============================================================")

                    stackTrace.lines().forEach { line ->
                        writer?.println("$timestamp A/CRASH: $line")
                    }

                    writer?.println("$timestamp A/CRASH: ============================================================")
                    writer?.flush()
                    writer?.close()

                    writePendingCrashFile(timestamp, thread.name, throwable, stackTrace)
                } catch (e: Exception) {
                    android.util.Log.e("FileLogTree", "Failed to log crash: ${e.message}")
                }
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writePendingCrashFile(
        timestamp: String,
        threadName: String,
        throwable: Throwable,
        stackTrace: String
    ) {
        try {
            val crashFile = File(logDir, PENDING_CRASH_FILENAME)

            fun escape(s: String): String = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

            val breadcrumbs = try {
                InMemoryLogTree.getInstance().getAllLogs().takeLast(50)
            } catch (_: Exception) {
                emptyList()
            }

            val json = buildString {
                appendLine("{")
                appendLine("  \"timestamp\": \"${escape(timestamp)}\",")
                appendLine("  \"device\": \"${escape("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")}\",")
                appendLine("  \"androidApi\": ${android.os.Build.VERSION.SDK_INT},")
                appendLine("  \"app\": \"dispatch\",")
                appendLine("  \"thread\": \"${escape(threadName)}\",")
                appendLine("  \"exception\": \"${escape(throwable.javaClass.simpleName)}\",")
                appendLine("  \"message\": \"${escape(throwable.message ?: "null")}\",")
                appendLine("  \"stackTrace\": \"${escape(stackTrace)}\",")
                append("  \"breadcrumbs\": [")
                breadcrumbs.forEachIndexed { index, entry ->
                    if (index > 0) append(",")
                    val msg = if (entry.message.length > 500) {
                        escape(entry.message.take(500) + "...")
                    } else {
                        escape(entry.message)
                    }
                    append("{\"timestamp\":\"${escape(entry.timestamp)}\",\"level\":\"${escape(entry.level)}\",\"tag\":\"${escape(entry.tag)}\",\"message\":\"$msg\"}")
                }
                appendLine("]")
                appendLine("}")
            }

            crashFile.writeText(json)
        } catch (e: Exception) {
            android.util.Log.e("FileLogTree", "Failed to write pending_crash.json: ${e.message}")
        }
    }

    fun flush() {
        synchronized(writeLock) {
            writer?.flush()
        }
    }

    fun getPreviousSessionLogs(): List<InMemoryLogTree.LogEntry> {
        val entries = mutableListOf<InMemoryLogTree.LogEntry>()
        val linePattern = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEA])/(.+?): (.*)$""")

        try {
            val yesterday = LocalDate.now().minusDays(1).format(dateFormat)
            val today = LocalDate.now().format(dateFormat)

            val filesToCheck = listOf(
                File(logDir, "dispatch-$yesterday.log"),
                File(logDir, "dispatch-$yesterday.log.1"),
                File(logDir, "dispatch-$today.log.1"),
                File(logDir, "dispatch-$today.log.2"),
            )

            filesToCheck.filter { it.exists() }.forEach { file ->
                file.readLines().forEach { line ->
                    linePattern.matchEntire(line)?.let { match ->
                        val (timestamp, level, tag, message) = match.destructured
                        entries.add(InMemoryLogTree.LogEntry(
                            level = level,
                            tag = tag,
                            message = message,
                            timestamp = timestamp.substringAfter(" "),
                            timestampMillis = 0
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FileLogTree", "Failed to read previous logs: ${e.message}")
        }

        return entries
    }

    fun hasCrashLogs(): Boolean {
        return try {
            val yesterday = LocalDate.now().minusDays(1).format(dateFormat)
            val today = LocalDate.now().format(dateFormat)

            listOf(
                File(logDir, "dispatch-$yesterday.log"),
                File(logDir, "dispatch-$today.log.1"),
            ).any { file ->
                file.exists() && file.readText().contains("APPLICATION CRASH")
            }
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllLogs() {
        synchronized(writeLock) {
            try {
                writer?.close()
                logDir.listFiles()?.forEach { it.delete() }
                openWriterForToday()
            } catch (e: Exception) {
                android.util.Log.e("FileLogTree", "Failed to clear logs: ${e.message}")
            }
        }
    }

    fun getLogDirectoryPath(): String = logDir.absolutePath

    fun isUsingSharedStorage(): Boolean = useSharedStorage

    fun getPendingCrashFile(): File? {
        val file = File(logDir, PENDING_CRASH_FILENAME)
        return if (file.exists() && file.length() > 0) file else null
    }

    companion object {
        private var instance: FileLogTree? = null
        const val PENDING_CRASH_FILENAME = "pending_crash.json"

        fun getInstance(): FileLogTree {
            return instance ?: throw IllegalStateException(
                "FileLogTree not initialized. Call init(context) first."
            )
        }

        fun init(context: Context, maxFileSizeBytes: Long = 1 * 1024 * 1024): FileLogTree {
            return instance ?: run {
                val useShared = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    true
                }

                FileLogTree(context.applicationContext, useShared, maxFileSizeBytes).also {
                    instance = it
                    val storageType = if (useShared) "SHARED (SSH accessible)" else "PRIVATE (app sandbox)"
                    android.util.Log.i("FileLogTree", "Initialized - $storageType - ${it.logDir.absolutePath}")
                }
            }
        }

        fun isInitialized(): Boolean = instance != null
    }
}

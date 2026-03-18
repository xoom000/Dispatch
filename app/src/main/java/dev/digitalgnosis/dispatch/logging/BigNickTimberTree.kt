package dev.digitalgnosis.dispatch.logging

import android.os.Build
import dev.digitalgnosis.dispatch.BuildConfig
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Timber Tree that sends ERROR/ASSERT events immediately to Route 33 server with breadcrumbs.
 *
 * Behavior:
 * - VERBOSE/DEBUG/INFO/WARN -> skip (handled by InMemoryLogTree + FileLogTree)
 * - ERROR/ASSERT -> immediately POST to Route 33 with last 50 breadcrumbs
 *
 * Rate limiting: 5-second gap between sends, max 10 per minute.
 * Fire-and-forget: if server unreachable, errors are still in FileLogTree.
 */
class BigNickTimberTree : Timber.Tree() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val lastSendTime = AtomicLong(0)
    private val eventCountThisMinute = AtomicInteger(0)
    private val minuteStart = AtomicLong(0)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < android.util.Log.ERROR) return
        if (!checkRateLimit()) return

        scope.launch {
            try {
                sendErrorEvent(priority, tag, message, t)
            } catch (_: Exception) {
                // Silent failure — FileLogTree has it
            }
        }
    }

    private fun checkRateLimit(): Boolean {
        val now = System.currentTimeMillis()

        val lastSend = lastSendTime.get()
        if (now - lastSend < MIN_SEND_INTERVAL_MS) return false

        val currentMinuteStart = minuteStart.get()
        if (now - currentMinuteStart > 60_000) {
            minuteStart.set(now)
            eventCountThisMinute.set(0)
        }
        if (eventCountThisMinute.getAndIncrement() >= MAX_EVENTS_PER_MINUTE) return false

        lastSendTime.set(now)
        return true
    }

    private fun sendErrorEvent(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = timestampFormat.format(Date())
        val level = if (priority == android.util.Log.ASSERT) "A" else "E"
        val exceptionClass = t?.javaClass?.simpleName
        val stackTrace = t?.let { android.util.Log.getStackTraceString(it) }

        val breadcrumbs = try {
            InMemoryLogTree.getInstance().getAllLogs().takeLast(MAX_BREADCRUMBS)
        } catch (e: Exception) {
            emptyList()
        }

        val jsonPayload = buildJsonPayload(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message,
            exceptionClass = exceptionClass,
            stackTrace = stackTrace,
            breadcrumbs = breadcrumbs
        )

        val url = URL("${TailscaleConfig.ROUTE33_SERVER}$ENDPOINT")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        try {
            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream)).use { it.readText() }
                } catch (_: Exception) { "no body" }
                Timber.d("BigNickTimberTree: Server returned HTTP $responseCode: $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildJsonPayload(
        timestamp: String,
        level: String,
        tag: String?,
        message: String,
        exceptionClass: String?,
        stackTrace: String?,
        breadcrumbs: List<InMemoryLogTree.LogEntry>
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"deviceId\":${escapeJson(DEVICE_ID)},")
        sb.append("\"versionCode\":${BuildConfig.VERSION_CODE},")
        sb.append("\"versionName\":${escapeJson(BuildConfig.VERSION_NAME)},")
        sb.append("\"flavor\":${escapeJson("dispatch")},")
        sb.append("\"timestamp\":${escapeJson(timestamp)},")
        sb.append("\"level\":${escapeJson(level)},")

        if (tag != null) {
            sb.append("\"tag\":${escapeJson(tag)},")
        }
        sb.append("\"message\":${escapeJson(message)}")

        if (exceptionClass != null) {
            sb.append(",\"exception\":${escapeJson(exceptionClass)}")
        }
        if (stackTrace != null) {
            sb.append(",\"stackTrace\":${escapeJson(stackTrace)}")
        }

        if (breadcrumbs.isNotEmpty()) {
            sb.append(",\"breadcrumbs\":[")
            breadcrumbs.forEachIndexed { index, entry ->
                if (index > 0) sb.append(",")
                val truncatedMessage = if (entry.message.length > MAX_BREADCRUMB_MESSAGE_LENGTH) {
                    entry.message.take(MAX_BREADCRUMB_MESSAGE_LENGTH) + "..."
                } else {
                    entry.message
                }
                sb.append("{")
                sb.append("\"timestamp\":${escapeJson(entry.timestamp)},")
                sb.append("\"level\":${escapeJson(entry.level)},")
                sb.append("\"tag\":${escapeJson(entry.tag)},")
                sb.append("\"message\":${escapeJson(truncatedMessage)}")
                sb.append("}")
            }
            sb.append("]")
        }

        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    companion object {
        private const val ENDPOINT = "/api/diagnostics/error-event"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 10_000
        private const val MIN_SEND_INTERVAL_MS = 5_000L
        private const val MAX_EVENTS_PER_MINUTE = 10
        private const val MAX_BREADCRUMBS = 50
        private const val MAX_BREADCRUMB_MESSAGE_LENGTH = 500

        val DEVICE_ID: String = "${Build.MODEL}-dispatch"
    }
}

package dev.digitalgnosis.dispatch.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import dagger.hilt.android.AndroidEntryPoint
import dev.digitalgnosis.dispatch.R
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.data.ChatBubbleRepository
import dev.digitalgnosis.dispatch.data.MessageChunk
import dev.digitalgnosis.dispatch.data.MessageRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

// ── SSE event payload models ─────────────────────────────────────────────────

@Serializable
private data class SseEnvelope(
    @SerialName("event_type") val eventType: String = "",
    @SerialName("thread_id") val threadId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val payload: JsonObject? = null,
)

@Serializable
private data class ChatBubblePayload(
    val type: String = "agent",
    val text: String = "",
    val detail: String = "",
    val sequence: Int = 0,
    @SerialName("sub_seq") val subSeq: Int = 0,
    val timestamp: String = "",
)

@Serializable
private data class ChunkPayload(
    val text: String = "",
)

/**
 * Foreground service that owns the SSE connection to File Bridge.
 *
 * This is the Google Messages DittoForegroundService pattern:
 * - Silent notification (MIN priority) keeps the process alive
 * - START_STICKY restarts the service if Android kills it
 * - SSE connection persists through app backgrounding
 * - WakeLock held only during reconnection attempts
 *
 * Replaces the lifecycle-aware EventStreamClient SSE connection.
 * The connection now survives background/foreground transitions.
 */
@AndroidEntryPoint
class SseConnectionService : Service() {

    @Inject lateinit var chatBubbleRepository: ChatBubbleRepository
    @Inject lateinit var messageRepository: MessageRepository

    private var eventSource: EventSource? = null
    private var lastEventId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)  // 20s — Tailscale cold-start after Doze can take 10-15s
        .readTimeout(0, TimeUnit.SECONDS)  // SSE: no read timeout
        .retryOnConnectionFailure(true)
        .build()

    // Reconnect state
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connected"))
        Timber.i("SseConnectionService: created, foreground started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connect()
        return START_STICKY  // Android will restart this service if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("SseConnectionService: destroying")
        reconnectJob?.cancel()
        scope.cancel()
        disconnect()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── SSE Connection ──────────────────────────────────────────────

    private fun connect() {
        if (eventSource != null) return

        val url = "${TailscaleConfig.FILE_BRIDGE_SERVER}/events/stream"
        val requestBuilder = Request.Builder().url(url)
        lastEventId?.let { requestBuilder.header("Last-Event-ID", it) }

        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(requestBuilder.build(), SseListener())
        Timber.i("SseConnectionService: connecting to %s", url)
    }

    private fun disconnect() {
        eventSource?.cancel()
        eventSource = null
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Exponential backoff: 2s, 4s, 8s, 16s, max 30s
            val delayMs = minOf(2000L * (1L shl minOf(reconnectAttempt, 4)), 30_000L)
            reconnectAttempt++
            Timber.i("SseConnectionService: reconnecting in %dms (attempt %d)", delayMs, reconnectAttempt)

            // Hold WakeLock during reconnection to prevent CPU sleep
            acquireWakeLock(delayMs + 5_000)
            delay(delayMs)

            disconnect()
            connect()
        }
    }

    // ── SSE Event Handler ───────────────────────────────────────────

    private inner class SseListener : EventSourceListener() {

        override fun onOpen(eventSource: EventSource, response: Response) {
            reconnectAttempt = 0
            releaseWakeLock()
            updateNotification("Connected")
            Timber.i("SseConnectionService: SSE connected (status=%d)", response.code)

            // Notify the signal hub
            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            if (id != null) lastEventId = id

            try {
                val envelope = json.decodeFromString<SseEnvelope>(data)
                val eventType = type?.takeIf { it.isNotBlank() } ?: envelope.eventType

                when (eventType) {
                    // ── Real-time chat bubbles ──
                    "chat_bubble" -> {
                        val sessionId = envelope.sessionId
                        val payload = envelope.payload
                        if (sessionId.isNotBlank() && payload != null) {
                            val p = json.decodeFromJsonElement<ChatBubblePayload>(payload)
                            val bubble = ChatBubble(
                                type = p.type,
                                text = p.text,
                                detail = p.detail,
                                sequence = p.sequence,
                                subSeq = p.subSeq,
                                timestamp = p.timestamp,
                            )
                            scope.launch {
                                chatBubbleRepository.insertFromSse(sessionId, bubble)
                            }
                        }
                    }

                    // ── Legacy chunk streaming ──
                    "agent_message_chunk", "agent_thought_chunk" -> {
                        val threadId = envelope.threadId
                        val payload = envelope.payload
                        if (threadId.isNotBlank() && payload != null) {
                            val text = json.decodeFromJsonElement<ChunkPayload>(payload).text
                            if (text.isNotEmpty()) {
                                messageRepository.emitLiveChunk(
                                    MessageChunk(threadId = threadId, text = text, type = eventType)
                                )
                            }
                        }
                    }

                    // ── Thread refresh signals ──
                    "dispatch_message", "cmail_message", "cmail_reply" -> {
                        val threadId = envelope.threadId
                        if (threadId.isNotBlank()) {
                            messageRepository.emitThreadRefresh(threadId)
                        }
                    }

                    // ── UI refresh signals ──
                    "whiteboard_update" -> {
                        _whiteboardRefresh.value = _whiteboardCounter.incrementAndGet()
                    }

                    "tool_used", "tool_failed", "session_ended" -> {
                        _eventFeedRefresh.value = _eventFeedCounter.incrementAndGet()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "SseConnectionService: failed to parse event")
            }
        }

        override fun onClosed(eventSource: EventSource) {
            this@SseConnectionService.eventSource = null
            _connectionState.value = ConnectionState.DISCONNECTED
            updateNotification("Reconnecting...")
            scheduleReconnect()
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
        ) {
            this@SseConnectionService.eventSource = null
            _connectionState.value = ConnectionState.DISCONNECTED
            updateNotification("Reconnecting...")
            Timber.w(t, "SseConnectionService: SSE failure (response=%s)", response?.code)
            scheduleReconnect()
        }
    }

    // ── Notification ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection",
            NotificationManager.IMPORTANCE_MIN,  // Silent — no sound, no vibrate, no peek
        ).apply {
            description = "Persistent connection to DG infrastructure"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DG Messages")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    // ── WakeLock ────────────────────────────────────────────────────

    private fun acquireWakeLock(timeoutMs: Long) {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DGMessages:SseReconnect"
            ).apply {
                acquire(timeoutMs)
            }
        } catch (e: SecurityException) {
            Timber.w(e, "SseConnectionService: could not acquire WAKE_LOCK — continuing without it")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ── Static signal hub ───────────────────────────────────────────
    // ViewModels observe these without needing a reference to the service.

    enum class ConnectionState { CONNECTED, DISCONNECTED, UNKNOWN }

    companion object {
        private const val CHANNEL_ID = "sse_connection"
        private const val NOTIFICATION_ID = 9001

        private val _connectionState = MutableStateFlow(ConnectionState.UNKNOWN)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _eventFeedCounter = AtomicInteger(0)
        private val _eventFeedRefresh = MutableStateFlow(0)
        val eventFeedRefresh: StateFlow<Int> = _eventFeedRefresh.asStateFlow()

        private val _whiteboardCounter = AtomicInteger(0)
        private val _whiteboardRefresh = MutableStateFlow(0)
        val whiteboardRefresh: StateFlow<Int> = _whiteboardRefresh.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, SseConnectionService::class.java)
            context.startForegroundService(intent)
        }
    }
}

package dev.digitalgnosis.dispatch.network

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import dev.digitalgnosis.dispatch.data.MessageChunk
import dev.digitalgnosis.dispatch.data.MessageRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lifecycle-aware SSE client for the File Bridge event stream.
 */
@Singleton
class EventStreamClient @Inject constructor(
    private val messageRepository: MessageRepository,
) : DefaultLifecycleObserver {

    private var eventSource: EventSource? = null
    private var lastEventId: String? = null

    @Volatile
    private var active = false

    private val _eventFeedRefresh = MutableStateFlow(0)
    val eventFeedRefresh: StateFlow<Int> = _eventFeedRefresh.asStateFlow()
    private val refreshCounter = AtomicInteger(0)

    private val _whiteboardRefresh = MutableStateFlow(0)
    val whiteboardRefresh: StateFlow<Int> = _whiteboardRefresh.asStateFlow()
    private val boardRefreshCounter = AtomicInteger(0)

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        if (active && eventSource == null) connect()
    }

    @Volatile
    private var eventFeedDirty = false
    private val eventFeedDebounceRunnable = Runnable {
        if (eventFeedDirty) {
            eventFeedDirty = false
            _eventFeedRefresh.value = refreshCounter.incrementAndGet()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Timber.i("EventStreamClient: registered lifecycle observer")
    }

    override fun onStart(owner: LifecycleOwner) {
        active = true
        connect()
    }

    override fun onStop(owner: LifecycleOwner) {
        active = false
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(eventFeedDebounceRunnable)
        eventFeedDirty = false
        disconnect()
    }

    private fun connect() {
        if (eventSource != null) return

        val url = "${TailscaleConfig.FILE_BRIDGE_SERVER}/events/stream"
        val requestBuilder = Request.Builder().url(url)
        lastEventId?.let { requestBuilder.header("Last-Event-ID", it) }
        val request = requestBuilder.build()

        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(request, SseListener())
        Timber.i("EventStreamClient: connecting to %s", url)
    }

    private fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        Timber.i("EventStreamClient: disconnected")
    }

    private fun scheduleReconnect() {
        if (!active) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private inner class SseListener : EventSourceListener() {

        override fun onOpen(eventSource: EventSource, response: Response) {
            Timber.i("EventStreamClient: SSE connected (status=%d)", response.code)
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            if (id != null) lastEventId = id

            try {
                val json = JSONObject(data)
                val eventType = type ?: json.optString("event_type", "")
                val threadId = json.optString("thread_id", "")
                val payload = json.optJSONObject("payload")

                when (eventType) {
                    "agent_message_chunk", "agent_thought_chunk" -> {
                        val text = payload?.optString("text", "") ?: ""
                        if (threadId.isNotBlank() && text.isNotEmpty()) {
                            messageRepository.emitLiveChunk(MessageChunk(
                                threadId = threadId,
                                text = text,
                                type = eventType
                            ))
                        }
                    }

                    "dispatch_message", "cmail_message", "cmail_reply" -> {
                        if (threadId.isNotBlank()) {
                            messageRepository.emitThreadRefresh(threadId)
                        }
                    }

                    "whiteboard_update" -> {
                        _whiteboardRefresh.value = boardRefreshCounter.incrementAndGet()
                    }

                    "tool_used", "tool_failed", "session_ended" -> {
                        eventFeedDirty = true
                        handler.removeCallbacks(eventFeedDebounceRunnable)
                        handler.postDelayed(eventFeedDebounceRunnable, EVENT_FEED_DEBOUNCE_MS)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "EventStreamClient: failed to parse event data")
            }
        }

        override fun onClosed(eventSource: EventSource) {
            this@EventStreamClient.eventSource = null
            scheduleReconnect()
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
        ) {
            this@EventStreamClient.eventSource = null
            scheduleReconnect()
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val EVENT_FEED_DEBOUNCE_MS = 3_000L
    }
}

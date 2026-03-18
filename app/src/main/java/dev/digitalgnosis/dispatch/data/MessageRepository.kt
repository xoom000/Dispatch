package dev.digitalgnosis.dispatch.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live chunk model for real-time streaming.
 */
data class MessageChunk(
    val threadId: String,
    val text: String,
    val type: String, // agent_message_chunk or agent_thought_chunk
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central message store backed by Room for persistence.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableStateFlow<List<DispatchMessage>>(emptyList())
    val messages: StateFlow<List<DispatchMessage>> = _messages.asStateFlow()

    private val _liveChunks = MutableSharedFlow<MessageChunk>(replay = 0, extraBufferCapacity = 64)
    val liveChunks: SharedFlow<MessageChunk> = _liveChunks.asSharedFlow()

    private val _messageCursor = AtomicInteger(0)

    private val _threadRefreshEvents = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val threadRefreshEvents: SharedFlow<String> = _threadRefreshEvents.asSharedFlow()

    init {
        scope.launch {
            try {
                val entities = messageDao.getAll()
                val messages = entities.map { it.toDomainModel() }
                _messages.value = messages
                Timber.i("MessageRepository: loaded %d messages from Room", messages.size)
            } catch (e: Exception) {
                Timber.e(e, "MessageRepository: failed to load from Room")
            }
        }
    }

    fun emitLiveChunk(chunk: MessageChunk) {
        _liveChunks.tryEmit(chunk)
    }

    fun addMessage(message: DispatchMessage) {
        val updated = listOf(message) + _messages.value
        _messages.value = updated.take(MAX_MESSAGES)

        scope.launch {
            try {
                messageDao.insert(message.toEntity())
                messageDao.trimToCount(MAX_MESSAGES)
            } catch (e: Exception) {
                Timber.e(e, "MessageRepository: failed to persist message from %s", message.sender)
            }
        }

        if (!message.isOutgoing) {
            resetCursor()
        }

        if (!message.isOutgoing && !message.threadId.isNullOrBlank()) {
            _threadRefreshEvents.tryEmit(message.threadId)
        }
    }

    fun emitThreadRefresh(threadId: String) {
        _threadRefreshEvents.tryEmit(threadId)
    }

    fun getMessages(): List<DispatchMessage> = _messages.value

    // ── Earbud Navigation Cursor ────────────────────────

    fun getIncomingMessages(): List<DispatchMessage> =
        _messages.value.filter { !it.isOutgoing }

    val messageCursorIndex: Int get() = _messageCursor.get()

    fun getMessageAtCursor(): DispatchMessage? {
        val incoming = getIncomingMessages()
        val idx = _messageCursor.get()
        return incoming.getOrNull(idx)
    }

    fun advanceCursor(): DispatchMessage? {
        val incoming = getIncomingMessages()
        val newIdx = _messageCursor.get() + 1
        if (newIdx >= incoming.size) return null
        _messageCursor.set(newIdx)
        return incoming[newIdx]
    }

    fun retreatCursor(): DispatchMessage? {
        val incoming = getIncomingMessages()
        val newIdx = _messageCursor.get() - 1
        if (newIdx < 0) return null
        _messageCursor.set(newIdx)
        return incoming[newIdx]
    }

    fun resetCursor() {
        _messageCursor.set(0)
    }

    fun clearMessages() {
        _messages.value = emptyList()
        scope.launch {
            try {
                messageDao.deleteAll()
                Timber.i("MessageRepository: cleared all messages from Room")
            } catch (e: Exception) {
                Timber.e(e, "MessageRepository: failed to clear Room")
            }
        }
    }

    companion object {
        private const val MAX_MESSAGES = 100
    }
}

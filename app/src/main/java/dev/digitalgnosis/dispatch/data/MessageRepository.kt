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
 * Repository for incoming FCM voice notifications.
 *
 * Owns the in-memory message list and the earbud navigation cursor.
 * Backed by Room for persistence across app restarts.
 */
@Singleton
class VoiceNotificationRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _notifications = MutableStateFlow<List<VoiceNotification>>(emptyList())
    val notifications: StateFlow<List<VoiceNotification>> = _notifications.asStateFlow()

    private val _cursor = AtomicInteger(0)

    init {
        scope.launch {
            try {
                val entities = messageDao.getAll()
                val incoming = entities
                    .filter { !it.isOutgoing }
                    .map { it.toVoiceNotification() }
                _notifications.value = incoming
                Timber.i("VoiceNotificationRepository: loaded %d notifications from Room", incoming.size)
            } catch (e: Exception) {
                Timber.e(e, "VoiceNotificationRepository: failed to load from Room")
            }
        }
    }

    fun add(notification: VoiceNotification) {
        val updated = listOf(notification) + _notifications.value
        _notifications.value = updated.take(MAX_MESSAGES)
        _cursor.set(0)

        scope.launch {
            try {
                messageDao.insert(notification.toEntity())
                messageDao.trimToCount(MAX_MESSAGES)
            } catch (e: Exception) {
                Timber.e(e, "VoiceNotificationRepository: failed to persist from %s", notification.sender)
            }
        }
    }

    fun getAtCursor(): VoiceNotification? {
        val list = _notifications.value
        val idx = _cursor.get()
        return list.getOrNull(idx)
    }

    val cursorIndex: Int get() = _cursor.get()

    fun advanceCursor(): VoiceNotification? {
        val list = _notifications.value
        val newIdx = _cursor.get() + 1
        if (newIdx >= list.size) return null
        _cursor.set(newIdx)
        return list[newIdx]
    }

    fun retreatCursor(): VoiceNotification? {
        val list = _notifications.value
        val newIdx = _cursor.get() - 1
        if (newIdx < 0) return null
        _cursor.set(newIdx)
        return list[newIdx]
    }

    fun resetCursor() {
        _cursor.set(0)
    }

    fun clear() {
        _notifications.value = emptyList()
        scope.launch {
            try {
                messageDao.deleteAll()
            } catch (e: Exception) {
                Timber.e(e, "VoiceNotificationRepository: failed to clear Room")
            }
        }
    }

    companion object {
        private const val MAX_MESSAGES = 100
    }
}

/**
 * Repository for outgoing cmail messages sent by Nigel.
 *
 * Keeps a recent outbox in memory for the UI. Backed by Room.
 */
@Singleton
class CmailOutboxRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _outbox = MutableStateFlow<List<CmailOutboxItem>>(emptyList())
    val outbox: StateFlow<List<CmailOutboxItem>> = _outbox.asStateFlow()

    init {
        scope.launch {
            try {
                val entities = messageDao.getAll()
                val outgoing = entities
                    .filter { it.isOutgoing }
                    .map { it.toCmailOutboxItem() }
                _outbox.value = outgoing
                Timber.i("CmailOutboxRepository: loaded %d outbox items from Room", outgoing.size)
            } catch (e: Exception) {
                Timber.e(e, "CmailOutboxRepository: failed to load from Room")
            }
        }
    }

    fun add(item: CmailOutboxItem) {
        val updated = listOf(item) + _outbox.value
        _outbox.value = updated.take(MAX_OUTBOX)

        scope.launch {
            try {
                messageDao.insert(item.toEntity())
                messageDao.trimToCount(MAX_OUTBOX)
            } catch (e: Exception) {
                Timber.e(e, "CmailOutboxRepository: failed to persist send to %s", item.department)
            }
        }
    }

    companion object {
        private const val MAX_OUTBOX = 100
    }
}

/**
 * Central message store — kept for streaming/live chunk relay and thread refresh signals.
 *
 * Voice notifications live in VoiceNotificationRepository.
 * Outgoing cmail items live in CmailOutboxRepository.
 * This class owns only the cross-cutting signals that don't belong to either domain.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _liveChunks = MutableSharedFlow<MessageChunk>(replay = 0, extraBufferCapacity = 64)
    val liveChunks: SharedFlow<MessageChunk> = _liveChunks.asSharedFlow()

    private val _threadRefreshEvents = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val threadRefreshEvents: SharedFlow<String> = _threadRefreshEvents.asSharedFlow()

    fun emitLiveChunk(chunk: MessageChunk) {
        _liveChunks.tryEmit(chunk)
    }

    fun emitThreadRefresh(threadId: String) {
        _threadRefreshEvents.tryEmit(threadId)
    }
}

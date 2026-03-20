package dev.digitalgnosis.dispatch.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for chat bubbles — the single source of truth for conversation data.
 *
 * Architecture:
 *   File Bridge (GET /sessions/{id}/chat) → parse to bubbles → Room DAO
 *   Room DAO (Flow) → Repository.observeBubbles() → ViewModel → Compose
 *
 * Data source is File Bridge. Room is the local cache.
 * The ViewModel only talks to this repository, never to the DAO directly.
 */
@Singleton
class ChatBubbleRepository @Inject constructor(
    private val chatBubbleDao: ChatBubbleDao,
    private val sessionRepository: SessionRepository,
) {

    /**
     * Observe bubbles for a session as a reactive Flow.
     * Maps from Room entities to domain ChatBubble objects.
     *
     * Room's InvalidationTracker fires automatically when
     * chat_bubbles table changes — no manual notification needed.
     */
    fun observeBubbles(sessionId: String): Flow<List<ChatBubble>> =
        chatBubbleDao.observeBubbles(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Initial load — fetch tail from File Bridge and populate Room.
     * Called once when the conversation screen opens.
     *
     * Returns metadata (hasEarlier, minSequence, maxSequence) for the ViewModel.
     */
    suspend fun loadTail(sessionId: String, limit: Int = 100): LoadResult {
        return try {
            val result = sessionRepository.fetchChatBubbles(
                sessionId = sessionId,
                limit = limit,
                tail = true,
            )

            if (result.bubbles.isNotEmpty()) {
                val entities = result.bubbles.map { it.toEntity(sessionId) }
                chatBubbleDao.upsertAll(entities)
            }

            LoadResult(
                count = result.bubbles.size,
                minSequence = result.minSequence,
                maxSequence = result.maxSequence,
                hasEarlier = result.hasEarlier,
            )
        } catch (e: Exception) {
            Timber.e(e, "ChatBubbleRepo: tail load failed for %s", sessionId.take(8))
            LoadResult(0, 0, 0, false)
        }
    }

    /**
     * Scroll-up pagination — load older bubbles via before_sequence param.
     * Prepends to Room; Flow notifies collectors automatically.
     */
    suspend fun loadBefore(sessionId: String, beforeSequence: Int, limit: Int = 50): LoadResult {
        return try {
            val result = sessionRepository.fetchChatBubbles(
                sessionId = sessionId,
                limit = limit,
                tail = false,
                beforeSequence = beforeSequence,
            )

            if (result.bubbles.isNotEmpty()) {
                val entities = result.bubbles.map { it.toEntity(sessionId) }
                chatBubbleDao.upsertAll(entities)
            }

            LoadResult(
                count = result.bubbles.size,
                minSequence = result.minSequence,
                maxSequence = result.maxSequence,
                hasEarlier = result.hasEarlier,
            )
        } catch (e: Exception) {
            Timber.e(e, "ChatBubbleRepo: loadBefore failed for %s", sessionId.take(8))
            LoadResult(0, 0, 0, false)
        }
    }

    /**
     * Insert a single bubble from an SSE chat_bubble event.
     * Room's InvalidationTracker notifies all Flow collectors automatically.
     */
    suspend fun insertFromSse(sessionId: String, bubble: ChatBubble) {
        try {
            chatBubbleDao.upsert(bubble.toEntity(sessionId))
            Timber.v(
                "ChatBubbleRepo: SSE bubble inserted seq=%d type=%s for %s",
                bubble.sequence, bubble.type, sessionId.take(8),
            )
        } catch (e: Exception) {
            Timber.w(e, "ChatBubbleRepo: SSE insert failed for %s", sessionId.take(8))
        }
    }

    /**
     * Clear and reload a session (used on manual refresh).
     */
    suspend fun refreshSession(sessionId: String, limit: Int = 100): LoadResult {
        chatBubbleDao.clearSession(sessionId)
        return loadTail(sessionId, limit)
    }

    /**
     * Get the current max sequence for a session.
     */
    suspend fun getMaxSequence(sessionId: String): Int =
        chatBubbleDao.getMaxSequence(sessionId) ?: 0

    /**
     * Get the current min sequence for a session.
     */
    suspend fun getMinSequence(sessionId: String): Int =
        chatBubbleDao.getMinSequence(sessionId) ?: 0

    /**
     * Result of a load operation.
     */
    data class LoadResult(
        val count: Int,
        val minSequence: Int,
        val maxSequence: Int,
        val hasEarlier: Boolean,
    )
}

// ── Mapping extensions ──────────────────────────────────────────────

private fun ChatBubbleEntity.toDomain() = ChatBubble(
    type = type,
    text = text,
    detail = detail,
    sequence = sequence,
    subSeq = subSeq,
    timestamp = timestamp,
)

private fun ChatBubble.toEntity(sessionId: String) = ChatBubbleEntity(
    sessionId = sessionId,
    sequence = sequence,
    subSeq = subSeq,
    type = type,
    text = text,
    detail = detail,
    timestamp = timestamp,
)

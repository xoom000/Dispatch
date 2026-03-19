package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.SessionsApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for chat bubbles — the single source of truth for conversation data.
 *
 * Architecture:
 *   Sessions API (GET /v1/sessions/{id}/events) → parse to bubbles → Room DAO
 *   Room DAO (Flow) → Repository.observeBubbles() → ViewModel → Compose
 *
 * Data source is the Anthropic Sessions API. Room is the local cache.
 * The ViewModel only talks to this repository. Never to the DAO directly.
 */
@Singleton
class ChatBubbleRepository @Inject constructor(
    private val chatBubbleDao: ChatBubbleDao,
    private val sessionsApiClient: SessionsApiClient,
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
     * Initial load — fetch events from Sessions API and populate Room.
     * Called once when the conversation screen opens.
     *
     * Returns metadata (hasEarlier, minSequence, maxSequence) for the ViewModel.
     */
    suspend fun loadTail(sessionId: String, limit: Int = 100): LoadResult {
        return try {
            val bubbles = sessionsApiClient.fetchBubbles(sessionId)

            if (bubbles.isNotEmpty()) {
                val entities = bubbles.map { it.toEntity(sessionId) }
                chatBubbleDao.upsertAll(entities)
            }

            val maxSeq = bubbles.maxOfOrNull { it.sequence } ?: 0
            val minSeq = bubbles.minOfOrNull { it.sequence } ?: 0

            LoadResult(
                count = bubbles.size,
                minSequence = minSeq,
                maxSequence = maxSeq,
                hasEarlier = false, // Sessions API returns all events, no pagination needed
            )
        } catch (e: Exception) {
            Timber.e(e, "ChatBubbleRepo: tail load failed for %s", sessionId.take(8))
            LoadResult(0, 0, 0, false)
        }
    }

    /**
     * Scroll-up — not needed for Sessions API (returns all events).
     * Kept for interface compatibility.
     */
    suspend fun loadBefore(sessionId: String, beforeSequence: Int, limit: Int = 50): LoadResult {
        return LoadResult(0, 0, 0, false)
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

private fun SessionsApiClient.ChatBubbleData.toEntity(sessionId: String) = ChatBubbleEntity(
    sessionId = sessionId,
    sequence = sequence,
    subSeq = subSeq,
    type = type,
    text = text,
    detail = detail,
    timestamp = timestamp,
)

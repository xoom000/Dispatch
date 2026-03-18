package dev.digitalgnosis.dispatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for chat bubbles — reactive queries via Flow.
 *
 * Room's InvalidationTracker automatically notifies Flow collectors
 * when the underlying table changes. This is the same pattern Google
 * Messages uses (ContentObserver → callbackFlow), but Room gives it
 * to us for free.
 *
 * The ViewModel NEVER calls these methods directly.
 * Only ChatBubbleRepository touches the DAO.
 */
@Dao
interface ChatBubbleDao {

    /**
     * Observe all bubbles for a session, ordered by sequence.
     * Returns a Flow that re-emits whenever the table changes.
     * This is the primary read path — Compose collects this.
     */
    @Query("SELECT * FROM chat_bubbles WHERE session_id = :sessionId ORDER BY sequence ASC")
    fun observeBubbles(sessionId: String): Flow<List<ChatBubbleEntity>>

    /**
     * Insert or update bubbles. REPLACE handles dedup on (session_id, sequence).
     * Used for both initial load (batch insert from HTTP) and SSE push (single insert).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bubbles: List<ChatBubbleEntity>)

    /**
     * Insert a single bubble from an SSE event.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bubble: ChatBubbleEntity)

    /**
     * Get the max sequence for a session — used to determine what's already loaded.
     */
    @Query("SELECT MAX(sequence) FROM chat_bubbles WHERE session_id = :sessionId")
    suspend fun getMaxSequence(sessionId: String): Int?

    /**
     * Get the min sequence for a session — used for scroll-up pagination.
     */
    @Query("SELECT MIN(sequence) FROM chat_bubbles WHERE session_id = :sessionId")
    suspend fun getMinSequence(sessionId: String): Int?

    /**
     * Count bubbles for a session.
     */
    @Query("SELECT COUNT(*) FROM chat_bubbles WHERE session_id = :sessionId")
    suspend fun countBubbles(sessionId: String): Int

    /**
     * Clear all bubbles for a session (used on refresh/reload).
     */
    @Query("DELETE FROM chat_bubbles WHERE session_id = :sessionId")
    suspend fun clearSession(sessionId: String)

    /**
     * Clear all bubbles across all sessions.
     */
    @Query("DELETE FROM chat_bubbles")
    suspend fun clearAll()
}

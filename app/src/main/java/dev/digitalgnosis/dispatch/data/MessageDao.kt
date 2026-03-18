package dev.digitalgnosis.dispatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for dispatch message persistence.
 *
 * Design: MessageRepository uses this as its persistence layer.
 * The StateFlow in MessageRepository remains the reactive source for UI —
 * Room is the durable backing store underneath.
 */
@Dao
interface MessageDao {

    /** Insert a single message. Returns the auto-generated row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity): Long

    /** Load all messages, newest first. Capped at 100 to match cache intent. */
    @Query("SELECT * FROM messages ORDER BY received_at DESC LIMIT 100")
    suspend fun getAll(): List<MessageEntity>

    /** Delete all messages. Used for explicit clear. */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** Count total messages in the table. */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /**
     * Trim old messages, keeping only the most recent [keepCount].
     * Deletes everything outside the top N by received_at.
     */
    @Query("""
        DELETE FROM messages WHERE id NOT IN (
            SELECT id FROM messages ORDER BY received_at DESC LIMIT :keepCount
        )
    """)
    suspend fun trimToCount(keepCount: Int)
}

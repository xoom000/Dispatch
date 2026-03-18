package dev.digitalgnosis.dispatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadMessageDao {
    @Query("SELECT * FROM thread_messages WHERE thread_id = :threadId ORDER BY created_at ASC")
    fun getMessagesForThreadFlow(threadId: String): Flow<List<ThreadMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ThreadMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ThreadMessageEntity)

    @Query("DELETE FROM thread_messages WHERE thread_id = :threadId")
    suspend fun deleteForThread(threadId: String)
}
package dev.digitalgnosis.dispatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {
    @Query("SELECT * FROM threads ORDER BY last_activity DESC")
    fun getAllThreadsFlow(): Flow<List<ThreadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(threads: List<ThreadEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(thread: ThreadEntity)

    @Query("DELETE FROM threads")
    suspend fun deleteAll()
}

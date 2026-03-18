package dev.digitalgnosis.dispatch.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val cmailRepository: CmailRepository,
    private val threadDao: ThreadDao,
    private val threadMessageDao: ThreadMessageDao
) {
    /**
     * Fetch threads from network and mirror them to the local Room database.
     * The UI will automatically update because it observes the Room DAOs via Flow.
     */
    suspend fun syncThreads() {
        withContext(Dispatchers.IO) {
            try {
                Timber.i("SyncManager: === START THREAD SYNC ===")
                val result = cmailRepository.fetchThreads(limit = 100)
                val entities = result.threads.map { it.toEntity() }
                
                Timber.d("SyncManager: Fetched %d threads from File Bridge. Inserting to Room...", entities.size)
                threadDao.insertAll(entities)
                Timber.i("SyncManager: === THREAD SYNC COMPLETE (stored: %d) ===", entities.size)
            } catch (e: Exception) {
                Timber.e(e, "SyncManager: Thread sync failed")
            }
        }
    }

    /**
     * Fetch a specific thread's detail from network and mirror to Room.
     */
    suspend fun syncThreadDetail(threadId: String) {
        withContext(Dispatchers.IO) {
            try {
                Timber.i("SyncManager: === START DETAIL SYNC [%s] ===", threadId.take(8))
                val detail = cmailRepository.fetchThreadDetail(threadId)
                if (detail != null) {
                    val messages = detail.messages.map { it.toEntity() }
                    Timber.d("SyncManager: [%s] Fetched %d messages. Updating Room...", threadId.take(8), messages.size)
                    threadMessageDao.insertAll(messages)
                    Timber.i("SyncManager: === DETAIL SYNC COMPLETE [%s] (%d msgs) ===", threadId.take(8), messages.size)
                } else {
                    Timber.w("SyncManager: [%s] Server returned null detail", threadId.take(8))
                }
            } catch (e: Exception) {
                Timber.e(e, "SyncManager: Detail sync failed for %s", threadId)
            }
        }
    }
}
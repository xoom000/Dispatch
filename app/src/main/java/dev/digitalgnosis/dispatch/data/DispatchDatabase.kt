package dev.digitalgnosis.dispatch.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for local message persistence.
 *
 * Version 1: Single table — messages.
 * Version 2: Phase 5 Sovereign Sync — added threads and thread_messages.
 * Destructive migration is acceptable here — this is a cache of data
 * that also exists on pop-os (dispatch history DB + File Bridge).
 * If the schema changes, wiping and re-receiving is fine.
 */
@Database(
    entities = [
        MessageEntity::class,
        ThreadEntity::class,
        ThreadMessageEntity::class,
        ChatBubbleEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class DispatchDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun threadDao(): ThreadDao
    abstract fun threadMessageDao(): ThreadMessageDao
    abstract fun chatBubbleDao(): ChatBubbleDao
}

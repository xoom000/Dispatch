package dev.digitalgnosis.dispatch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val threadId: String,
    val subject: String,
    val participants: String, // Comma separated
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "last_activity") val lastActivity: String,
    @ColumnInfo(name = "last_sender") val lastSender: String? = null,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String? = null,
    @ColumnInfo(name = "sync_status") val syncStatus: String = "SYNCED" // PENDING, SYNCED
)

fun ThreadInfo.toEntity(): ThreadEntity = ThreadEntity(
    threadId = threadId,
    subject = subject,
    participants = participants.joinToString(","),
    messageCount = messageCount,
    createdAt = createdAt,
    lastActivity = lastActivity,
    lastSender = lastSender,
    lastMessagePreview = lastMessagePreview,
    syncStatus = "SYNCED"
)

fun ThreadEntity.toDomainModel(): ThreadInfo = ThreadInfo(
    threadId = threadId,
    subject = subject,
    participants = participants.split(",").filter { it.isNotBlank() },
    messageCount = messageCount,
    createdAt = createdAt,
    lastActivity = lastActivity,
    lastSender = lastSender,
    lastMessagePreview = lastMessagePreview
)
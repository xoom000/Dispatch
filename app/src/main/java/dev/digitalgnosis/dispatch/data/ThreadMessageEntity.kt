package dev.digitalgnosis.dispatch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "thread_messages",
    indices = [Index(value = ["thread_id"])]
)
data class ThreadMessageEntity(
    @PrimaryKey val messageId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    val position: Int,
    val sender: String,
    val recipient: String,
    val cc: String? = null,
    val subject: String,
    val body: String,
    val priority: String,
    val delivery: String,
    val read: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: String,
    val attachments: String? = null,
    @ColumnInfo(name = "sync_status") val syncStatus: String = "SYNCED" // PENDING, SYNCED, FAILED
)

fun ThreadMessage.toEntity(status: String = "SYNCED"): ThreadMessageEntity = ThreadMessageEntity(
    messageId = messageId,
    threadId = threadId,
    position = position,
    sender = sender,
    recipient = recipient,
    cc = cc,
    subject = subject,
    body = body,
    priority = priority,
    delivery = delivery,
    read = read,
    createdAt = createdAt,
    attachments = attachments,
    syncStatus = status
)

fun ThreadMessageEntity.toDomainModel(): ThreadMessage = ThreadMessage(
    messageId = messageId,
    threadId = threadId,
    position = position,
    sender = sender,
    recipient = recipient,
    cc = cc,
    subject = subject,
    body = body,
    priority = priority,
    delivery = delivery,
    read = read,
    createdAt = createdAt,
    attachments = attachments
)

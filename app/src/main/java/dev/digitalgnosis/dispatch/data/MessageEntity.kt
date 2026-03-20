package dev.digitalgnosis.dispatch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting voice notifications and outgoing cmail items across app restarts.
 * Discriminated by isOutgoing: false = VoiceNotification, true = CmailOutboxItem.
 *
 * List fields (fileUrls, fileNames, fileSizes) are stored as comma-separated strings.
 * This avoids a junction table for a simple cache.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val message: String,
    val priority: String,
    val timestamp: String,
    val voice: String? = null,
    @ColumnInfo(name = "file_url") val fileUrl: String? = null,
    @ColumnInfo(name = "file_name") val fileName: String? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "file_urls") val fileUrls: String? = null,
    @ColumnInfo(name = "file_names") val fileNames: String? = null,
    @ColumnInfo(name = "file_sizes") val fileSizes: String? = null,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "is_outgoing") val isOutgoing: Boolean = false,
    @ColumnInfo(name = "target_department") val targetDepartment: String? = null,
    val invoked: Boolean = false,
    @ColumnInfo(name = "invoked_department") val invokedDepartment: String? = null,
    @ColumnInfo(name = "invoked_at") val invokedAt: Long = 0L,
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
    @ColumnInfo(name = "thread_id") val threadId: String? = null,
)

/** Convert domain model to Room entity. */
fun DispatchMessage.toEntity(): MessageEntity = MessageEntity(
    sender = sender,
    message = message,
    priority = priority,
    timestamp = timestamp,
    voice = voice,
    fileUrl = fileUrl,
    fileName = fileName,
    fileSize = fileSize,
    fileUrls = fileUrls.joinToString(",").ifEmpty { null },
    fileNames = fileNames.joinToString(",").ifEmpty { null },
    fileSizes = fileSizes.joinToString(",").ifEmpty { null },
    receivedAt = receivedAt,
    isOutgoing = isOutgoing,
    targetDepartment = targetDepartment,
    invoked = invoked,
    invokedDepartment = invokedDepartment,
    invokedAt = invokedAt,
    sessionId = sessionId,
    threadId = threadId,
)

/** Convert Room entity back to legacy domain model. */
fun MessageEntity.toDomainModel(): DispatchMessage = DispatchMessage(
    sender = sender,
    message = message,
    priority = priority,
    timestamp = timestamp,
    voice = voice,
    fileUrl = fileUrl,
    fileName = fileName,
    fileSize = fileSize,
    fileUrls = fileUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    fileNames = fileNames?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    fileSizes = fileSizes?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList(),
    receivedAt = receivedAt,
    isOutgoing = isOutgoing,
    targetDepartment = targetDepartment,
    invoked = invoked,
    invokedDepartment = invokedDepartment,
    invokedAt = invokedAt,
    sessionId = sessionId,
    threadId = threadId,
)

/** Convert incoming entity to VoiceNotification domain model. */
fun MessageEntity.toVoiceNotification(): VoiceNotification = VoiceNotification(
    sender = sender,
    message = message,
    voice = voice,
    timestamp = timestamp,
    priority = priority,
    cmailThreadId = threadId,
    receivedAt = receivedAt,
    fileUrl = fileUrl,
    fileName = fileName,
    fileSize = fileSize,
    fileUrls = fileUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    fileNames = fileNames?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    fileSizes = fileSizes?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList(),
)

/** Convert VoiceNotification to Room entity. */
fun VoiceNotification.toEntity(): MessageEntity = MessageEntity(
    sender = sender,
    message = message,
    priority = priority,
    timestamp = timestamp,
    voice = voice,
    fileUrl = fileUrl,
    fileName = fileName,
    fileSize = fileSize,
    fileUrls = fileUrls.joinToString(",").ifEmpty { null },
    fileNames = fileNames.joinToString(",").ifEmpty { null },
    fileSizes = fileSizes.joinToString(",").ifEmpty { null },
    receivedAt = receivedAt,
    isOutgoing = false,
    threadId = cmailThreadId,
)

/** Convert outgoing entity to CmailOutboxItem domain model. */
fun MessageEntity.toCmailOutboxItem(): CmailOutboxItem = CmailOutboxItem(
    department = targetDepartment ?: sender,
    message = message,
    sentAt = receivedAt,
    invoked = invoked,
    invokedDepartment = invokedDepartment,
    invokedAt = invokedAt,
    threadId = threadId,
    sessionId = sessionId,
    fileNames = fileNames?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
)

/** Convert CmailOutboxItem to Room entity. */
fun CmailOutboxItem.toEntity(): MessageEntity = MessageEntity(
    sender = "You",
    message = message,
    priority = "normal",
    timestamp = "",
    receivedAt = sentAt,
    isOutgoing = true,
    targetDepartment = department,
    invoked = invoked,
    invokedDepartment = invokedDepartment,
    invokedAt = invokedAt,
    threadId = threadId,
    sessionId = sessionId,
    fileNames = fileNames.joinToString(",").ifEmpty { null },
)

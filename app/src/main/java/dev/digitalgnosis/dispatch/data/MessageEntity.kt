package dev.digitalgnosis.dispatch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting dispatch messages across app restarts.
 * Maps 1:1 to DispatchMessage domain model via extension functions.
 *
 * List fields (fileUrls, fileNames, fileSizes) are stored as comma-separated strings
 * via the Converters class. This avoids a junction table for a simple cache.
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

/** Convert Room entity back to domain model. */
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

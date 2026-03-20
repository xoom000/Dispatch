package dev.digitalgnosis.dispatch.data

/**
 * Domain model for an incoming FCM voice message from a DG agent.
 *
 * Represents exactly one voice dispatch: the spoken message, who sent it,
 * any file attachments, and the thread it belongs to.
 */
data class VoiceNotification(
    val sender: String,
    val message: String,
    val voice: String?,
    val timestamp: String,
    val priority: String = "normal",
    val cmailThreadId: String? = null,
    val traceId: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    /** Single file fields — backward compat with v2.1 payloads. */
    val fileUrl: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    /** Multi-file fields (v2.6). */
    val fileUrls: List<String> = emptyList(),
    val fileNames: List<String> = emptyList(),
    val fileSizes: List<Long> = emptyList(),
) {
    /** All attachment URLs — merges multi-file with single-file fallback. */
    val allFileUrls: List<String> get() =
        fileUrls.ifEmpty { listOfNotNull(fileUrl) }

    /** All attachment names — merges multi-file with single-file fallback. */
    val allFileNames: List<String> get() =
        fileNames.ifEmpty { listOfNotNull(fileName) }

    /** True if this notification has at least one downloadable file attachment. */
    val hasAttachment: Boolean get() = allFileUrls.isNotEmpty() && allFileNames.isNotEmpty()
}

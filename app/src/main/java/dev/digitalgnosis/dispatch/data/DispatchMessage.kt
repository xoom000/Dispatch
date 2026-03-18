package dev.digitalgnosis.dispatch.data

data class DispatchMessage(
    val sender: String,
    val message: String,
    val priority: String,
    val timestamp: String,
    val voice: String? = null,
    /** Single file fields — backward compat with v2.1 payloads. */
    val fileUrl: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    /** Multi-file fields (v2.6). */
    val fileUrls: List<String> = emptyList(),
    val fileNames: List<String> = emptyList(),
    val fileSizes: List<Long> = emptyList(),
    val receivedAt: Long = System.currentTimeMillis(),
    /** True if this is an outgoing message sent by Nigel from the app. */
    val isOutgoing: Boolean = false,
    /** Target department(s) for outgoing messages, comma-separated. */
    val targetDepartment: String? = null,
    /** True if this message triggered an agent invocation via cmail. */
    val invoked: Boolean = false,
    /** Which department was invoked (for Watch button routing). */
    val invokedDepartment: String? = null,
    /** Epoch ms when invocation was sent (for session discovery time guard). */
    val invokedAt: Long = 0L,
    /** Session ID returned by File Bridge for direct Watch targeting (skips discovery). */
    val sessionId: String? = null,
    /** Cmail thread ID — links this dispatch message to a conversation thread. */
    val threadId: String? = null,
) {
    /** All attachment URLs — merges multi-file with single-file fallback. */
    val allFileUrls: List<String> get() =
        fileUrls.ifEmpty { listOfNotNull(fileUrl) }

    /** All attachment names — merges multi-file with single-file fallback. */
    val allFileNames: List<String> get() =
        fileNames.ifEmpty { listOfNotNull(fileName) }

    /** All attachment sizes — merges multi-file with single-file fallback. */
    val allFileSizes: List<Long> get() =
        fileSizes.ifEmpty { listOfNotNull(fileSize) }

    /** True if this message has at least one downloadable file attachment. */
    val hasAttachment: Boolean get() = allFileUrls.isNotEmpty() && allFileNames.isNotEmpty()
}

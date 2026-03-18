package dev.digitalgnosis.dispatch.data

/**
 * A single dispatch message from the server-side history database.
 * This is the full archive — every message ever sent, stored on pop-os.
 *
 * Maps to the File Bridge GET /dispatch/history response shape.
 */
data class HistoryMessage(
    val id: Int,
    val timestamp: String,
    val sender: String,
    val message: String,
    val voice: String,
    val priority: String,
    val success: Boolean,
    val error: String?,
    val fileName: String?,
    val fileUrl: String?,
    val fileSize: Long?,
    val threadId: String?,
)

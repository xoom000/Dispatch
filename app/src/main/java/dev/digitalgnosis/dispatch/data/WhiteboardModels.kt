package dev.digitalgnosis.dispatch.data

/**
 * A single task on the CEO's whiteboard.
 */
data class WhiteboardTask(
    val id: String,
    val title: String,
    val assignee: String,
    val status: String,        // active, blocked, done, parked
    val priority: String = "normal",  // high, normal, low
    val threadId: String? = null,
    val note: String? = null,
    val created: String = "",
    val updated: String = "",
)

/**
 * The full whiteboard response from GET /whiteboard.
 */
data class Whiteboard(
    val lastUpdated: String = "",
    val tasks: List<WhiteboardTask> = emptyList(),
)

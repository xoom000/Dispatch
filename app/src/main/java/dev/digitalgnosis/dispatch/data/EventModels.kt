package dev.digitalgnosis.dispatch.data

/**
 * A single event from the Event Orchestrator.
 * Matches the shape returned by GET /feed.
 */
data class OrchestratorEvent(
    val id: Int,
    val eventType: String,
    val timestamp: String,
    val source: String,
    val department: String,
    val threadId: String,
    val sessionId: String,
    val summary: String,
)

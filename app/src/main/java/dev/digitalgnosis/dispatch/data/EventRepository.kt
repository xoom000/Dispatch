package dev.digitalgnosis.dispatch.data

/**
 * Domain interface for the Event Orchestrator feed.
 */
interface EventRepository {

    fun fetchEvents(
        eventType: String? = null,
        department: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): EventListResult

    data class EventListResult(
        val events: List<OrchestratorEvent>,
        val total: Int,
    )
}

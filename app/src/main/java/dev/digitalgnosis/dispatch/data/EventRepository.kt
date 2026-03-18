package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the Event Orchestrator feed.
 *
 * Tracks cross-department activity and system events.
 */
@Singleton
class EventRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Fetch events from the Event Orchestrator feed.
     */
    fun fetchEvents(
        eventType: String? = null,
        department: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): EventListResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (eventType != null) append("&event_type=$eventType")
            if (department != null) append("&department=$department")
        }
        
        val body = client.get("feed?$params") ?: return EventListResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("events") ?: JSONArray()
            val events = mutableListOf<OrchestratorEvent>()

            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                events.add(OrchestratorEvent(
                    id = e.optInt("id", 0),
                    eventType = e.optString("event_type", ""),
                    timestamp = e.optString("timestamp", ""),
                    source = e.optString("source", ""),
                    department = e.optString("department", ""),
                    threadId = e.optString("thread_id", ""),
                    sessionId = e.optString("session_id", ""),
                    summary = e.optString("summary", ""),
                ))
            }
            EventListResult(events, json.optInt("total", events.size))
        } catch (e: Exception) {
            Timber.e(e, "EventFeed: parse failed")
            EventListResult(emptyList(), 0)
        }
    }

    /**
     * Data wrapper for event list responses.
     */
    data class EventListResult(
        val events: List<OrchestratorEvent>,
        val total: Int,
    )
}

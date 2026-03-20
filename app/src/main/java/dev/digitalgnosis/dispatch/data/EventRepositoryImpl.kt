package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [EventRepository].
 * Injected via [EventRepository] interface — never inject this class directly.
 */
@Singleton
class EventRepositoryImpl @Inject constructor(
    private val client: BaseFileBridgeClient
) : EventRepository {

    override fun fetchEvents(
        eventType: String?,
        department: String?,
        limit: Int,
        offset: Int,
    ): EventRepository.EventListResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (eventType != null) append("&event_type=$eventType")
            if (department != null) append("&department=$department")
        }
        Timber.d("EventRepo: fetchEvents — requesting (type=%s, dept=%s, limit=%d, offset=%d)",
            eventType, department, limit, offset)
        val body = client.get("feed?$params") ?: return EventRepository.EventListResult(emptyList(), 0)

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
            val result = EventRepository.EventListResult(events, json.optInt("total", events.size))
            Timber.d("EventRepo: fetchEvents — got %d events (total=%d)", result.events.size, result.total)
            result
        } catch (e: Exception) {
            Timber.e(e, "EventRepo: fetchEvents parse failed")
            EventRepository.EventListResult(emptyList(), 0)
        }
    }
}

package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the shared project Whiteboard.
 *
 * Tracks tasks, assignments, and status across all departments.
 */
@Singleton
class WhiteboardRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Fetch the complete whiteboard state.
     */
    fun fetchWhiteboard(): Whiteboard {
        val body = client.get("whiteboard") ?: return Whiteboard()

        return try {
            val json = JSONObject(body)
            val lastUpdated = json.optString("last_updated", "")
            val tasksArr = json.optJSONArray("tasks") ?: JSONArray()
            val tasks = mutableListOf<WhiteboardTask>()

            for (i in 0 until tasksArr.length()) {
                val t = tasksArr.getJSONObject(i)
                tasks.add(
                    WhiteboardTask(
                        id = t.optString("id", ""),
                        title = t.optString("title", ""),
                        assignee = t.optString("assignee", ""),
                        status = t.optString("status", "active"),
                        priority = t.optString("priority", "normal"),
                        threadId = t.optString("thread_id", "").ifBlank { null },
                        note = t.optString("note", "").ifBlank { null },
                        created = t.optString("created", ""),
                        updated = t.optString("updated", ""),
                    )
                )
            }
            Whiteboard(lastUpdated, tasks)
        } catch (e: Exception) {
            Timber.e(e, "Whiteboard: parse failed")
            Whiteboard()
        }
    }
}

package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Dispatch message history.
 *
 * Fetches the full archive of outgoing voice messages from pop-os.
 */
@Singleton
class HistoryRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Fetch dispatch message history from the pop-os archive.
     */
    fun fetchDispatchHistory(
        sender: String? = null,
        search: String? = null,
        priority: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): HistoryResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (sender != null) append("&sender=${java.net.URLEncoder.encode(sender, "UTF-8")}")
            if (search != null) append("&search=${java.net.URLEncoder.encode(search, "UTF-8")}")
            if (priority != null) append("&priority=$priority")
        }
        
        val body = client.get("dispatch/history?$params") ?: return HistoryResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("messages")
            val messages = mutableListOf<HistoryMessage>()

            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                messages.add(HistoryMessage(
                    id = m.optInt("id", 0),
                    timestamp = m.optString("timestamp", ""),
                    sender = m.optString("sender", ""),
                    message = m.optString("message", ""),
                    voice = m.optString("voice", ""),
                    priority = m.optString("priority", "normal"),
                    success = m.optInt("success", 1) == 1,
                    error = if (m.isNull("error")) null else m.optString("error", ""),
                    fileName = if (m.isNull("file_name")) null else m.optString("file_name", ""),
                    fileUrl = if (m.isNull("file_url")) null else m.optString("file_url", ""),
                    fileSize = if (m.isNull("file_size")) null else m.optLong("file_size", 0),
                    threadId = if (m.isNull("thread_id")) null else m.optString("thread_id", ""),
                ))
            }
            HistoryResult(messages, json.optInt("total", messages.size))
        } catch (e: Exception) {
            Timber.e(e, "DispatchHistory: parse failed")
            HistoryResult(emptyList(), 0)
        }
    }

    /** Data wrapper for history responses. */
    data class HistoryResult(val messages: List<HistoryMessage>, val total: Int)
}

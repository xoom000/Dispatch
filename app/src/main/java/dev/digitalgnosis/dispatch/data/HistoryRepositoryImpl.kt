package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [HistoryRepository].
 * Injected via [HistoryRepository] interface — never inject this class directly.
 */
@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val client: BaseFileBridgeClient
) : HistoryRepository {

    override fun fetchDispatchHistory(
        sender: String?,
        search: String?,
        priority: String?,
        limit: Int,
        offset: Int,
    ): HistoryRepository.HistoryResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (sender != null) append("&sender=${java.net.URLEncoder.encode(sender, "UTF-8")}")
            if (search != null) append("&search=${java.net.URLEncoder.encode(search, "UTF-8")}")
            if (priority != null) append("&priority=$priority")
        }
        Timber.d("HistoryRepo: fetchDispatchHistory — requesting (sender=%s, limit=%d, offset=%d)", sender, limit, offset)
        val body = client.get("dispatch/history?$params") ?: return HistoryRepository.HistoryResult(emptyList(), 0)

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
            val result = HistoryRepository.HistoryResult(messages, json.optInt("total", messages.size))
            Timber.d("HistoryRepo: fetchDispatchHistory — got %d messages (total=%d)", result.messages.size, result.total)
            result
        } catch (e: Exception) {
            Timber.e(e, "HistoryRepo: fetchDispatchHistory parse failed")
            HistoryRepository.HistoryResult(emptyList(), 0)
        }
    }
}

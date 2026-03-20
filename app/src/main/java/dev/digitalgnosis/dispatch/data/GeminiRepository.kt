package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.config.TailscaleConfig
import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the "Gemini Native" path.
 * 
 * Communicates directly with the dedicated /gemini endpoints on the File Bridge,
 * bypassing the legacy Cmail/Claude path for high-fidelity control.
 */
@Singleton
class GeminiRepository @Inject constructor(
    private val networkClient: BaseFileBridgeClient
) {

    /**
     * Send a message to Gemini and receive a stream of real-time updates
     * (thoughts, message chunks, tool calls).
     */
    fun sendNativePrompt(threadId: String, message: String): Flow<GeminiUpdate> = flow {
        val url = "${TailscaleConfig.FILE_BRIDGE_SERVER}/gemini/send"
        Timber.d("GeminiRepo: sendNativePrompt — requesting (thread=%s, msgLen=%d)", threadId.take(8), message.length)
        Timber.i("GeminiRepo: POST %s", url)
        
        val bodyJson = JSONObject()
        bodyJson.put("thread_id", threadId)
        bodyJson.put("message", message)
        
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            networkClient.getRawClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "HTTP ${response.code}: ${response.message}"
                    Timber.w("GeminiRepo: request FAILED: %s", err)
                    emit(GeminiUpdate.Error(err))
                    return@use
                }

                val reader = response.body?.source() ?: return@use
                var chunkCount = 0
                while (!reader.exhausted()) {
                    val line = reader.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        try {
                            val json = JSONObject(data)
                            val updateType = json.optString("sessionUpdate", "")
                            val content = json.optJSONObject("content")
                            val text = content?.optString("text", "") ?: ""

                            when (updateType) {
                                "agent_thought_chunk" -> emit(GeminiUpdate.Thought(text))
                                "agent_message_chunk" -> { chunkCount++; emit(GeminiUpdate.MessageChunk(text)) }
                            }
                        } catch (e: Exception) {
                            Timber.w("GeminiRepo: parse error: %s", e.message)
                        }
                    }
                }
                Timber.d("GeminiRepo: sendNativePrompt — stream complete (%d message chunks)", chunkCount)
            }
        } catch (e: Exception) {
            Timber.e(e, "GeminiRepo: Stream FAILED")
            emit(GeminiUpdate.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fetch the list of native Gemini sessions from the File Bridge.
     */
    suspend fun fetchSessions(): List<GeminiSessionInfo> = withContext(Dispatchers.IO) {
        Timber.d("GeminiRepo: fetchSessions — requesting")
        val body = networkClient.get("gemini/sessions") ?: return@withContext emptyList()
        try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("sessions") ?: JSONArray()
            val result = mutableListOf<GeminiSessionInfo>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                result.add(GeminiSessionInfo(
                    id = item.getString("id"),
                    startTime = item.optString("startTime", ""),
                    lastUpdated = item.optString("lastUpdated", ""),
                    messageCount = item.optInt("messageCount", 0),
                    preview = item.optString("preview", "")
                ))
            }
            Timber.d("GeminiRepo: fetchSessions — got %d sessions", result.size)
            result
        } catch (e: Exception) {
            Timber.e(e, "GeminiRepo: fetchSessions FAILED")
            emptyList()
        }
    }

    /**
     * Fetch the full details of a specific native Gemini session.
     */
    suspend fun fetchSessionDetail(sessionId: String): JSONObject? = withContext(Dispatchers.IO) {
        Timber.d("GeminiRepo: fetchSessionDetail — requesting (session=%s)", sessionId.take(8))
        val body = networkClient.get("gemini/sessions/$sessionId") ?: return@withContext null
        try {
            val json = JSONObject(body)
            Timber.d("GeminiRepo: fetchSessionDetail — got response for %s", sessionId.take(8))
            json
        } catch (e: Exception) {
            Timber.e(e, "GeminiRepo: fetchSessionDetail FAILED for %s", sessionId.take(8))
            null
        }
    }
}

data class GeminiSessionInfo(
    val id: String,
    val startTime: String,
    val lastUpdated: String,
    val messageCount: Int,
    val preview: String
)

sealed class GeminiUpdate {
    data class Thought(val text: String) : GeminiUpdate()
    data class MessageChunk(val text: String) : GeminiUpdate()
    data class Error(val message: String) : GeminiUpdate()
}

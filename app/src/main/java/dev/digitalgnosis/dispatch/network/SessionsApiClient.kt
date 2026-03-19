package dev.digitalgnosis.dispatch.network

import dev.digitalgnosis.dispatch.config.AnthropicAuthManager
import dev.digitalgnosis.dispatch.data.StreamEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the Anthropic Sessions API.
 *
 * Handles session creation, event sending, and SSE subscription
 * for the remote-control bridge architecture.
 *
 * All calls go to api.anthropic.com, authenticated via OAuth.
 * Sessions execute on pop-os via the registered bridge environment.
 */
@Singleton
class SessionsApiClient @Inject constructor(
    private val authManager: AnthropicAuthManager,
) {

    /** Standard client for REST calls. */
    private val restClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Long-lived client for SSE event subscriptions. */
    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // SSE: no read timeout
        .build()

    // ── Session Lifecycle ────────────────────────────────────────────

    /**
     * Create a new session on the bridge with an initial prompt.
     * Returns the session ID (format: session_01...) or null on failure.
     *
     * The initial message is included in the create request so the bridge
     * processes it immediately — no separate sendEvent needed for turn 1.
     */
    fun createSession(prompt: String): String? {
        // Discover bridge dynamically if not set or stale
        val envId = authManager.bridgeEnvironmentId
            ?: discoverBridgeEnvironment()?.also { authManager.bridgeEnvironmentId = it }
            ?: run {
                Timber.e("SessionsAPI: no bridge environment available")
                return null
            }

        val payload = JSONObject().apply {
            put("title", prompt.take(60))
            put("events", JSONArray().put(JSONObject().apply {
                put("type", "user")
                put("uuid", java.util.UUID.randomUUID().toString())
                put("message", JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }))
            put("session_context", JSONObject().apply {
                put("model", "claude-sonnet-4-6")
            })
            put("environment_id", envId)
        }

        val body = executePost("/v1/sessions", payload.toString()) ?: return null

        return try {
            val json = JSONObject(body)
            val sessionId = json.optString("id", "")
                .ifBlank { json.optString("session_id", "") }
            if (sessionId.isBlank()) null else sessionId.also {
                Timber.i("SessionsAPI: created session %s", it.take(20))
            }
        } catch (e: Exception) {
            Timber.e(e, "SessionsAPI: failed to parse create response")
            null
        }
    }

    /**
     * Send a message (event) to an existing session.
     * Returns true on success.
     *
     * Format matches JSONL session file structure: flat user event with
     * uuid, message.role, message.content. Wrapped in {"events": [...]}.
     */
    fun sendEvent(sessionId: String, message: String): Boolean {
        val wrapper = JSONObject().apply {
            put("events", JSONArray().put(JSONObject().apply {
                put("type", "user")
                put("uuid", java.util.UUID.randomUUID().toString())
                put("message", JSONObject().apply {
                    put("role", "user")
                    put("content", message)
                })
            }))
        }

        val result = executePost("/v1/sessions/$sessionId/events", wrapper.toString())
        return result != null
    }

    /**
     * Subscribe to session events via SSE.
     * Returns a Flow of StreamEvent that emits as the agent works.
     * Uses the same StreamEvent sealed class as the File Bridge streaming.
     */
    fun subscribeToSession(sessionId: String): Flow<StreamEvent> = callbackFlow {
        val url = "${AnthropicAuthManager.BASE_URL}/v1/sessions/$sessionId/events"
        val request = buildRequest(url)
            .addHeader("Accept", "text/event-stream")
            .get()
            .build()

        val factory = EventSources.createFactory(sseClient)
        val eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Timber.i("SessionsAPI: SSE connected to session %s (status=%d)",
                    sessionId.take(20), response.code)
                trySend(StreamEvent.Connected(
                    streamId = sessionId,
                    department = "",
                ))
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                try {
                    val json = JSONObject(data)
                    val eventType = type ?: json.optString("type", "")

                    // Parse stream-json events — same format as our File Bridge relay
                    val event: StreamEvent? = when {
                        eventType == "content_block_delta" || hasNestedType(json, "content_block_delta") -> {
                            val delta = json.optJSONObject("delta")
                                ?: json.optJSONObject("event")?.optJSONObject("delta")
                            when (delta?.optString("type")) {
                                "text_delta" -> StreamEvent.Token(delta.optString("text", ""))
                                "thinking_delta" -> StreamEvent.Thinking(delta.optString("thinking", ""))
                                else -> null
                            }
                        }

                        eventType == "content_block_start" || hasNestedType(json, "content_block_start") -> {
                            val block = json.optJSONObject("content_block")
                                ?: json.optJSONObject("event")?.optJSONObject("content_block")
                            if (block?.optString("type") == "tool_use") {
                                StreamEvent.Tool(
                                    name = block.optString("name", ""),
                                    status = "started",
                                    toolUseId = block.optString("id", ""),
                                )
                            } else null
                        }

                        eventType == "assistant" -> {
                            val msg = json.optJSONObject("message") ?: json
                            val content = msg.optJSONArray("content")
                            var text = ""
                            if (content != null) {
                                for (i in 0 until content.length()) {
                                    val block = content.optJSONObject(i)
                                    if (block?.optString("type") == "text") {
                                        text += block.optString("text", "")
                                    }
                                }
                            }
                            if (text.isNotBlank()) StreamEvent.AssistantText(text) else null
                        }

                        eventType.startsWith("result") -> {
                            if (eventType.contains("error")) {
                                StreamEvent.Error(
                                    errorType = eventType,
                                    result = json.optString("result", ""),
                                )
                            } else {
                                StreamEvent.Done(
                                    result = json.optString("result", ""),
                                    costUsd = json.optDouble("total_cost_usd", 0.0),
                                    durationMs = json.optLong("duration_ms", 0),
                                    sessionId = json.optString("session_id", sessionId),
                                    stopReason = json.optString("stop_reason", ""),
                                )
                            }
                        }

                        eventType == "tool_progress" -> {
                            StreamEvent.Tool(
                                name = json.optString("tool_name", ""),
                                status = "progress",
                            )
                        }

                        eventType.startsWith("system/") -> {
                            StreamEvent.Status(
                                status = json.optString("status", eventType),
                                model = json.optString("model", ""),
                                sessionId = json.optString("session_id", ""),
                            )
                        }

                        // stream_event wrapper — unwrap and recurse
                        eventType == "stream_event" -> {
                            val inner = json.optJSONObject("event")
                            if (inner != null) {
                                // Re-parse the inner event
                                val innerType = inner.optString("type", "")
                                when {
                                    innerType == "content_block_delta" -> {
                                        val delta = inner.optJSONObject("delta")
                                        when (delta?.optString("type")) {
                                            "text_delta" -> StreamEvent.Token(delta.optString("text", ""))
                                            "thinking_delta" -> StreamEvent.Thinking(delta.optString("thinking", ""))
                                            else -> null
                                        }
                                    }
                                    innerType == "content_block_start" -> {
                                        val block = inner.optJSONObject("content_block")
                                        if (block?.optString("type") == "tool_use") {
                                            StreamEvent.Tool(
                                                name = block.optString("name", ""),
                                                status = "started",
                                            )
                                        } else null
                                    }
                                    else -> null
                                }
                            } else null
                        }

                        else -> null
                    }

                    if (event != null) {
                        trySend(event)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "SessionsAPI: failed to parse SSE event type=%s", type)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Timber.i("SessionsAPI: SSE closed for %s", sessionId.take(20))
                channel.close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                Timber.e(t, "SessionsAPI: SSE failed for %s (code=%d)",
                    sessionId.take(20), response?.code ?: -1)
                trySend(StreamEvent.Error(
                    errorType = "connection_error",
                    result = t?.message ?: "SSE failed (code=${response?.code})",
                ))
                channel.close()
            }
        })

        awaitClose {
            Timber.i("SessionsAPI: cancelling SSE for %s", sessionId.take(20))
            eventSource.cancel()
        }
    }

    /**
     * Get events for a session (JSON array, not SSE).
     * Returns the parsed event objects, or null on failure.
     */
    fun getSessionEvents(sessionId: String): List<JSONObject>? {
        val body = executeGet("/v1/sessions/$sessionId/events") ?: return null
        return try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return null
            (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        } catch (e: Exception) {
            Timber.e(e, "SessionsAPI: failed to parse events for %s", sessionId.take(20))
            null
        }
    }

    // ── Discovery ────────────────────────────────────────────────────

    /**
     * List available compute environments.
     * Used to discover the bridge environment ID dynamically.
     * Returns environments sorted: online bridges first.
     */
    fun listEnvironments(): List<EnvironmentInfo> {
        val body = executeGet("/v1/environment_providers") ?: return emptyList()
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("environments") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val env = arr.optJSONObject(i) ?: return@mapNotNull null
                val bridgeInfo = env.optJSONObject("bridge_info")
                EnvironmentInfo(
                    id = env.optString("environment_id", ""),
                    kind = env.optString("kind", ""),
                    name = env.optString("name", ""),
                    online = bridgeInfo?.optBoolean("online", false) ?: (env.optString("kind") == "anthropic_cloud"),
                )
            }.sortedByDescending { it.kind == "bridge" && it.online }
        } catch (e: Exception) {
            Timber.e(e, "SessionsAPI: failed to parse environments")
            emptyList()
        }
    }

    /**
     * Discover the online bridge environment ID.
     * Returns the first bridge environment that's online, or null.
     */
    fun discoverBridgeEnvironment(): String? {
        return listEnvironments()
            .firstOrNull { it.kind == "bridge" && it.online }
            ?.id
            ?.also { Timber.i("SessionsAPI: discovered bridge env %s", it.take(20)) }
    }

    data class EnvironmentInfo(
        val id: String,
        val kind: String,
        val name: String,
        val online: Boolean = false,
    )

    // ── HTTP helpers ─────────────────────────────────────────────────

    private fun buildRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        for ((key, value) in authManager.buildHeaders()) {
            builder.addHeader(key, value)
        }
        return builder
    }

    private fun executeGet(path: String): String? {
        val url = "${AnthropicAuthManager.BASE_URL}$path"
        val request = buildRequest(url).get().build()
        return executeRequest(request)
    }

    private fun executePost(path: String, json: String): String? {
        val url = "${AnthropicAuthManager.BASE_URL}$path"
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = buildRequest(url).post(body).build()
        return executeRequest(request)
    }

    private fun executeRequest(request: Request): String? {
        Timber.i("SessionsAPI: %s %s", request.method, request.url)
        return try {
            restClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    Timber.e("SessionsAPI: %s %s FAILED (code=%d) body=%s",
                        request.method, request.url.encodedPath, response.code,
                        body?.take(500) ?: "null")
                    return null
                }
                Timber.i("SessionsAPI: %s %s OK (%d bytes)",
                    request.method, request.url.encodedPath, body?.length ?: 0)
                body
            }
        } catch (e: Exception) {
            Timber.e(e, "SessionsAPI: %s %s error", request.method, request.url.encodedPath)
            null
        }
    }

    private fun hasNestedType(json: JSONObject, type: String): Boolean {
        return json.optJSONObject("event")?.optString("type") == type
    }
}

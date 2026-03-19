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
     * Poll for session events and emit them as a Flow of StreamEvent.
     *
     * The Sessions API events endpoint returns JSON (not SSE).
     * We poll with after_id to get incremental updates, and check
     * session status to know when the agent is done.
     *
     * @param afterId Start polling after this event ID (null = all events)
     * @param pollIntervalMs How often to poll (default 1s)
     */
    fun pollSessionEvents(
        sessionId: String,
        afterId: String? = null,
        pollIntervalMs: Long = 1000L,
    ): Flow<StreamEvent> = callbackFlow {
        var lastEventId = afterId
        var running = true

        trySend(StreamEvent.Connected(streamId = sessionId, department = ""))

        while (running) {
            try {
                val path = buildString {
                    append("/v1/sessions/$sessionId/events")
                    if (lastEventId != null) append("?after_id=$lastEventId")
                }
                val body = executeGet(path)
                if (body != null) {
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")

                    if (data != null && data.length() > 0) {
                        for (i in 0 until data.length()) {
                            val ev = data.optJSONObject(i) ?: continue
                            lastEventId = ev.optString("uuid", lastEventId)
                            val events = parseSessionEvent(ev, sessionId)
                            for (event in events) {
                                trySend(event)
                                if (event is StreamEvent.Done || event is StreamEvent.Error) {
                                    running = false
                                }
                            }
                        }
                    }
                }

                // Check session status — if idle, agent is done
                if (running) {
                    val status = getSessionStatus(sessionId)
                    if (status == "idle" && lastEventId != afterId) {
                        // Session went idle after we started — might be done
                        // Do one more poll to catch final events
                        Thread.sleep(pollIntervalMs)
                        continue
                    }
                    Thread.sleep(pollIntervalMs)
                }
            } catch (e: Exception) {
                Timber.e(e, "SessionsAPI: poll error for %s", sessionId.take(20))
                trySend(StreamEvent.Error("poll_error", e.message ?: "Poll failed"))
                running = false
            }
        }

        channel.close()
        awaitClose { running = false }
    }

    /** Get the current session status (pending, running, idle). */
    fun getSessionStatus(sessionId: String): String? {
        val body = executeGet("/v1/sessions/$sessionId") ?: return null
        return try {
            JSONObject(body).optString("session_status", null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a single session event JSON into StreamEvent(s).
     * Session events use the JSONL format: type, uuid, message/content, etc.
     */
    private fun parseSessionEvent(ev: JSONObject, sessionId: String): List<StreamEvent> {
        val type = ev.optString("type", "")
        val results = mutableListOf<StreamEvent>()

        when (type) {
            "assistant" -> {
                val msg = ev.optJSONObject("message")
                if (msg != null) {
                    val contentArr = msg.optJSONArray("content")
                    if (contentArr != null) {
                        val text = StringBuilder()
                        for (i in 0 until contentArr.length()) {
                            val block = contentArr.optJSONObject(i) ?: continue
                            if (block.optString("type") == "text") {
                                text.append(block.optString("text", ""))
                            }
                        }
                        if (text.isNotBlank()) {
                            results.add(StreamEvent.AssistantText(text.toString()))
                        }
                    }
                }
            }

            "result" -> {
                val subtype = ev.optString("subtype", "")
                if (subtype.contains("error")) {
                    results.add(StreamEvent.Error(
                        errorType = "result/$subtype",
                        result = ev.optString("result", ""),
                    ))
                } else {
                    results.add(StreamEvent.Done(
                        result = ev.optString("result", ""),
                        costUsd = ev.optDouble("total_cost_usd", 0.0),
                        durationMs = ev.optLong("duration_ms", 0),
                        sessionId = ev.optString("session_id", sessionId),
                        stopReason = ev.optString("stop_reason", ""),
                    ))
                }
            }

            "system" -> {
                val subtype = ev.optString("subtype", "")
                if (subtype == "init") {
                    results.add(StreamEvent.Status(
                        status = "init",
                        model = ev.optString("model", ""),
                        sessionId = ev.optString("session_id", ""),
                    ))
                }
            }

            // Skip: user, rate_limit_event, hook events
        }

        return results
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

    // ── Session List ───────────────────────────────────────────────────

    /**
     * List sessions from the Anthropic Sessions API.
     * Returns sessions sorted by most recent activity.
     *
     * GET /v1/sessions returns session objects with:
     *   id, title, session_status, created_at, updated_at, environment_id
     */
    fun listSessions(limit: Int = 30): List<SessionSummary> {
        val body = executeGet("/v1/sessions") ?: return emptyList()
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("data") ?: json.optJSONArray("sessions") ?: return emptyList()
            (0 until minOf(arr.length(), limit)).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                SessionSummary(
                    id = s.optString("id", s.optString("session_id", "")),
                    title = s.optString("title", "Untitled"),
                    status = s.optString("session_status", s.optString("status", "unknown")),
                    createdAt = s.optString("created_at", ""),
                    updatedAt = s.optString("updated_at", ""),
                    environmentId = s.optString("environment_id", ""),
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "SessionsAPI: failed to parse session list")
            emptyList()
        }
    }

    data class SessionSummary(
        val id: String,
        val title: String,
        val status: String,
        val createdAt: String,
        val updatedAt: String,
        val environmentId: String,
    )

    // ── Conversation History ────────────────────────────────────────

    /**
     * Fetch session events and convert to ChatBubble format for the UI.
     *
     * Maps Sessions API event types to bubble types:
     *   user    → "nigel" bubble
     *   assistant (text blocks) → "agent" bubble
     *   assistant (tool_use blocks) → "tool" bubble
     *   result  → skipped (metadata only)
     *
     * Returns bubbles in chronological order with synthetic sequence numbers.
     */
    fun fetchBubbles(sessionId: String): List<ChatBubbleData> {
        val events = getSessionEvents(sessionId) ?: return emptyList()
        val bubbles = mutableListOf<ChatBubbleData>()
        var seq = 0

        for (ev in events) {
            val type = ev.optString("type", "")
            val timestamp = ev.optString("created_at", "")

            when (type) {
                "user" -> {
                    val msg = ev.optJSONObject("message")
                    val content = msg?.optString("content", "")
                        ?: msg?.optJSONArray("content")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                val block = arr.optJSONObject(i)
                                if (block?.optString("type") == "text") block.optString("text") else null
                            }.joinToString("\n")
                        }
                        ?: ""
                    if (content.isNotBlank()) {
                        bubbles.add(ChatBubbleData("nigel", content, "", seq++, 0, timestamp))
                    }
                }

                "assistant" -> {
                    val msg = ev.optJSONObject("message")
                    val contentArr = msg?.optJSONArray("content")
                    if (contentArr != null) {
                        var subSeq = 0
                        for (i in 0 until contentArr.length()) {
                            val block = contentArr.optJSONObject(i) ?: continue
                            when (block.optString("type")) {
                                "text" -> {
                                    val text = block.optString("text", "")
                                    if (text.isNotBlank()) {
                                        bubbles.add(ChatBubbleData("agent", text, "", seq, subSeq++, timestamp))
                                    }
                                }
                                "tool_use" -> {
                                    val toolName = block.optString("name", "tool")
                                    bubbles.add(ChatBubbleData("tool", toolName, "completed", seq, subSeq++, timestamp))
                                }
                            }
                        }
                        seq++
                    }
                }
                // Skip: result, system, rate_limit_event, etc.
            }
        }
        return bubbles
    }

    data class ChatBubbleData(
        val type: String,
        val text: String,
        val detail: String,
        val sequence: Int,
        val subSeq: Int,
        val timestamp: String,
    )

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

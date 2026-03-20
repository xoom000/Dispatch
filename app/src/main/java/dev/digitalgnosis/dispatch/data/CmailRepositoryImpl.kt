package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [CmailRepository].
 *
 * All messages flow through the File Bridge relay on pop-os.
 * Injected via [CmailRepository] interface — never inject this class directly.
 */
@Singleton
class CmailRepositoryImpl @Inject constructor(
    private val client: BaseFileBridgeClient
) : CmailRepository {

    override fun sendCmail(
        department: String,
        message: String,
        subject: String?,
        priority: String,
        invoke: Boolean,
        threadId: String?,
        agentType: String?,
    ): Result<CmailSendResult> {
        Timber.i("Cmail: prepare send to %s (agent=%s, thread=%s)", department, agentType, threadId)
        val payload = JSONObject().apply {
            put("department", department)
            put("message", message)
            if (subject != null) put("subject", subject)
            put("priority", priority)
            put("invoke", invoke)
            if (threadId != null) put("thread_id", threadId)
            if (agentType != null) put("agent_type", agentType)
        }

        Timber.d("Cmail: sendCmail — posting to File Bridge")
        val body = client.post("cmail/send", payload.toString())

        if (body == null) {
            Timber.w("Cmail: send FAILED (network error or non-200 status)")
            return Result.failure(Exception("Network error"))
        }

        return try {
            val json = JSONObject(body)
            val result = CmailSendResult(
                success = true,
                message = "Sent to $department",
                invoked = json.optBoolean("invoked", false),
                department = json.optString("department", department).ifBlank { department },
                sessionId = if (json.isNull("session_id")) null else json.optString("session_id", "").ifBlank { null }
            )
            Timber.i("Cmail: sendCmail — success (dept=%s, invoked=%b, sessionId=%s)",
                result.department, result.invoked, result.sessionId?.take(8))
            Result.success(result)
        } catch (e: Exception) {
            Timber.e(e, "Cmail: sendCmail parse response failed")
            Result.failure(e)
        }
    }

    override fun sendCmailGroup(
        departments: List<String>,
        message: String,
        subject: String?,
        priority: String,
        invoke: Boolean,
        threadId: String?,
        agentType: String?,
    ): Result<CmailSendResult> {
        Timber.d("CmailRepo: sendCmailGroup — requesting (depts=%s, priority=%s, invoke=%b)",
            departments.joinToString(), priority, invoke)
        val payload = JSONObject().apply {
            put("departments", JSONArray(departments))
            put("message", message)
            if (subject != null) put("subject", subject)
            put("priority", priority)
            put("invoke", invoke)
            if (threadId != null) put("thread_id", threadId)
            if (agentType != null) put("agent_type", agentType)
        }

        val body = client.post("cmail/send", payload.toString())
            ?: return Result.failure(Exception("Network error"))

        return try {
            val json = JSONObject(body)
            val result = CmailSendResult(
                success = true,
                message = "Sent to ${departments.joinToString(", ")}",
                invoked = json.optBoolean("invoked", false),
                department = json.optString("department", departments.first()).ifBlank { departments.first() },
                sessionId = if (json.isNull("session_id")) null else json.optString("session_id", "").ifBlank { null }
            )
            Timber.d("CmailRepo: sendCmailGroup — success (%d depts, invoked=%b)", departments.size, result.invoked)
            Result.success(result)
        } catch (e: Exception) {
            Timber.e(e, "CmailRepo: sendCmailGroup parse failed (depts=%s)", departments.joinToString())
            Result.failure(e)
        }
    }

    override fun fetchDepartments(): List<DepartmentInfo> {
        Timber.d("CmailRepo: fetchDepartments — requesting")
        val body = client.get("cmail/departments") ?: return emptyList()

        return try {
            val json = JSONObject(body)
            val depts = json.optJSONArray("departments") ?: JSONArray()
            val result = mutableListOf<DepartmentInfo>()

            // Synthetic Gemini CLI contact
            result.add(DepartmentInfo(
                name = "Gemini CLI",
                description = "Digital Gnosis Project Architect",
            ))

            for (i in 0 until depts.length()) {
                val dept = depts.getJSONObject(i)
                if (dept.optBoolean("has_agent", false)) {
                    result.add(DepartmentInfo(
                        name = dept.getString("name"),
                        agentType = dept.optString("agent_type", null),
                        description = dept.optString("description", "").ifBlank { null },
                    ))
                }
            }
            Timber.d("CmailRepo: fetchDepartments — got %d departments", result.size)
            result
        } catch (e: Exception) {
            Timber.e(e, "CmailRepo: fetchDepartments parse failed")
            emptyList()
        }
    }

    override fun fetchThreads(
        limit: Int,
        offset: Int,
        participant: String?,
    ): CmailRepository.ThreadListResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (participant != null) append("&participant=$participant")
        }
        Timber.d("CmailRepo: fetchThreads — requesting (limit=%d, offset=%d, participant=%s)", limit, offset, participant)
        val body = client.get("cmail/threads?$params") ?: return CmailRepository.ThreadListResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("threads")
            val threads = mutableListOf<ThreadInfo>()

            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                threads.add(ThreadInfo(
                    threadId = t.getString("thread_id"),
                    subject = t.optString("subject", ""),
                    participants = parseStringArray(t.optJSONArray("participants")),
                    messageCount = t.optInt("total_messages", t.optInt("message_count", 0)),
                    createdAt = t.optString("created_at", ""),
                    lastActivity = t.optString("last_activity", ""),
                    lastSender = if (t.isNull("last_sender")) null else t.optString("last_sender", ""),
                    lastMessagePreview = if (t.isNull("last_message_preview")) null else t.optString("last_message_preview", ""),
                ))
            }
            val result = CmailRepository.ThreadListResult(threads, json.optInt("total", threads.size))
            Timber.d("CmailRepo: fetchThreads — got %d threads (total=%d)", result.threads.size, result.total)
            result
        } catch (e: Exception) {
            Timber.e(e, "CmailRepo: fetchThreads parse failed")
            CmailRepository.ThreadListResult(emptyList(), 0)
        }
    }

    override fun replyToThread(
        threadId: String,
        department: String,
        message: String,
        invoke: Boolean,
        agentType: String?,
    ): Result<CmailSendResult> {
        return sendCmail(
            department = department,
            message = message,
            threadId = threadId,
            invoke = invoke,
            agentType = agentType
        )
    }

    override fun fetchThreadDetail(threadId: String): ThreadDetail? {
        Timber.d("CmailRepo: fetchThreadDetail — requesting (thread=%s)", threadId.take(8))
        val body = client.get("cmail/threads/$threadId") ?: return null

        return try {
            val json = JSONObject(body)
            val msgArr = json.getJSONArray("messages")
            val messages = mutableListOf<ThreadMessage>()

            for (i in 0 until msgArr.length()) {
                val m = msgArr.getJSONObject(i)
                val rawId = m.optString("message_id", "")
                val sender = m.optString("sender", "unknown")
                val pos = m.optInt("position", i)

                // C3 Fix: If server provides blank ID, build a stable synthetic one
                val finalId = if (rawId.isBlank()) "gen_${sender}_${pos}_${threadId.take(8)}" else rawId

                messages.add(ThreadMessage(
                    messageId = finalId,
                    threadId = m.optString("thread_id", threadId),
                    position = pos,
                    sender = sender,
                    recipient = m.optString("recipient", ""),
                    cc = if (m.isNull("cc")) null else m.optString("cc", ""),
                    subject = m.optString("subject", ""),
                    body = m.optString("body", ""),
                    priority = m.optString("priority", "normal"),
                    delivery = m.optString("delivery", "direct"),
                    read = m.optInt("read", 0) == 1,
                    createdAt = m.optString("created_at", ""),
                    attachments = if (m.isNull("attachments")) null else m.optString("attachments", "")
                ))
            }

            // C3 Fix: Ensure uniqueness by messageId
            val dedupedMessages = messages.distinctBy { it.messageId }

            val detail = ThreadDetail(
                threadId = json.optString("thread_id", threadId),
                subject = json.optString("subject", ""),
                participants = parseStringArray(json.optJSONArray("participants")),
                messageCount = json.optInt("message_count", dedupedMessages.size),
                createdAt = json.optString("created_at", ""),
                lastActivity = json.optString("last_activity", ""),
                messages = dedupedMessages
            )
            Timber.d("CmailRepo: fetchThreadDetail — got %d messages for thread %s",
                detail.messages.size, threadId.take(8))
            detail
        } catch (e: Exception) {
            Timber.e(e, "CmailRepo: fetchThreadDetail parse failed for %s", threadId.take(8))
            null
        }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            result.add(arr.getString(i))
        }
        return result
    }
}

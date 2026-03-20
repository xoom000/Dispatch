package dev.digitalgnosis.dispatch.data

/**
 * Domain interface for Cmail messaging, threading, and department discovery.
 */
interface CmailRepository {

    fun sendCmail(
        department: String,
        message: String,
        subject: String? = null,
        priority: String = "normal",
        invoke: Boolean = true,
        threadId: String? = null,
        agentType: String? = null,
    ): Result<CmailSendResult>

    fun sendCmailGroup(
        departments: List<String>,
        message: String,
        subject: String? = null,
        priority: String = "normal",
        invoke: Boolean = true,
        threadId: String? = null,
        agentType: String? = null,
    ): Result<CmailSendResult>

    fun fetchDepartments(): List<DepartmentInfo>

    fun fetchThreads(
        limit: Int = 30,
        offset: Int = 0,
        participant: String? = null,
    ): ThreadListResult

    fun replyToThread(
        threadId: String,
        department: String,
        message: String,
        invoke: Boolean = true,
        agentType: String? = null,
    ): Result<CmailSendResult>

    fun fetchThreadDetail(threadId: String): ThreadDetail?

    data class ThreadListResult(
        val threads: List<ThreadInfo>,
        val total: Int,
    )
}

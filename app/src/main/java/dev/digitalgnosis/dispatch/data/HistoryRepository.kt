package dev.digitalgnosis.dispatch.data

/**
 * Domain interface for Dispatch message history.
 */
interface HistoryRepository {

    fun fetchDispatchHistory(
        sender: String? = null,
        search: String? = null,
        priority: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): HistoryResult

    data class HistoryResult(val messages: List<HistoryMessage>, val total: Int)
}

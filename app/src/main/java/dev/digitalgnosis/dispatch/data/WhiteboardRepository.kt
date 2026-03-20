package dev.digitalgnosis.dispatch.data

/**
 * Domain interface for the shared project Whiteboard.
 */
interface WhiteboardRepository {

    fun fetchWhiteboard(): Whiteboard
}

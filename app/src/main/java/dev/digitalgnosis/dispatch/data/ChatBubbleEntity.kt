package dev.digitalgnosis.dispatch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity for chat bubbles — local cache of session conversation data.
 *
 * Each bubble is one visual element in the conversation view:
 * - "nigel": Nigel's typed messages
 * - "agent": Claude's conversational text
 * - "dispatch": Dispatch audio payloads (what Nigel hears)
 * - "tool": Tool use (collapsed, shows tool name)
 *
 * Composite primary key: (sessionId, sequence, subSeq).
 * sequence is per-JSONL-record; subSeq is 0-based within that record.
 * Together they uniquely identify a bubble — an assistant record can
 * produce multiple bubbles (text block + tool_use blocks), all sharing
 * the same sequence but different subSeq values.
 *
 * This is a cache. Destructive migration is acceptable — the data
 * also lives on pop-os in the JSONL session files.
 */
@Entity(
    tableName = "chat_bubbles",
    primaryKeys = ["session_id", "sequence", "sub_seq"],
    indices = [
        Index("session_id"),
        Index("session_id", "sequence"),
    ],
)
data class ChatBubbleEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "sequence") val sequence: Int,
    @ColumnInfo(name = "sub_seq") val subSeq: Int = 0,    // 0-based within JSONL record
    @ColumnInfo(name = "type") val type: String,          // nigel, agent, dispatch, tool
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "detail") val detail: String = "",  // tool status for "tool" type
    @ColumnInfo(name = "timestamp") val timestamp: String = "",
)

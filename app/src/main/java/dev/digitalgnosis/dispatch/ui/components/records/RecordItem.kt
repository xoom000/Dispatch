package dev.digitalgnosis.dispatch.ui.components.records

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.digitalgnosis.dispatch.data.SessionRecord

@Composable
fun RecordItem(
    modifier: Modifier = Modifier,
    record: SessionRecord,
) {
    when (record.recordType) {
        "user" -> UserRecordBubble(record = record)
        "assistant" -> AssistantRecordBubble(record = record)
        "system" -> SystemRecord(record = record)
        "summary" -> SystemRecord(record = record)
        "queue-operation" -> SystemRecord(record = record)
        else -> {
            // Skip unknown types silently
        }
    }
}

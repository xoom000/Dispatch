package dev.digitalgnosis.dispatch.ui.components.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.SessionRecord
import dev.digitalgnosis.dispatch.util.formatModelName

@Composable
fun AssistantRecordBubble(
    modifier: Modifier = Modifier,
    record: SessionRecord,
) {
    val text = record.contentText ?: return
    if (text.isBlank()) return

    // Tool calls show as compact chips
    if (record.toolName != null) {
        ToolCallChip(record = record)
        return
    }

    // Assistant response: right-aligned
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Agent",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!record.model.isNullOrBlank()) {
                        Text(
                            text = formatModelName(record.model),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text.take(2000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

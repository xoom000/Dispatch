package dev.digitalgnosis.dispatch.ui.components.records

import androidx.compose.foundation.layout.Column
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

@Composable
fun UserRecordBubble(
    modifier: Modifier = Modifier,
    record: SessionRecord,
) {
    val text = record.contentText ?: return
    if (text.isBlank()) return

    // Tool results show as compact chips
    if (record.toolName != null) {
        ToolResultChip(record = record)
        return
    }

    // User prompt: left-aligned
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 48.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "Nigel",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
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

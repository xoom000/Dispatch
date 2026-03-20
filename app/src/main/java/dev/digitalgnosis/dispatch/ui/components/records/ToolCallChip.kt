package dev.digitalgnosis.dispatch.ui.components.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.SessionRecord

@Composable
fun ToolCallChip(
    modifier: Modifier = Modifier,
    record: SessionRecord,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = buildString {
                        append(record.toolName ?: "tool")
                        if (!record.toolInput.isNullOrBlank()) {
                            append(": ")
                            append(record.toolInput.take(60))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            },
            modifier = Modifier.height(28.dp),
        )
    }
}

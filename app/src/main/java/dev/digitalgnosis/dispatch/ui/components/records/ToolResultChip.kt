package dev.digitalgnosis.dispatch.ui.components.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.SessionRecord
import dev.digitalgnosis.dispatch.ui.theme.DgStatusActive

@Composable
fun ToolResultChip(
    modifier: Modifier = Modifier,
    record: SessionRecord,
) {
    val isSuccess = record.toolStatus == "success"
    val isError = record.isError

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
                        append(record.toolName ?: "result")
                        if (isError) append(" FAILED")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (isError) Icons.Default.Close else Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isError) MaterialTheme.colorScheme.error
                    else DgStatusActive,
                )
            },
            modifier = Modifier.height(28.dp),
        )
    }
}

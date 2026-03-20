package dev.digitalgnosis.dispatch.ui.components.records

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.SessionRecord

@Composable
fun SystemRecord(
    modifier: Modifier = Modifier,
    record: SessionRecord,
) {
    val text = record.contentText ?: return
    if (text.isBlank()) return

    Text(
        text = text.take(200),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 2.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

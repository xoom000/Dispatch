package dev.digitalgnosis.dispatch.ui.components.cards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.HistoryMessage
import dev.digitalgnosis.dispatch.ui.viewmodels.HistoryViewModel
import dev.digitalgnosis.dispatch.util.formatSmartTimestamp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryCard(
    modifier: Modifier = Modifier,
    message: HistoryMessage,
    viewModel: HistoryViewModel,
) {
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (!message.success)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("dispatch", message.message))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
                onLongClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("dispatch", "${message.sender}: ${message.message}")
                    )
                    Toast.makeText(context, "Copied with sender", Toast.LENGTH_SHORT).show()
                },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: sender + time + replay
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.sender,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Replay button
                    IconButton(
                        onClick = {
                            viewModel.replayMessage(message)
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Replay",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    // Failed indicator
                    if (!message.success) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }

                    Text(
                        text = formatSmartTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Message body
            Text(
                text = message.message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )

            // Error detail
            if (!message.success && !message.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Priority badge (only if not normal)
            if (message.priority != "normal") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.priority.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (message.priority == "urgent")
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

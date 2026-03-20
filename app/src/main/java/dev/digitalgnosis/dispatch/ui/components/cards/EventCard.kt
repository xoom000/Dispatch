package dev.digitalgnosis.dispatch.ui.components.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.OrchestratorEvent
import dev.digitalgnosis.dispatch.ui.theme.DgChannelActivity
import dev.digitalgnosis.dispatch.ui.theme.DgChannelCmail
import dev.digitalgnosis.dispatch.ui.theme.DgDeptBoardroom
import dev.digitalgnosis.dispatch.ui.theme.DgDeptDispatch
import dev.digitalgnosis.dispatch.ui.theme.DgDeptEngineering
import dev.digitalgnosis.dispatch.ui.theme.DgDeptIT
import dev.digitalgnosis.dispatch.ui.theme.DgStatusActive
import dev.digitalgnosis.dispatch.ui.theme.DgStatusErrorDark
import dev.digitalgnosis.dispatch.ui.theme.DgStatusNeutral
import dev.digitalgnosis.dispatch.ui.theme.DgStatusWarning
import dev.digitalgnosis.dispatch.util.formatCompactTime

@Composable
fun EventCard(
    modifier: Modifier = Modifier,
    event: OrchestratorEvent,
) {
    val (icon, accentColor) = eventVisuals(event.eventType)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Event type icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .padding(top = 2.dp),
                tint = accentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Department + event type label
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = event.department.ifBlank { "system" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                    Text(
                        text = eventLabel(event.eventType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }

                // Summary
                if (event.summary.isNotBlank()) {
                    Text(
                        text = event.summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Timestamp
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatCompactTime(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

fun eventVisuals(eventType: String): Pair<ImageVector, Color> = when (eventType) {
    "tool_used" -> Icons.Default.Build to DgStatusActive
    "tool_failed" -> Icons.Default.Close to DgStatusErrorDark
    "session_ended" -> Icons.Default.PowerSettingsNew to DgStatusWarning
    "session_compacting" -> Icons.Default.Compress to DgDeptEngineering
    "agent_idle" -> Icons.Default.HourglassEmpty to DgDeptBoardroom
    "dispatch_message" -> Icons.Default.FiberManualRecord to DgChannelActivity
    "cmail_message", "cmail_reply" -> Icons.Default.FiberManualRecord to DgChannelCmail
    "session_started" -> Icons.Default.FiberManualRecord to DgDeptDispatch
    "session_completed" -> Icons.Default.FiberManualRecord to DgDeptIT
    else -> Icons.Default.FiberManualRecord to DgStatusNeutral
}

fun eventLabel(eventType: String): String = when (eventType) {
    "tool_used" -> "tool"
    "tool_failed" -> "FAILED"
    "session_ended" -> "ended"
    "session_compacting" -> "compacting"
    "agent_idle" -> "idle"
    "dispatch_message" -> "dispatch"
    "cmail_message" -> "cmail"
    "cmail_reply" -> "reply"
    "session_started" -> "started"
    "session_completed" -> "completed"
    else -> eventType
}

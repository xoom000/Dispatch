package dev.digitalgnosis.dispatch.ui.components.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.SessionInfo
import dev.digitalgnosis.dispatch.ui.theme.DgStatusActive
import dev.digitalgnosis.dispatch.ui.theme.DgStatusErrorDark
import dev.digitalgnosis.dispatch.ui.theme.DgStatusWarning
import dev.digitalgnosis.dispatch.util.formatActivityTime
import dev.digitalgnosis.dispatch.util.formatModelName

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionCard(
    modifier: Modifier = Modifier,
    session: SessionInfo,
    onClick: () -> Unit,
    runningCommand: String? = null,
    onCommand: ((String) -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Department + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.department,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (session.status == "active") {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = "Live",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                modifier = Modifier.size(8.dp),
                                tint = DgStatusActive,
                            )
                        },
                        modifier = Modifier.height(28.dp),
                    )
                } else {
                    Text(
                        text = "${session.recordCount} rec",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Summary
            if (!session.summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = session.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Context bar (only for sessions with context data)
            if (session.contextPct > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Progress bar
                    val pct = session.contextPct.toFloat().coerceIn(0f, 100f)
                    val barColor = when {
                        pct >= 80f -> DgStatusErrorDark
                        pct >= 60f -> DgStatusWarning
                        else -> DgStatusActive
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .then(
                                    Modifier.padding(0.dp)
                                ),
                        ) {
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // Track
                                drawRoundRect(
                                    color = barColor.copy(alpha = 0.2f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                                )
                                // Fill
                                drawRoundRect(
                                    color = barColor,
                                    size = size.copy(width = size.width * pct / 100f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                                )
                            }
                        }
                    }
                    Text(
                        text = "ctx: ${session.contextPct.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = barColor,
                    )
                }
            }

            // Command buttons (shown for all completed sessions, hidden for active/live)
            if (runningCommand != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = runningCommand.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (onCommand != null && session.status != "active") {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onCommand("compact") },
                        modifier = Modifier.height(28.dp),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Compress,
                            contentDescription = "Compact",
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "Compact",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(
                        onClick = { onCommand("cost") },
                        modifier = Modifier.height(28.dp),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text(
                            text = "Cost",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(
                        onClick = { onCommand("context") },
                        modifier = Modifier.height(28.dp),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Text(
                            text = "Context",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // Model + branch info
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!session.model.isNullOrBlank()) {
                    Text(
                        text = formatModelName(session.model),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (!session.gitBranch.isNullOrBlank()) {
                    Text(
                        text = session.gitBranch,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // Timestamp
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatActivityTime(session.lastActivity),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

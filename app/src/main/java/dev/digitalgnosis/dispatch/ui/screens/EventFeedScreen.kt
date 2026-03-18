package dev.digitalgnosis.dispatch.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.data.OrchestratorEvent
import dev.digitalgnosis.dispatch.ui.viewmodels.AgentsViewModel

/**
 * Event type filter definition — label, color, and the API event_type value.
 */
private data class EventFilter(
    val label: String,
    val eventType: String?,  // null = "All"
    val color: Color,
)

private val EVENT_FILTERS = listOf(
    EventFilter("All", null, Color(0xFF9E9E9E)),
    EventFilter("Ended", "session_ended", Color(0xFFFFA726)),
    EventFilter("Failed", "tool_failed", Color(0xFFE53935)),
    EventFilter("Idle", "agent_idle", Color(0xFFAB47BC)),
    EventFilter("Compact", "session_compacting", Color(0xFF42A5F5)),
    EventFilter("Tools", "tool_used", Color(0xFF4CAF50)),
    EventFilter("Dispatch", "dispatch_message", Color(0xFF26A69A)),
    EventFilter("Cmail", "cmail_message", Color(0xFF5C6BC0)),
)

/**
 * Live event feed -- real-time flight recorder telemetry from all agents.
 * Shows tool calls, failures, session death, compaction, and idle events.
 * Filter chips let you slice by event type to find rare signals in the noise.
 */
@Composable
fun EventFeedScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentsViewModel = hiltViewModel(),
    refreshSignal: Int = 0,
) {
    val events by viewModel.events.collectAsState()
    val total by viewModel.totalEvents.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    // Auto-refresh when SSE signal increments
    LaunchedEffect(refreshSignal) {
        if (refreshSignal > 0) viewModel.refreshEvents()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (total > 0) "$total events" else "Events",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { viewModel.refreshEvents() }, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EVENT_FILTERS.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter.eventType,
                    onClick = {
                        selectedFilter = if (selectedFilter == filter.eventType) null else filter.eventType
                        viewModel.setEventFilter(selectedFilter)
                    },
                    label = { Text(filter.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = filter.color.copy(alpha = 0.15f),
                    ),
                )
            }
        }

        if (events.isEmpty() && !loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (selectedFilter != null) "No matching events" else "No events yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (selectedFilter != null) "Try a different filter" else "Flight recorder events will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    EventCard(event = event)
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: OrchestratorEvent) {
    val (icon, accentColor) = eventVisuals(event.eventType)

    Card(
        modifier = Modifier
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
                text = formatEventTime(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * Map event type to icon and accent color.
 */
private fun eventVisuals(eventType: String): Pair<ImageVector, Color> {
    return when (eventType) {
        "tool_used" -> Icons.Default.Build to Color(0xFF4CAF50)          // Green
        "tool_failed" -> Icons.Default.Close to Color(0xFFE53935)        // Red
        "session_ended" -> Icons.Default.PowerSettingsNew to Color(0xFFFFA726) // Orange
        "session_compacting" -> Icons.Default.Compress to Color(0xFF42A5F5) // Blue
        "agent_idle" -> Icons.Default.HourglassEmpty to Color(0xFFAB47BC)  // Purple
        "dispatch_message" -> Icons.Default.FiberManualRecord to Color(0xFF26A69A) // Teal
        "cmail_message", "cmail_reply" -> Icons.Default.FiberManualRecord to Color(0xFF5C6BC0) // Indigo
        "session_started" -> Icons.Default.FiberManualRecord to Color(0xFF66BB6A) // Light green
        "session_completed" -> Icons.Default.FiberManualRecord to Color(0xFF78909C) // Blue grey
        else -> Icons.Default.FiberManualRecord to Color(0xFF9E9E9E)     // Grey
    }
}

/**
 * Human-readable label for event types.
 */
private fun eventLabel(eventType: String): String {
    return when (eventType) {
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
}

/**
 * Compact time display for event timestamps.
 */
private fun formatEventTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = sdf.parse(iso.substringBefore('.').substringBefore('Z')) ?: return ""

        val now = System.currentTimeMillis()
        val diff = now - date.time
        val minutes = diff / (1000 * 60)
        val hours = diff / (1000 * 60 * 60)

        when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            else -> {
                val display = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                display.timeZone = java.util.TimeZone.getDefault()
                display.format(date)
            }
        }
    } catch (_: Exception) {
        ""
    }
}

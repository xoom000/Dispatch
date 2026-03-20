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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.components.cards.EventCard
import dev.digitalgnosis.dispatch.ui.theme.DgChannelActivity
import dev.digitalgnosis.dispatch.ui.theme.DgChannelCmail
import dev.digitalgnosis.dispatch.ui.theme.DgDeptBoardroom
import dev.digitalgnosis.dispatch.ui.theme.DgDeptEngineering
import dev.digitalgnosis.dispatch.ui.theme.DgStatusActive
import dev.digitalgnosis.dispatch.ui.theme.DgStatusErrorDark
import dev.digitalgnosis.dispatch.ui.theme.DgStatusNeutral
import dev.digitalgnosis.dispatch.ui.theme.DgStatusWarning
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
    EventFilter("All", null, DgStatusNeutral),
    EventFilter("Ended", "session_ended", DgStatusWarning),
    EventFilter("Failed", "tool_failed", DgStatusErrorDark),
    EventFilter("Idle", "agent_idle", DgDeptBoardroom),
    EventFilter("Compact", "session_compacting", DgDeptEngineering),
    EventFilter("Tools", "tool_used", DgStatusActive),
    EventFilter("Dispatch", "dispatch_message", DgChannelActivity),
    EventFilter("Cmail", "cmail_message", DgChannelCmail),
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

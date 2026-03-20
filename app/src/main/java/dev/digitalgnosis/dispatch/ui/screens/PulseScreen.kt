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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.components.cards.PulsePostCard
import dev.digitalgnosis.dispatch.ui.components.cards.pulseChannelColor
import dev.digitalgnosis.dispatch.ui.viewmodels.PulseViewModel

/**
 * Pulse screen — company-wide activity broadcast feed.
 * Shows posts across all channels with channel filtering and tag badges.
 */
@Composable
fun PulseScreen(
    modifier: Modifier = Modifier,
    viewModel: PulseViewModel = hiltViewModel(),
    refreshSignal: Int = 0,
) {
    val posts by viewModel.posts.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val total by viewModel.totalPosts.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    var selectedChannel by remember { mutableStateOf<String?>(null) }

    // Auto-refresh on SSE signal
    LaunchedEffect(refreshSignal) {
        if (refreshSignal > 0) viewModel.refreshPosts()
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
                text = if (total > 0) "$total posts" else "Pulse",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { viewModel.refreshAll() }, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Channel filter chips
        if (channels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // "All" chip
                FilterChip(
                    selected = selectedChannel == null,
                    onClick = {
                        selectedChannel = null
                        viewModel.setChannel(null)
                    },
                    label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ),
                )
                channels.forEach { ch ->
                    FilterChip(
                        selected = selectedChannel == ch.name,
                        onClick = {
                            selectedChannel = if (selectedChannel == ch.name) null else ch.name
                            viewModel.setChannel(selectedChannel)
                        },
                        label = { Text("#${ch.name}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = pulseChannelColor(ch.name).copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        }

        // Content
        if (posts.isEmpty() && !loading) {
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
                        text = "No activity yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Pulse broadcasts will appear here",
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
                items(posts, key = { "${it.ts}_${it.dept}_${it.channel}" }) { post ->
                    PulsePostCard(post = post, showChannel = selectedChannel == null)
                }
            }
        }
    }
}

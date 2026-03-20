package dev.digitalgnosis.dispatch.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.components.cards.TaskCard
import dev.digitalgnosis.dispatch.ui.components.cards.taskStatusColor
import dev.digitalgnosis.dispatch.ui.viewmodels.BoardViewModel
import timber.log.Timber

@Composable
fun BoardScreen(
    modifier: Modifier = Modifier,
    viewModel: BoardViewModel = hiltViewModel(),
    whiteboardRefresh: Int,
    onNavigateToThread: ((String) -> Unit)? = null,
) {
    val board by viewModel.board.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Real-time refresh when whiteboard_update SSE event fires
    LaunchedEffect(whiteboardRefresh) {
        if (whiteboardRefresh > 0) {
            Timber.i("BoardScreen: SSE whiteboard_update received, refreshing")
            viewModel.refreshBoard()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Board",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (board != null) {
                    val active = board!!.tasks.count { it.status == "active" }
                    val blocked = board!!.tasks.count { it.status == "blocked" }
                    Text(
                        text = buildString {
                            append("$active active")
                            if (blocked > 0) append(" · $blocked blocked")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            IconButton(onClick = { viewModel.refreshBoard() }, enabled = !loading) {
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

        if (board == null && loading) {
            // Initial loading
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (error != null && board == null) {
            // Error state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (board != null) {
            val tasks = board!!.tasks
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Board is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Group tasks: active first, blocked second, parked third, done last (collapsed)
                val active = tasks.filter { it.status == "active" }
                val blocked = tasks.filter { it.status == "blocked" }
                val parked = tasks.filter { it.status == "parked" }
                val done = tasks.filter { it.status == "done" }

                var showDone by remember { mutableStateOf(false) }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Active tasks
                    if (active.isNotEmpty()) {
                        item {
                            SectionHeader("Active", active.size, taskStatusColor("active"))
                        }
                        items(active, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onTapThread = onNavigateToThread,
                            )
                        }
                    }

                    // Blocked tasks
                    if (blocked.isNotEmpty()) {
                        item {
                            SectionHeader("Blocked", blocked.size, taskStatusColor("blocked"))
                        }
                        items(blocked, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onTapThread = onNavigateToThread,
                            )
                        }
                    }

                    // Parked tasks
                    if (parked.isNotEmpty()) {
                        item {
                            SectionHeader("Parked", parked.size, taskStatusColor("parked"))
                        }
                        items(parked, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onTapThread = onNavigateToThread,
                            )
                        }
                    }

                    // Done tasks (collapsed)
                    if (done.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showDone = !showDone }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (showDone) "Done ▾" else "Done ▸",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = taskStatusColor("done"),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${done.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                        if (showDone) {
                            items(done, key = { it.id }) { task ->
                                TaskCard(
                                    task = task,
                                    onTapThread = onNavigateToThread,
                                    dimmed = true,
                                )
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// ─── Components ──────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

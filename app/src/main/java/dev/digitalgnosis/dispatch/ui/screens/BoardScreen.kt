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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.Whiteboard
import dev.digitalgnosis.dispatch.data.WhiteboardTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.viewmodels.BoardViewModel

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
                            SectionHeader("Active", active.size, statusColor("active"))
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
                            SectionHeader("Blocked", blocked.size, statusColor("blocked"))
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
                            SectionHeader("Parked", parked.size, statusColor("parked"))
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
                                    color = statusColor("done"),
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

@Composable
private fun TaskCard(
    task: WhiteboardTask,
    onTapThread: ((String) -> Unit)? = null,
    dimmed: Boolean = false,
) {
    val alpha = if (dimmed) 0.5f else 1f
    val hasThread = task.threadId != null && onTapThread != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .then(
                if (hasThread) Modifier.clickable { onTapThread?.invoke(task.threadId!!) }
                else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha * 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Status icon
            Icon(
                imageVector = statusIcon(task.status),
                contentDescription = task.status,
                tint = statusColor(task.status).copy(alpha = alpha),
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 2.dp),
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title row with priority dot
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.priority == "high") {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFF5252),
                            modifier = Modifier.size(6.dp),
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (dimmed) FontWeight.Normal else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Assignee badge + thread indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssigneeBadge(task.assignee, alpha)

                    if (hasThread) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = "Has thread",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.7f),
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    // Updated time
                    val age = formatTaskAge(task.updated)
                    if (age.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = age,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.5f),
                        )
                    }
                }

                // Note
                if (!task.note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = task.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssigneeBadge(assignee: String, alpha: Float) {
    val color = assigneeColor(assignee)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = alpha * 0.2f),
    ) {
        Text(
            text = assignee,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = alpha),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ─── Styling ─────────────────────────────────────────────────────────────────

private fun statusColor(status: String): Color = when (status) {
    "active" -> Color(0xFF4CAF50)    // Green
    "blocked" -> Color(0xFFFF5252)   // Red
    "done" -> Color(0xFF9E9E9E)      // Gray
    "parked" -> Color(0xFFFFCA28)    // Yellow
    else -> Color(0xFF9E9E9E)
}

private fun statusIcon(status: String) = when (status) {
    "active" -> Icons.Default.Circle
    "blocked" -> Icons.Default.Warning
    "done" -> Icons.Default.CheckCircle
    "parked" -> Icons.Default.PauseCircle
    else -> Icons.Default.Circle
}

private fun assigneeColor(assignee: String): Color = when (assignee) {
    "engineering" -> Color(0xFF42A5F5)
    "dispatch" -> Color(0xFF66BB6A)
    "boardroom", "ceo" -> Color(0xFFAB47BC)
    "hunter" -> Color(0xFFFF7043)
    "research" -> Color(0xFF26C6DA)
    "it" -> Color(0xFF78909C)
    "nigel" -> Color(0xFFFFCA28)
    else -> Color(0xFF90A4AE)
}

/** Parse ISO timestamp and return human-readable age. */
private fun formatTaskAge(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = sdf.parse(iso) ?: return ""
        val ageMs = System.currentTimeMillis() - date.time
        val minutes = ageMs / (1000 * 60)
        val hours = ageMs / (1000 * 60 * 60)
        val days = ageMs / (1000 * 60 * 60 * 24)
        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            hours < 48 -> "yesterday"
            else -> "${days}d ago"
        }
    } catch (_: Exception) { "" }
}

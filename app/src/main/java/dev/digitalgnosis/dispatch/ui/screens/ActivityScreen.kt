package dev.digitalgnosis.dispatch.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.SessionDetail
import dev.digitalgnosis.dispatch.data.SessionInfo
import dev.digitalgnosis.dispatch.data.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.viewmodels.AgentsViewModel
import dev.digitalgnosis.dispatch.ui.viewmodels.SessionDetailViewModel

import androidx.activity.compose.BackHandler

/**
 * Agents tab -- combines Sessions and Events views with a segmented toggle.
 */
@Composable
fun AgentsScreen(
    modifier: Modifier = Modifier,
    eventRefreshSignal: Int = 0,
) {
    var selectedSegment by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        // Segmented button toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = selectedSegment == 0,
                    onClick = { selectedSegment = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("Sessions")
                }
                SegmentedButton(
                    selected = selectedSegment == 1,
                    onClick = { selectedSegment = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("Events")
                }
            }
        }

        when (selectedSegment) {
            0 -> SessionsRoot(
                modifier = Modifier,
            )
            1 -> EventFeedScreen(
                modifier = Modifier,
                refreshSignal = eventRefreshSignal,
            )
        }
    }
}

// ---- Session Navigation (list -> detail) ----

@Composable
private fun SessionsRoot(
    modifier: Modifier = Modifier,
) {
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRecordCount by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = selectedSessionId != null) {
        selectedSessionId = null
    }

    if (selectedSessionId != null) {
        SessionDetailScreen(
            modifier = modifier,
            sessionId = selectedSessionId!!,
            initialRecordCount = selectedRecordCount,
            onBack = { 
                selectedSessionId = null
            },
        )
    } else {
        SessionListScreen(
            modifier = modifier,
            onSessionSelected = { info ->
                selectedSessionId = info.sessionId
                selectedRecordCount = info.recordCount
            },
        )
    }
}

// ---- Session List ----

@Composable
private fun SessionListScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentsViewModel = hiltViewModel(),
    onSessionSelected: (SessionInfo) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsState()
    val total by viewModel.totalSessions.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    
    // Track which sessions have a command running: sessionId -> command name
    var runningCommands by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (total > 0) "$total sessions" else "Sessions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { viewModel.refreshSessions() }, enabled = !loading) {
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

        if (sessions.isEmpty() && !loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No sessions yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Start the session pipeline to capture agent activity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onSessionSelected(session) },
                        runningCommand = runningCommands[session.sessionId],
                        onCommand = { cmd -> 
                            viewModel.sendSessionCommand(session.sessionId, cmd)
                        },
                    )
                }
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    session: SessionInfo,
    onClick: () -> Unit,
    runningCommand: String? = null,
    onCommand: ((String) -> Unit)? = null,
) {
    Card(
        modifier = Modifier
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
                                tint = Color(0xFF4CAF50),
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
                        pct >= 80f -> Color(0xFFE53935) // Red
                        pct >= 60f -> Color(0xFFFFA726) // Orange
                        else -> Color(0xFF4CAF50) // Green
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

// ---- Session Detail ----

@Composable
private fun SessionDetailScreen(
    modifier: Modifier = Modifier,
    sessionId: String,
    initialRecordCount: Int,
    viewModel: SessionDetailViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val sessionDetail by viewModel.sessionDetail.collectAsState()
    val records by viewModel.records.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) {
        viewModel.loadSession(sessionId)
        viewModel.startLiveWatching(sessionId)
    }

    // Auto-scroll: instant on first load, animated on updates (only if near bottom)
    LaunchedEffect(records.size) {
        if (records.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems <= 1 || lastVisible >= totalItems - 3) {
            listState.animateScrollToItem(records.size - 1)
        }
    }

    // Derived state: show jump-to-bottom button when not near bottom
    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 5 && lastVisible < totalItems - 2
        }
    }

    val isLive = sessionDetail?.session?.status == "active"

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onBack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sessionDetail?.session?.department ?: "Session",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sessionDetail?.session?.summary != null) {
                    Text(
                        text = sessionDetail?.session?.summary!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isLive) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(text = "Live", style = MaterialTheme.typography.labelSmall)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(8.dp),
                            tint = Color(0xFF4CAF50),
                        )
                    },
                    modifier = Modifier.height(28.dp).padding(end = 8.dp),
                )
            } else if (!loading && sessionDetail != null) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(text = "Completed", style = MaterialTheme.typography.labelSmall)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.height(28.dp).padding(end = 8.dp),
                )
            }
            IconButton(onClick = { viewModel.loadSession(sessionId) }, enabled = !loading) {
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

        // Debug bar
        if (records.isNotEmpty()) {
            val errorCount = records.count { it.isError }
            val toolCount = records.count { it.toolName != null }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${records.size} records",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$toolCount tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$errorCount errors",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (errorCount > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        if (loading && records.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        records,
                        key = { "${it.agentId}-${it.sequence}" },
                    ) { record ->
                        RecordItem(record = record)
                    }
                    // Live indicator at bottom when session is active
                    if (isLive) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF4CAF50),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Agent working...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // Jump-to-bottom FAB
                if (showScrollToBottom) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(records.size - 1)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Jump to bottom",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

private const val INITIAL_LOAD_SIZE = 200
private const val LOAD_MORE_SIZE = 200

@Composable
internal fun RecordItem(record: SessionRecord) {
    when (record.recordType) {
        "user" -> UserRecordBubble(record)
        "assistant" -> AssistantRecordBubble(record)
        "system" -> SystemRecord(record)
        "summary" -> SystemRecord(record)
        "queue-operation" -> SystemRecord(record)
        else -> {
            // Skip unknown types silently
        }
    }
}

@Composable
private fun UserRecordBubble(record: SessionRecord) {
    val text = record.contentText ?: return
    if (text.isBlank()) return

    // Tool results show as compact chips
    if (record.toolName != null) {
        ToolResultChip(record)
        return
    }

    // User prompt: left-aligned
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 48.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "Nigel",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text.take(2000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AssistantRecordBubble(record: SessionRecord) {
    val text = record.contentText ?: return
    if (text.isBlank()) return

    // Tool calls show as compact chips
    if (record.toolName != null) {
        ToolCallChip(record)
        return
    }

    // Assistant response: right-aligned
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Agent",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!record.model.isNullOrBlank()) {
                        Text(
                            text = formatModelName(record.model),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text.take(2000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCallChip(record: SessionRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = buildString {
                        append(record.toolName ?: "tool")
                        if (!record.toolInput.isNullOrBlank()) {
                            append(": ")
                            append(record.toolInput.take(60))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            },
            modifier = Modifier.height(28.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolResultChip(record: SessionRecord) {
    val isSuccess = record.toolStatus == "success"
    val isError = record.isError

    Row(
        modifier = Modifier
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
                    else Color(0xFF4CAF50),
                )
            },
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
private fun SystemRecord(record: SessionRecord) {
    val text = record.contentText ?: return
    if (text.isBlank()) return

    Text(
        text = text.take(200),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 2.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---- Utilities ----

internal fun formatModelName(model: String): String {
    return when {
        model.contains("opus-4-6") -> "Opus 4.6"
        model.contains("opus-4-5") -> "Opus 4.5"
        model.contains("sonnet-4-6") -> "Sonnet 4.6"
        model.contains("sonnet-4-5") -> "Sonnet 4.5"
        model.contains("haiku-4-5") -> "Haiku 4.5"
        model.contains("opus") -> "Opus"
        model.contains("sonnet") -> "Sonnet"
        model.contains("haiku") -> "Haiku"
        else -> model.take(20)
    }
}

internal fun formatActivityTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = sdf.parse(iso) ?: return iso

        val now = System.currentTimeMillis()
        val diff = now - date.time
        val hours = diff / (1000 * 60 * 60)
        val minutes = diff / (1000 * 60)

        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            hours < 48 -> "yesterday"
            else -> {
                val display = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                display.format(date)
            }
        }
    } catch (_: Exception) {
        iso
    }
}

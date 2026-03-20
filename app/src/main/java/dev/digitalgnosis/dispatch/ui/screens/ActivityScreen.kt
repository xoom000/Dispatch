package dev.digitalgnosis.dispatch.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import dev.digitalgnosis.dispatch.ui.theme.DgStatusActive
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.SessionInfo
import kotlinx.coroutines.launch

import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.viewmodels.AgentsViewModel
import dev.digitalgnosis.dispatch.ui.viewmodels.SessionDetailViewModel

import androidx.activity.compose.BackHandler
import dev.digitalgnosis.dispatch.ui.components.sessions.SessionCard
import dev.digitalgnosis.dispatch.ui.components.records.RecordItem

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
                            tint = DgStatusActive,
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
                                    color = DgStatusActive,
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


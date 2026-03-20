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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import dev.digitalgnosis.dispatch.ui.theme.DgStatusActive
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.components.records.RecordItem
import dev.digitalgnosis.dispatch.ui.viewmodels.LiveSessionViewModel
import dev.digitalgnosis.dispatch.util.formatModelName

/**
 * Live session watching screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSessionScreen(
    department: String,
    invokedAt: Long,
    sessionId: String? = null,
    viewModel: LiveSessionViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val sessionInfo by viewModel.sessionInfo.collectAsState()
    val records by viewModel.records.collectAsState()
    val discovering by viewModel.discovering.collectAsState()
    val discoveryError by viewModel.discoveryError.collectAsState()
    val pollError by viewModel.pollError.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(department, invokedAt, sessionId) {
        viewModel.startDiscovery(department, invokedAt, sessionId)
    }

    // Auto-scroll when new records arrive
    LaunchedEffect(records.size) {
        if (records.isNotEmpty()) {
            listState.animateScrollToItem(records.size - 1)
        }
    }

    // UI
    val isLive = sessionInfo?.status == "active"
    val errorCount = records.count { it.isError }
    val toolCount = records.count { it.toolName != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = department.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sessionInfo?.model != null) {
                            Text(
                                text = formatModelName(sessionInfo!!.model!!),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (sessionInfo != null) {
                        if (isLive) {
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
                                modifier = Modifier
                                    .height(28.dp)
                                    .padding(end = 8.dp),
                            )
                        } else {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = "Completed",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier
                                    .height(28.dp)
                                    .padding(end = 8.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Discovery phase
            if (discovering) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Finding ${department}'s session...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Scaffold
            }

            // Discovery failed
            if (discoveryError != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = discoveryError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap back to return",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Scaffold
            }

            // Stats bar
            if (records.isNotEmpty()) {
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

            // Poll error banner
            if (pollError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = pollError!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            HorizontalDivider()

            // Loading initial records
            if (records.isEmpty() && sessionInfo != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Loading records...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Record list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        records,
                        key = { "${it.agentId}-${it.sequence}" },
                    ) { record ->
                        RecordItem(record = record)
                    }

                    // Live indicator at bottom
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
            }
        }
    }
}

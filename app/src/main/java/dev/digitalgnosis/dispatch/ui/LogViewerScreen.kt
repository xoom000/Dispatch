package dev.digitalgnosis.dispatch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalgnosis.dispatch.logging.FileLogTree
import dev.digitalgnosis.dispatch.logging.InMemoryLogTree
import timber.log.Timber

/**
 * In-app log viewer using Timber's InMemoryLogTree.
 * Color-coded by level, filterable, copy to clipboard, auto-scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val logTree = remember { InMemoryLogTree.getInstance() }
    val fileLogTree = remember { if (FileLogTree.isInitialized()) FileLogTree.getInstance() else null }
    var logs by remember { mutableStateOf<List<InMemoryLogTree.LogEntry>>(emptyList()) }
    var filterLevel by remember { mutableStateOf("ALL") }
    var excludedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showTagPicker by remember { mutableStateOf(false) }

    var hasCrashLogs by remember { mutableStateOf(false) }
    var showingPreviousSession by remember { mutableStateOf(false) }
    var crashDismissed by remember { mutableStateOf(false) }

    val uniqueTags = remember(logs) {
        logs.map { it.tag }.distinct().sorted()
    }

    val listState = rememberLazyListState()

    fun filterLogs(logList: List<InMemoryLogTree.LogEntry>): List<InMemoryLogTree.LogEntry> {
        return logList
            .filter { filterLevel == "ALL" || it.level == filterLevel }
            .filter { it.tag !in excludedTags }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        hasCrashLogs = fileLogTree?.hasCrashLogs() == true
        Timber.d("=== LOG VIEWER OPENED ===")
        logs = logTree.getAllLogs()

        val listener: (List<InMemoryLogTree.LogEntry>) -> Unit = {
            if (!showingPreviousSession) {
                logs = logTree.getAllLogs()
            }
        }
        logTree.addLogListener(listener)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Debug Logs", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${logs.size} entries",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    IconButton(
                        onClick = {
                            val filtered = filterLogs(logs)
                            val allLogs = filtered.joinToString("\n") {
                                "${it.timestamp} ${it.level}/${it.tag}: ${it.message}"
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Logs", allLogs))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy All",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = {
                            logTree.clearLogs()
                            fileLogTree?.clearAllLogs()
                            logs = emptyList()
                            excludedTags = emptySet()
                            hasCrashLogs = false
                            showingPreviousSession = false
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Crash warning banner
        if (hasCrashLogs && !crashDismissed) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showingPreviousSession = true
                        logs = fileLogTree?.getPreviousSessionLogs() ?: emptyList()
                        crashDismissed = true
                    },
                color = Color(0xFFFF5252)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CRASH DETECTED",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Tap to view previous session logs",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { crashDismissed = true }) {
                        Icon(Icons.Default.Clear, "Dismiss", tint = Color.White)
                    }
                }
            }
        }

        // Previous session indicator
        if (showingPreviousSession) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showingPreviousSession = false
                        logs = logTree.getAllLogs()
                    },
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Viewing PREVIOUS SESSION logs",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Tap to return",
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("ALL", "A", "E", "W", "I", "D").forEach { level ->
                FilterChip(
                    selected = filterLevel == level,
                    onClick = { filterLevel = level },
                    label = {
                        Text(
                            text = when (level) {
                                "A" -> "Crash"
                                "E" -> "Errors"
                                "W" -> "Warnings"
                                "I" -> "Info"
                                "D" -> "Debug"
                                else -> "All"
                            },
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (level) {
                            "A" -> Color(0xFFB71C1C).copy(alpha = 0.3f)
                            "E" -> Color(0xFFFF5252).copy(alpha = 0.2f)
                            "W" -> Color(0xFFFF9800).copy(alpha = 0.2f)
                            "I" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            "D" -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                )
            }
        }

        // Tag filter row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { showTagPicker = true },
                    label = {
                        Text(
                            text = if (excludedTags.isNotEmpty()) "Filters (${excludedTags.size})" else "Filter Tags",
                            fontSize = 12.sp
                        )
                    }
                )

                excludedTags.forEach { tag ->
                    FilterChip(
                        selected = true,
                        onClick = { excludedTags = excludedTags - tag },
                        label = { Text(text = tag, fontSize = 12.sp, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF5252).copy(alpha = 0.2f)
                        ),
                        trailingIcon = {
                            Icon(Icons.Default.Clear, "Remove", modifier = Modifier.size(14.dp))
                        }
                    )
                }
            }

            if (excludedTags.isNotEmpty()) {
                IconButton(
                    onClick = { excludedTags = emptySet() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Clear, "Clear all",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        val filteredLogs = filterLogs(logs)

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No logs yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filteredLogs) { log ->
                    LogEntryCard(log)
                }
            }
        }
    } // end Column
    } // end Scaffold

    // Tag picker dialog
    if (showTagPicker) {
        TagPickerDialog(
            tags = uniqueTags,
            excludedTags = excludedTags,
            onExcludedTagsChanged = { excludedTags = it },
            onDismiss = { showTagPicker = false }
        )
    }
}

@Composable
private fun TagPickerDialog(
    tags: List<String>,
    excludedTags: Set<String>,
    onExcludedTagsChanged: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Filter Tags")
                Text(
                    text = "Tap to hide/show tags",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.height(400.dp)
            ) {
                items(tags) { tag ->
                    val isExcluded = tag in excludedTags

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onExcludedTagsChanged(
                                    if (isExcluded) excludedTags - tag else excludedTags + tag
                                )
                            },
                        color = if (isExcluded) Color(0xFFFF5252).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isExcluded) "Hidden" else "Visible",
                                fontSize = 12.sp,
                                color = if (isExcluded) Color(0xFFFF5252) else Color(0xFF4CAF50),
                                modifier = Modifier.width(48.dp)
                            )
                            Text(
                                text = tag,
                                fontSize = 14.sp,
                                fontWeight = if (isExcluded) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun LogEntryCard(log: InMemoryLogTree.LogEntry) {
    val context = LocalContext.current

    val backgroundColor = when (log.level) {
        "A" -> Color(0xFFB71C1C).copy(alpha = 0.2f)
        "E" -> Color(0xFFFF5252).copy(alpha = 0.1f)
        "W" -> Color(0xFFFF9800).copy(alpha = 0.1f)
        "I" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
        "D" -> Color(0xFF4CAF50).copy(alpha = 0.05f)
        else -> MaterialTheme.colorScheme.surface
    }

    val levelColor = when (log.level) {
        "A" -> Color(0xFFB71C1C)
        "E" -> Color(0xFFFF5252)
        "W" -> Color(0xFFFF9800)
        "I" -> MaterialTheme.colorScheme.primary
        "D" -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val logText = "${log.timestamp} ${log.level}/${log.tag}: ${log.message}"
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Log Entry", logText))
            },
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Level badge
            Surface(
                color = levelColor,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = log.level,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = log.tag,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = log.timestamp,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = log.message,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

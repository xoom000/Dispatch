package dev.digitalgnosis.dispatch.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.data.HistoryMessage
import dev.digitalgnosis.dispatch.ui.viewmodels.HistoryViewModel
import timber.log.Timber

private const val PAGE_SIZE = 40

/**
 * Full archive view — every dispatch message ever sent, fetched from
 * the pop-os history database via File Bridge.
 *
 * Features:
 * - Paginated infinite scroll (loads more as you scroll down)
 * - Text search across message content
 * - Sender filter chips (populated from actual data)
 * - Tap to copy, long-press for sender + message
 * - Replay via GPU TTS
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val messages by viewModel.history.collectAsState()
    val total by viewModel.totalMessages.collectAsState()
    val knownSenders by viewModel.knownSenders.collectAsState()
    val loading by viewModel.isLoading.collectAsState()
    val loadingMore by viewModel.isLoadingMore.collectAsState()
    
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeSender by rememberSaveable { mutableStateOf<String?>(null) }
    
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Initial load
    LaunchedEffect(Unit) { viewModel.loadMessages(reset = true) }

    // Infinite scroll: load more when near the bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            !loading && !loadingMore && messages.size < total && lastVisible >= totalItems - 5
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMessages(reset = false)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search messages...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        viewModel.setSearch(null)
                        focusManager.clearFocus()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    val query = searchQuery.trim()
                    viewModel.setSearch(query.ifEmpty { null })
                    focusManager.clearFocus()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        )

        // Sender filter chips
        if (knownSenders.size > 1) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // "All" chip
                FilterChip(
                    selected = activeSender == null,
                    onClick = {
                        activeSender = null
                        viewModel.setSender(null)
                    },
                    label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                )
                knownSenders.forEach { sender ->
                    FilterChip(
                        selected = activeSender == sender,
                        onClick = {
                            activeSender = if (activeSender == sender) null else sender
                            viewModel.setSender(activeSender)
                        },
                        label = { Text(sender, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }

        // Header: total count + refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    loading -> "Loading..."
                    total > 0 -> "$total messages"
                    else -> "No messages"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { viewModel.loadMessages(reset = true) },
                enabled = !loading,
            ) {
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

        // Message list
        if (messages.isEmpty() && !loading) {
            // Empty state
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (searchQuery.isNotBlank() || activeSender != null) "No matching messages"
                           else "No history yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (searchQuery.isNotBlank() || activeSender != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        searchQuery = ""
                        activeSender = null
                        viewModel.setSearch(null)
                        viewModel.setSender(null)
                    }) {
                        Text("Clear filters")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                items(messages, key = { it.id }) { message ->
                    HistoryCard(
                        message = message,
                        viewModel = viewModel,
                    )
                }

                // Loading more indicator
                if (loadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }

                // "Loaded all" indicator
                if (!loadingMore && messages.size >= total && total > 0) {
                    item {
                        Text(
                            text = "All $total messages loaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    message: HistoryMessage,
    viewModel: HistoryViewModel,
) {
    val context = LocalContext.current
    var replaying by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (!message.success)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("dispatch", message.message))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
                onLongClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("dispatch", "${message.sender}: ${message.message}")
                    )
                    Toast.makeText(context, "Copied with sender", Toast.LENGTH_SHORT).show()
                },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: sender + time + replay
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.sender,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Replay button
                    IconButton(
                        onClick = {
                            viewModel.replayMessage(message)
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Replay",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    // Failed indicator
                    if (!message.success) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }

                    Text(
                        text = formatHistoryTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Message body
            Text(
                text = message.message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )

            // Error detail
            if (!message.success && !message.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Priority badge (only if not normal)
            if (message.priority != "normal") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.priority.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (message.priority == "urgent")
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * Format ISO timestamp for display. Shows time for today,
 * date for older messages.
 */
private fun formatHistoryTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        // Try with fractional seconds first
        val sdf = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).also {
                it.timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
        } catch (_: Exception) {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                it.timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
        }

        val date = sdf.parse(iso) ?: return iso

        val now = System.currentTimeMillis()
        val diff = now - date.time
        val hours = diff / (1000 * 60 * 60)

        when {
            hours < 24 -> {
                val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                timeFormat.format(date)
            }
            hours < 48 -> {
                val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                "Yesterday ${timeFormat.format(date)}"
            }
            hours < 168 -> { // 7 days
                val dayFormat = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US)
                dayFormat.format(date)
            }
            else -> {
                val fullFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                fullFormat.format(date)
            }
        }
    } catch (_: Exception) {
        iso.take(19) // Fallback: show raw but truncated
    }
}

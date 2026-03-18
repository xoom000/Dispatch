package dev.digitalgnosis.dispatch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.data.ThreadInfo
import dev.digitalgnosis.dispatch.ui.components.AgentAvatar
import dev.digitalgnosis.dispatch.ui.viewmodels.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onComposeNew: () -> Unit,
) {
    val threads by viewModel.threads.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val selectedThread by viewModel.selectedThread.collectAsState()

    if (selectedThread != null) {
        MessagesScreen(
            threadId = selectedThread!!.threadId,
            department = selectedThread!!.participants.firstOrNull() ?: "Unknown",
            onBack = { viewModel.selectThread(null) }
        )
    } else {
        // FLAT LAYOUT: No Scaffold here, inherited from MainActivity
        Box(modifier = modifier.fillMaxSize()) {
            Column {
                // Inline Header instead of TopAppBar to avoid nesting issues
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Google Messages",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 22.sp
                        )
                    )
                    Row {
                        IconButton(onClick = { }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("N", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(threads) { thread ->
                        ConversationRow(thread) { viewModel.selectThread(thread) }
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }

            // FAB inside the Box so it stays responsive
            ExtendedFloatingActionButton(
                onClick = onComposeNew,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.AddComment, contentDescription = null) },
                text = { Text("Start chat") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun ConversationRow(thread: ThreadInfo, onClick: () -> Unit) {
    val displayName = thread.participants.firstOrNull() ?: "Unknown"
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgentAvatar(name = displayName, size = 56.dp)
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTimestamp(thread.lastActivity),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = thread.lastMessagePreview ?: "No messages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun formatTimestamp(iso: String): String {
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = sdf.parse(iso) ?: return ""
        val now = System.currentTimeMillis()
        val diff = now - date.time
        
        when {
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} min"
            diff < 24 * 60 * 60 * 1000 -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(date)
            diff < 7 * 24 * 60 * 60 * 1000 -> java.text.SimpleDateFormat("EEE", java.util.Locale.US).format(date)
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(date)
        }
    } catch (_: Exception) { "" }
}

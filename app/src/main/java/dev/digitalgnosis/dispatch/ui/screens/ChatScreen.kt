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
import dev.digitalgnosis.dispatch.data.SessionInfo
import dev.digitalgnosis.dispatch.ui.components.AgentAvatar
import dev.digitalgnosis.dispatch.ui.viewmodels.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onComposeNew: () -> Unit,
) {
    val sessions by viewModel.sessions.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val selectedSession by viewModel.selectedSession.collectAsState()

    if (selectedSession != null) {
        MessagesScreen(
            threadId = selectedSession!!.sessionId,
            department = selectedSession!!.department,
            onBack = { viewModel.selectSession(null) }
        )
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Dispatch",
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
                    items(sessions) { session ->
                        SessionRow(session) { viewModel.selectSession(session) }
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }

            FloatingActionButton(
                onClick = onComposeNew,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp),
                shape = RoundedCornerShape(28.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AddComment, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start chat")
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionInfo, onClick: () -> Unit) {
    val label = session.alias.ifBlank { null }
        ?: session.department
    val preview = session.summary
        ?: "Session ${session.sessionId.take(8)}"
    val time = formatTimestamp(session.lastActivity)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgentAvatar(name = session.department, size = 52.dp)

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTimestamp(iso: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(iso.take(19)) ?: return ""
        val formatter = SimpleDateFormat("HH:mm", Locale.US)
        formatter.format(date)
    } catch (e: Exception) {
        ""
    }
}

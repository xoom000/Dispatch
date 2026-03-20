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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.components.AgentAvatar
import dev.digitalgnosis.dispatch.ui.viewmodels.ChatViewModel
import dev.digitalgnosis.dispatch.util.formatSmartTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onComposeNew: () -> Unit,
    onOpenConversation: (sessionId: String, department: String) -> Unit = { _, _ -> },
) {
    val sessions by viewModel.sessionInfoList.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

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
                    SessionRow(session) {
                        onOpenConversation(session.sessionId, session.department)
                    }
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

@Composable
private fun SessionRow(session: ChatViewModel.SessionInfo, onClick: () -> Unit) {
    val subtitle = session.title.ifBlank { session.alias.ifBlank { session.sessionId.take(20) } }
    val time = formatSmartTimestamp(session.lastActivity)

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
                text = session.department.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
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


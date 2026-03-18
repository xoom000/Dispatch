package dev.digitalgnosis.dispatch.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.data.GeminiSessionInfo
import dev.digitalgnosis.dispatch.ui.components.AgentAvatar
import dev.digitalgnosis.dispatch.ui.viewmodels.GeminiMessage
import dev.digitalgnosis.dispatch.ui.viewmodels.GeminiViewModel

@Composable
fun GeminiWorkspaceScreen(
    modifier: Modifier = Modifier,
    viewModel: GeminiViewModel = hiltViewModel(),
) {
    val activeSession by viewModel.activeSession.collectAsState()
    
    if (activeSession != null) {
        BackHandler { viewModel.clearActiveSession() }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (activeSession == null) {
            SessionListContent(viewModel)
        } else {
            SessionDetailContent(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListContent(viewModel: GeminiViewModel) {
    val sessions by viewModel.sessions.collectAsState()
    val loading by viewModel.isLoading.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("Gemini Native", style = MaterialTheme.typography.titleLarge) },
            actions = {
                IconButton(onClick = { viewModel.refreshSessions() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        )
        
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions) { session ->
                SessionRow(session) { viewModel.loadSession(session.id) }
            }
        }
    }
}

@Composable
private fun SessionRow(session: GeminiSessionInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Session ${session.id.take(8)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(text = session.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailContent(viewModel: GeminiViewModel) {
    val session by viewModel.activeSession.collectAsState()
    val listState = rememberLazyListState()
    var replyText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(session?.messages?.size) {
        session?.messages?.size?.let { size ->
            if (size > 0) listState.animateScrollToItem(size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { viewModel.clearActiveSession() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentAvatar(name = "Gemini CLI", size = 36.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Gemini CLI", style = MaterialTheme.typography.titleMedium)
                }
            }
        )

        val messages = session?.messages ?: emptyList()
        val optimizedMessages = if (messages.size > 50) messages.takeLast(50) else messages

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(optimizedMessages, key = { it.id }) { GeminiMessageBubble(it) }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // RCS Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { }) {
                Icon(Icons.Default.Add, contentDescription = "Attach", tint = MaterialTheme.colorScheme.primary)
            }
            
            Surface(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(28.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = "Emoji", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        placeholder = { Text("RCS message", style = MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            FloatingActionButton(
                onClick = {
                    if (replyText.isNotBlank()) {
                        viewModel.sendMessage(replyText)
                        replyText = ""
                    }
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
            ) {
                Icon(
                    imageVector = if (replyText.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }
    }
}

@Composable
private fun GeminiMessageBubble(message: GeminiMessage) {
    val isGemini = message.isGemini
    val bubbleColor = if (!isGemini) Color(0xFF004D40) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (!isGemini) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(), 
        horizontalAlignment = if (isGemini) Alignment.Start else Alignment.End
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isGemini) 4.dp else 18.dp,
                bottomEnd = if (isGemini) 18.dp else 4.dp
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.thoughts.isNotEmpty()) {
                    message.thoughts.forEach { thought ->
                        Text(
                            text = "💡 $thought",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = textColor.copy(alpha = 0.2f))
                }
                
                // If it's a live chunk, show a subtle pulsing indicator or just the text
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontFamily = if (isGemini) FontFamily.Monospace else FontFamily.Default
                    ),
                    color = textColor
                )
            }
        }
    }
}

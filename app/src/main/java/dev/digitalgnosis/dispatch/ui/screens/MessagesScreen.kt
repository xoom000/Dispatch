package dev.digitalgnosis.dispatch.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.ui.components.AgentAvatar
import dev.digitalgnosis.dispatch.ui.theme.DgDispatchBubble
import dev.digitalgnosis.dispatch.ui.theme.DgDispatchText
import dev.digitalgnosis.dispatch.ui.theme.DgToolBubble
import dev.digitalgnosis.dispatch.ui.theme.DgToolText
import dev.digitalgnosis.dispatch.ui.viewmodels.MessagesViewModel

@Composable
fun MessagesScreen(
    threadId: String,
    department: String,
    viewModel: MessagesViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val bubbles by viewModel.bubbles.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val streamingToolStatus by viewModel.streamingToolStatus.collectAsState()
    val playingSequence by viewModel.playingSequence.collectAsState()
    val listState = rememberLazyListState()
    var replyText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(threadId) {
        viewModel.loadSession(threadId, department)
    }

    LaunchedEffect(bubbles.size, isStreaming) {
        if (bubbles.isNotEmpty()) {
            listState.animateScrollToItem(bubbles.size - 1)
        }
    }

    // FLAT LAYOUT: No Scaffold, using Column to fill the MainActivity modifier
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Custom Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1F20))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            AgentAvatar(name = department, size = 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = department,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { }) { Icon(Icons.Default.Call, contentDescription = "Call") }
            IconButton(onClick = { }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        }

        // Message List
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(bubbles) { bubble ->
                val isCurrentlyPlaying = playingSequence == bubble.sequence
                when (bubble.type) {
                    "nigel" -> NigelBubble(bubble)
                    "agent" -> AgentBubble(bubble)
                    "dispatch" -> DispatchBubble(
                        bubble = bubble,
                        isPlaying = isCurrentlyPlaying,
                        onReplay = {
                            viewModel.replayDispatch(bubble.text, bubble.sequence)
                        }
                    )
                    "tool" -> ToolBubble(bubble)
                    else -> AgentBubble(bubble)
                }
            }
            // Streaming bubble — shows live token accumulation
            if (isStreaming) {
                item {
                    StreamingBubble(
                        text = streamingText,
                        toolStatus = streamingToolStatus,
                    )
                }
            }
            if (isSending && !isStreaming) {
                item { SendingIndicator() }
            }
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
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF444746))
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
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Photo, contentDescription = "Gallery", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val isTextBlank = replyText.isBlank()
            IconButton(
                onClick = {
                    if (!isTextBlank) {
                        viewModel.sendStreaming(department, replyText)
                        replyText = ""
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isTextBlank) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── Per-type bubble renderers ────────────────────────────────────────

@Composable
private fun NigelBubble(bubble: ChatBubble) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = Color(0xFF0842A0),
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = 20.dp, bottomEnd = 4.dp
            ),
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = bubble.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = Color(0xFFD3E3FD)
                )
            }
        }
        if (bubble.timestamp.isNotBlank()) {
            Text(
                text = bubble.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun AgentBubble(bubble: ChatBubble) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = Color(0xFF282A2C),
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = 4.dp, bottomEnd = 20.dp
            ),
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = bubble.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = Color(0xFFE3E3E3)
                )
            }
        }
        if (bubble.timestamp.isNotBlank()) {
            Text(
                text = bubble.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun DispatchBubble(
    bubble: ChatBubble,
    isPlaying: Boolean,
    onReplay: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = DgDispatchBubble,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = 4.dp, bottomEnd = 18.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bubble.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = DgDispatchText
                    )
                    if (bubble.detail.isNotBlank()) {
                        Text(
                            text = bubble.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = DgDispatchText.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onReplay,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isPlaying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = DgDispatchText
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Replay",
                            tint = DgDispatchText.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        if (bubble.timestamp.isNotBlank()) {
            Text(
                text = bubble.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun ToolBubble(bubble: ChatBubble) {
    Surface(
        color = DgToolBubble,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(
                text = bubble.text + if (bubble.detail.isNotBlank()) " — ${bubble.detail}" else "",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontStyle = FontStyle.Italic
                ),
                color = DgToolText,
            )
        }
    }
}

@Composable
private fun StreamingBubble(text: String, toolStatus: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        // Tool status overlay (shows when agent is using tools)
        AnimatedVisibility(visible = !toolStatus.isNullOrBlank()) {
            Surface(
                color = DgToolBubble,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(vertical = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = DgToolText
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = toolStatus ?: "",
                        style = MaterialTheme.typography.labelMedium.copy(fontStyle = FontStyle.Italic),
                        color = DgToolText,
                    )
                }
            }
        }

        // Streaming text bubble
        Surface(
            color = Color(0xFF282A2C),
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = 4.dp, bottomEnd = 20.dp
            ),
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (text.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Thinking...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE3E3E3).copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = Color(0xFFE3E3E3)
                    )
                }
            }
        }
    }
}

@Composable
private fun SendingIndicator() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Sending...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

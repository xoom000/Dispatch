package dev.digitalgnosis.dispatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.ui.components.ConversationHeader
import dev.digitalgnosis.dispatch.ui.components.DateSeparator
import dev.digitalgnosis.dispatch.ui.components.InputBar
import dev.digitalgnosis.dispatch.ui.components.JumpToBottom
import dev.digitalgnosis.dispatch.ui.components.TypingIndicator
import dev.digitalgnosis.dispatch.ui.components.bubbles.AgentBubble
import dev.digitalgnosis.dispatch.ui.components.bubbles.DispatchBubble
import dev.digitalgnosis.dispatch.ui.components.bubbles.NigelBubble
import dev.digitalgnosis.dispatch.ui.components.bubbles.SendingIndicator
import dev.digitalgnosis.dispatch.ui.components.bubbles.StreamingBubble
import dev.digitalgnosis.dispatch.ui.components.bubbles.ToolBubble
import dev.digitalgnosis.dispatch.ui.viewmodels.MessagesViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// ── Helpers ───────────────────────────────────────────────────────────

private fun timestampToDate(timestamp: String): LocalDate? {
    if (timestamp.isBlank()) return null
    val formats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
    )
    for (fmt in formats) {
        try {
            return LocalDate.parse(timestamp, fmt)
        } catch (_: DateTimeParseException) {}
    }
    return null
}

private fun dateSeparatorLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

// ── Screen ────────────────────────────────────────────────────────────

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
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var replyText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(threadId) {
        viewModel.loadSession(threadId, department)
    }

    // Auto-scroll: only when user is already at (or near) the bottom
    LaunchedEffect(bubbles.size, isStreaming) {
        if (bubbles.isNotEmpty()) {
            val atBottom = listState.firstVisibleItemIndex >= bubbles.size - 2 ||
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == listState.layoutInfo.totalItemsCount - 1
            if (atBottom) {
                listState.animateScrollToItem(bubbles.size - 1)
            }
        }
    }

    // JumpToBottom visibility: show when last item is not visible
    val jumpToBottomEnabled by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible < totalItems - 2
        }
    }

    // imePadding belongs on InputBar, NOT here. Putting it on the Column
    // causes a gap between keyboard and input bar. This was the root cause
    // of 9 failed fix attempts. See: 2026-03-20-messages-screen-fix-plan.md
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        ConversationHeader(department = department, onBack = onBack)

        // Message List in a Box so JumpToBottom can overlay it
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                // Sender-run grouping: compare adjacent bubbles
                val chatBubbles = bubbles
                items(chatBubbles.size) { index ->
                    val bubble = chatBubbles[index]
                    val prevBubble = chatBubbles.getOrNull(index - 1)
                    val nextBubble = chatBubbles.getOrNull(index + 1)

                    val isFirstInRun = prevBubble == null || prevBubble.type != bubble.type
                    val isLastInRun = nextBubble == null || nextBubble.type != bubble.type

                    // Date separator: show when the date changes from the previous bubble
                    val currentDate = timestampToDate(bubble.timestamp)
                    val prevDate = prevBubble?.let { timestampToDate(it.timestamp) }
                    if (currentDate != null && currentDate != prevDate) {
                        DateSeparator(label = dateSeparatorLabel(currentDate))
                    }

                    // Extra top padding when a new sender run begins (not the very first bubble)
                    val topPadding = if (isFirstInRun && index != 0) 8.dp else 0.dp

                    val isCurrentlyPlaying = playingSequence == bubble.sequence
                    when (bubble.type) {
                        "nigel" -> NigelBubble(
                            bubble = bubble,
                            showTimestamp = isLastInRun,
                            topPadding = topPadding,
                            isFirstInRun = isFirstInRun,
                            isLastInRun = isLastInRun,
                            snackbarHostState = snackbarHostState,
                        )
                        "agent" -> AgentBubble(
                            bubble = bubble,
                            showTimestamp = isLastInRun,
                            topPadding = topPadding,
                            isFirstInRun = isFirstInRun,
                            isLastInRun = isLastInRun,
                            department = department,
                            snackbarHostState = snackbarHostState,
                        )
                        "dispatch" -> DispatchBubble(
                            bubble = bubble,
                            isPlaying = isCurrentlyPlaying,
                            showTimestamp = isLastInRun,
                            topPadding = topPadding,
                            isFirstInRun = isFirstInRun,
                            isLastInRun = isLastInRun,
                            onReplay = {
                                viewModel.replayDispatch(bubble.text, bubble.sequence)
                            }
                        )
                        "tool" -> ToolBubble(bubble = bubble, topPadding = topPadding)
                        else -> AgentBubble(
                            bubble = bubble,
                            showTimestamp = isLastInRun,
                            topPadding = topPadding,
                            isFirstInRun = isFirstInRun,
                            isLastInRun = isLastInRun,
                            department = department,
                            snackbarHostState = snackbarHostState,
                        )
                    }
                }

                // Typing indicator — shown when agent is thinking (streaming but no tokens yet)
                if (isStreaming && streamingText.isEmpty()) {
                    item {
                        TypingIndicator(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                // Streaming bubble — shows live token accumulation
                if (isStreaming && streamingText.isNotEmpty()) {
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

            JumpToBottom(
                enabled = jumpToBottomEnabled,
                onClicked = {
                    scope.launch {
                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        InputBar(
            value = replyText,
            onValueChange = { replyText = it },
            onSend = {
                viewModel.sendStreaming(department, replyText)
                replyText = ""
                scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
            },
            isSending = isSending,
        )
    }
}


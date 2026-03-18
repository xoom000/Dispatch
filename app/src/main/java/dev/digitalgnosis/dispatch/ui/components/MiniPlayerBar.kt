package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalgnosis.dispatch.audio.PlaybackUiState

/**
 * Mini player bar — slides up from the bottom when audio is playing.
 *
 * Modeled after Google Messages / Spotify mini player:
 * - Agent avatar + sender name + message preview
 * - Play/pause button
 * - Skip next button (next queued message)
 * - Recording indicator when voice reply is active
 *
 * Placed ABOVE the bottom nav bar in the scaffold.
 */
@Composable
fun MiniPlayerBar(
    state: PlaybackUiState,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.isActive,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column {
                // Thin progress accent line at top
                if (state.isPlaying) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                } else {
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Agent avatar
                    AgentAvatar(name = state.sender, size = 40.dp)

                    Spacer(modifier = Modifier.width(12.dp))

                    // Sender + message preview
                    Column(modifier = Modifier.weight(1f)) {
                        if (state.isRecording) {
                            Text(
                                text = "Recording reply to ${state.replyTarget}...",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(
                                text = state.sender,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.messagePreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Queue count
                        if (state.pendingCount > 1) {
                            Text(
                                text = "${state.pendingCount} messages queued",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    // Replay button
                    IconButton(onClick = onReplay, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Replay",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Play/Pause button
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying)
                                Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    // Skip next (only if queue has more)
                    if (state.pendingCount > 1) {
                        IconButton(onClick = onSkipNext, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next message",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

package dev.digitalgnosis.dispatch.ui.components.bubbles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.ui.theme.DgDispatchBubble
import dev.digitalgnosis.dispatch.ui.theme.DgDispatchText

@Composable
fun DispatchBubble(
    modifier: Modifier = Modifier,
    bubble: ChatBubble,
    isPlaying: Boolean,
    showTimestamp: Boolean,
    topPadding: Dp,
    isFirstInRun: Boolean = true,
    isLastInRun: Boolean = true,
    onReplay: () -> Unit,
) {
    // Incoming (left-aligned): tail is LEFT side
    val shape = when {
        isFirstInRun && isLastInRun -> RoundedCornerShape(20.dp)
        isFirstInRun -> RoundedCornerShape(
            topStart = 20.dp, topEnd = 20.dp,
            bottomStart = 4.dp, bottomEnd = 20.dp
        )
        isLastInRun -> RoundedCornerShape(
            topStart = 4.dp, topEnd = 20.dp,
            bottomStart = 20.dp, bottomEnd = 20.dp
        )
        else -> RoundedCornerShape(
            topStart = 4.dp, topEnd = 20.dp,
            bottomStart = 4.dp, bottomEnd = 20.dp
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = DgDispatchBubble,
            shape = shape,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Waveform icon
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = DgDispatchText,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
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
                    // "Dispatched" label
                    Text(
                        text = "Dispatched",
                        style = MaterialTheme.typography.labelSmall,
                        color = DgDispatchText.copy(alpha = 0.45f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
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
        if (showTimestamp && bubble.timestamp.isNotBlank()) {
            Text(
                text = bubble.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }
    }
}

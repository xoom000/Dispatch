package dev.digitalgnosis.dispatch.ui.components.bubbles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalgnosis.dispatch.ui.theme.DgToolBubble
import dev.digitalgnosis.dispatch.ui.theme.DgToolText

@Composable
fun StreamingBubble(
    modifier: Modifier = Modifier,
    text: String,
    toolStatus: String?,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

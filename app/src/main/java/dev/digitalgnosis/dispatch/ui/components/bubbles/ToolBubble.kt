package dev.digitalgnosis.dispatch.ui.components.bubbles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.ui.theme.DgToolBubble
import dev.digitalgnosis.dispatch.ui.theme.DgToolText
import dev.digitalgnosis.dispatch.ui.theme.OutlineVariant

private fun extractToolName(text: String): String {
    // Try to extract a short tool name from the first token or line
    val firstLine = text.lines().firstOrNull()?.trim() ?: return text
    return if (firstLine.length <= 40) firstLine else firstLine.take(37) + "…"
}

@Composable
fun ToolBubble(
    modifier: Modifier = Modifier,
    bubble: ChatBubble,
    topPadding: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val toolName = extractToolName(bubble.text)

    Surface(
        color = DgToolBubble,
        shape = shape,
        modifier = modifier
            .padding(top = topPadding, bottom = 1.dp)
            .border(width = 1.dp, color = OutlineVariant, shape = shape)
            .clickable { expanded = !expanded }
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // Collapsed header row: tool name + checkmark
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = DgToolText,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelMedium,
                    color = DgToolText,
                    maxLines = 1
                )
            }
            // Expanded: full content in monospace
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = bubble.text + if (bubble.detail.isNotBlank()) "\n\n${bubble.detail}" else "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = DgToolText,
                    )
                }
            }
        }
    }
}

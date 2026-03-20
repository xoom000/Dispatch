package dev.digitalgnosis.dispatch.ui.components.bubbles

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.ui.components.SymbolAnnotationType
import dev.digitalgnosis.dispatch.ui.components.messageFormatter

@Composable
fun NigelBubble(
    modifier: Modifier = Modifier,
    bubble: ChatBubble,
    showTimestamp: Boolean,
    topPadding: Dp,
    isFirstInRun: Boolean = true,
    isLastInRun: Boolean = true,
) {
    val uriHandler = LocalUriHandler.current
    val styledText = messageFormatter(text = bubble.text, primary = true)

    // Send animation — fires once on first composition, slides in from left
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val animProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "nigelSend"
    )

    // Outgoing (right-aligned): tail is RIGHT side
    val shape = when {
        isFirstInRun && isLastInRun -> RoundedCornerShape(20.dp)
        isFirstInRun -> RoundedCornerShape(
            topStart = 20.dp, topEnd = 20.dp,
            bottomStart = 20.dp, bottomEnd = 4.dp
        )
        isLastInRun -> RoundedCornerShape(
            topStart = 20.dp, topEnd = 4.dp,
            bottomStart = 20.dp, bottomEnd = 20.dp
        )
        else -> RoundedCornerShape(
            topStart = 20.dp, topEnd = 4.dp,
            bottomStart = 20.dp, bottomEnd = 4.dp
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .graphicsLayer {
                translationX = (1f - animProgress) * (-20.dp.toPx())
            },
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = shape,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                ClickableText(
                    text = styledText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    onClick = { offset ->
                        styledText.getStringAnnotations(start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                if (annotation.tag == SymbolAnnotationType.LINK.name) {
                                    uriHandler.openUri(annotation.item)
                                }
                            }
                    }
                )
            }
        }
        if (showTimestamp && bubble.timestamp.isNotBlank()) {
            Text(
                text = bubble.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

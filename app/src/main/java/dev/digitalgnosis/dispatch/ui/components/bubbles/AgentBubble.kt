package dev.digitalgnosis.dispatch.ui.components.bubbles

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.data.ChatBubble
import dev.digitalgnosis.dispatch.ui.theme.DgDeptBoardroom
import dev.digitalgnosis.dispatch.ui.theme.DgDeptDefault
import dev.digitalgnosis.dispatch.ui.theme.DgDeptDispatch
import dev.digitalgnosis.dispatch.ui.theme.DgDeptEngineering
import dev.digitalgnosis.dispatch.ui.theme.DgDeptHunter
import dev.digitalgnosis.dispatch.ui.theme.DgDeptIT
import dev.digitalgnosis.dispatch.ui.theme.DgDeptNigel
import dev.digitalgnosis.dispatch.ui.theme.DgDeptResearch

fun departmentColor(dept: String): Color = when (dept.lowercase().trim()) {
    "engineering", "eng" -> DgDeptEngineering
    "research" -> DgDeptResearch
    "boardroom" -> DgDeptBoardroom
    "hunter" -> DgDeptHunter
    "dispatch" -> DgDeptDispatch
    "it" -> DgDeptIT
    "nigel" -> DgDeptNigel
    else -> DgDeptDefault
}

@Composable
fun AgentBubble(
    modifier: Modifier = Modifier,
    bubble: ChatBubble,
    showTimestamp: Boolean,
    topPadding: Dp,
    isFirstInRun: Boolean = true,
    isLastInRun: Boolean = true,
    department: String = "",
    snackbarHostState: SnackbarHostState,
) {

    // Arrival animation — fires once on first composition
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val animProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "agentArrival"
    )

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
            .padding(top = topPadding)
            .graphicsLayer {
                alpha = animProgress
                scaleX = 0.95f + 0.05f * animProgress
                scaleY = 0.95f + 0.05f * animProgress
                translationY = (1f - animProgress) * 20.dp.toPx()
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
            },
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                // 2dp department color stripe on the left edge
                if (department.isNotBlank()) {
                    Surface(
                        color = departmentColor(department),
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                    ) {}
                }
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    BubbleRichContent(
                        text = bubble.text,
                        primary = false,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        snackbarHostState = snackbarHostState,
                    )
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

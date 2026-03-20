package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.ui.theme.DgNeonCyan

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    // Total cycle: 3 dots * 120ms stagger + hold time = ~800ms feels natural
    val cycleDuration = 900

    val dot1Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = cycleDuration
                0f at 0
                -8f at 150
                0f at 300
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot1"
    )

    val dot2Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = cycleDuration
                0f at 120
                -8f at 270
                0f at 420
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot2"
    )

    val dot3Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = cycleDuration
                0f at 240
                -8f at 390
                0f at 540
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier
            .clip(
                RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomStart = 4.dp, bottomEnd = 20.dp
                )
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dot1Offset, dot2Offset, dot3Offset).forEach { yOffset ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .offset(y = yOffset.dp)
                    .clip(CircleShape)
                    .background(DgNeonCyan)
            )
        }
    }
}

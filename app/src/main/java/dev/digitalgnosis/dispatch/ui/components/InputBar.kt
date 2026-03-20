package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.digitalgnosis.dispatch.ui.theme.DgNeonCyan

@Composable
fun InputBar(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit = {},
    onEmoji: () -> Unit = {},
    onImage: () -> Unit = {},
    onMic: () -> Unit = {},
    isSending: Boolean = false,
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) DgNeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        animationSpec = tween(durationMillis = 250),
        label = "inputPillBorderColor"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 1.5.dp else 1.dp,
        animationSpec = tween(durationMillis = 250),
        label = "inputPillBorderWidth"
    )
    val glowRadius by animateDpAsState(
        targetValue = if (isFocused) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 250),
        label = "inputPillGlowRadius"
    )

    // Single pill — everything inside, nothing outside. Matches Google Messages layout.
    // No extra padding bands, no separate zones. Background shows through transparent pill.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .neonGlow(color = borderColor, glowRadius = glowRadius, cornerRadius = 24.dp),
            color = Color.White.copy(alpha = 0.03f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(borderWidth, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onEmoji, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.EmojiEmotions, contentDescription = "Emoji", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("Message", style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { isFocused = it.isFocused },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = DgNeonCyan,
                    ),
                )
                IconButton(onClick = onImage, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Photo, contentDescription = "Gallery", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Send/mic button outside pill, bottom-aligned like Google Messages
        val isTextBlank = value.isBlank()
        IconButton(
            onClick = { if (!isTextBlank) onSend() else onMic() },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (isTextBlank) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (isTextBlank) "Voice" else "Send",
                tint = if (isTextBlank) MaterialTheme.colorScheme.onSurfaceVariant else DgNeonCyan
            )
        }
    }
}

/**
 * Draws a soft neon glow shadow behind the composable using a blurred color layer.
 * Only visible when glowRadius > 0.
 */
private fun Modifier.neonGlow(color: Color, glowRadius: Dp, cornerRadius: Dp): Modifier =
    this.drawBehind {
        if (glowRadius.toPx() <= 0f) return@drawBehind
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                asFrameworkPaint().apply {
                    isAntiAlias = true
                    this.color = android.graphics.Color.TRANSPARENT
                    setShadowLayer(
                        glowRadius.toPx(),
                        0f,
                        0f,
                        color.copy(alpha = 0.4f).toArgb()
                    )
                }
            }
            canvas.drawRoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = cornerRadius.toPx(),
                radiusY = cornerRadius.toPx(),
                paint = paint
            )
        }
    }

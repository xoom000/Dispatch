package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.random.Random

private val MatrixChars = "ﾊﾐﾋｰｳｼﾅﾓﾆｻﾜﾂｵﾘｱﾎﾃﾏｹﾒｴｶｷﾑﾕﾗｾﾈｽﾀﾇﾍ012345789Z".toList()

private data class RainColumn(
    val x: Float,       // normalized 0..1
    val speed: Float,   // normalized drops per cycle
    val offset: Float,  // phase offset 0..1
    val length: Int,    // trail length in chars
    val chars: List<Int>, // indices into MatrixChars, one per trail slot
)

/**
 * A subtle Matrix-style digital rain canvas, rendered behind message content.
 * Very low opacity so it reads as texture rather than noise.
 */
@Composable
fun MatrixRainBackground(
    modifier: Modifier = Modifier,
    columnCount: Int = 30,
    baseColor: Color = Color(0xFF00E5FF), // DgNeonCyan
    opacity: Float = 0.04f,
) {
    val textMeasurer = rememberTextMeasurer()

    // One cycle = 4 seconds. tick goes 0..1 linearly and loops.
    val infiniteTransition = rememberInfiniteTransition(label = "matrixRain")
    val tick by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing)
        ),
        label = "matrixTick"
    )

    // Stable column definitions — seeded so they don't scramble on recompose.
    val columns = remember(columnCount) {
        val rng = Random(seed = 0xDEADBEEF)
        List(columnCount) { i ->
            RainColumn(
                x = (i.toFloat() + rng.nextFloat() * 0.5f) / columnCount,
                speed = 0.3f + rng.nextFloat() * 0.7f,
                offset = rng.nextFloat(),
                length = 4 + rng.nextInt(8),
                chars = List(12) { rng.nextInt(MatrixChars.size) }
            )
        }
    }

    val headStyle = TextStyle(
        color = baseColor.copy(alpha = opacity * 6f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val charHeightPx = 14f * density
        val charWidthPx = 8f * density
        val screenRows = (size.height / charHeightPx).toInt() + 1

        columns.forEach { col ->
            val xPx = col.x * size.width

            // Head position: advances by speed each cycle, offset by phase
            val rawHead = ((tick * col.speed + col.offset) % 1f) * (screenRows + col.length)
            val headRow = rawHead.toInt()

            for (slot in 0 until col.length) {
                val row = headRow - slot
                if (row < 0 || row > screenRows) continue

                val charIndex = col.chars[slot % col.chars.size]
                val char = MatrixChars[charIndex].toString()

                // Head is brightest, trail fades out
                val slotAlpha = if (slot == 0) {
                    opacity * 8f
                } else {
                    opacity * (1f - slot.toFloat() / col.length)
                }
                if (slotAlpha <= 0f) continue

                val style = TextStyle(
                    color = baseColor.copy(alpha = slotAlpha.coerceIn(0f, 1f)),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                val measured = textMeasurer.measure(char, style)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(xPx, row * charHeightPx)
                )
            }
        }
    }
}

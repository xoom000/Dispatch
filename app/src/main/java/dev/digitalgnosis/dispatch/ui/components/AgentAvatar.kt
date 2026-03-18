package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Circular avatar for an agent or department.
 * Displays initials on a consistent, color-coded background.
 */
@Composable
fun AgentAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val identity = getAgentIdentity(name)
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(identity.backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = identity.initials,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = identity.foregroundColor,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp,
                lineHeight = (size.value * 0.4f).sp
            )
        )
    }
}

/**
 * Visual identity for an agent (initials + color scheme).
 */
data class AgentIdentity(
    val initials: String,
    val backgroundColor: Color,
    val foregroundColor: Color = Color.White
)

/**
 * Maps a department/agent name to a consistent visual identity.
 * Same name always returns same color.
 */
fun getAgentIdentity(name: String): AgentIdentity {
    val cleanName = name.trim().lowercase()
    
    // Generate initials: "prompt-engine" -> "PE", "boardroom" -> "B"
    val initials = when {
        cleanName.contains("-") -> {
            val parts = cleanName.split("-")
            (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
        cleanName.contains(" ") -> {
            val parts = cleanName.split(" ")
            (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
        cleanName.length >= 2 -> cleanName.take(2).uppercase()
        else -> cleanName.take(1).uppercase()
    }

    // Hash name to pick a consistent color from a modern palette
    val colors = listOf(
        Color(0xFFEF5350), // Red
        Color(0xFFEC407A), // Pink
        Color(0xFFAB47BC), // Purple
        Color(0xFF7E57C2), // Deep Purple
        Color(0xFF5C6BC0), // Indigo
        Color(0xFF42A5F5), // Blue
        Color(0xFF29B6F6), // Light Blue
        Color(0xFF26C6DA), // Cyan
        Color(0xFF26A69A), // Teal
        Color(0xFF66BB6A), // Green
        Color(0xFF9CCC65), // Light Green
        Color(0xFFD4E157), // Lime
        Color(0xFFFFEE58), // Yellow
        Color(0xFFFFCA28), // Amber
        Color(0xFFFFA726), // Orange
        Color(0xFFFF7043), // Deep Orange
    )
    
    val hash = cleanName.hashCode()
    val colorIndex = Math.abs(hash) % colors.size
    val bgColor = colors[colorIndex]
    
    // Use dark text for light backgrounds
    val isLightColor = colorIndex in 11..14 // Lime, Yellow, Amber, Orange
    val fgColor = if (isLightColor) Color(0xFF121212) else Color.White

    return AgentIdentity(initials, bgColor, fgColor)
}

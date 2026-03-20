package dev.digitalgnosis.dispatch.ui.components

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class JumpVisibility { VISIBLE, GONE }

@Composable
fun JumpToBottom(enabled: Boolean, onClicked: () -> Unit, modifier: Modifier = Modifier) {
    val transition = updateTransition(
        targetState = if (enabled) JumpVisibility.VISIBLE else JumpVisibility.GONE,
        label = "JumpToBottom visibility"
    )
    val bottomOffset by transition.animateDp(label = "JumpToBottom offset") {
        if (it == JumpVisibility.GONE) (-32).dp else 32.dp
    }
    if (bottomOffset > 0.dp) {
        ExtendedFloatingActionButton(
            icon = {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            },
            text = { Text("Jump to bottom") },
            onClick = onClicked,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .offset(x = 0.dp, y = -bottomOffset)
                .height(36.dp)
        )
    }
}

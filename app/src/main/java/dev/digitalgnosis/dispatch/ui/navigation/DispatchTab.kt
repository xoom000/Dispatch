package dev.digitalgnosis.dispatch.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class DispatchTab(
    val label: String,
    val icon: ImageVector,
) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    PULSE("Pulse", Icons.Default.GraphicEq),
    BOARD("Board", Icons.Default.Dashboard),
    GEMINI("Gemini", Icons.Default.Terminal),
}

@Composable
fun BottomNavBar(
    currentTab: DispatchTab,
    onTabSelected: (DispatchTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        DispatchTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}

package dev.digitalgnosis.dispatch.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class DispatchTab(
    val label: String,
    val icon: ImageVector,
) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    PULSE("Pulse", Icons.Default.GraphicEq),
    BOARD("Board", Icons.Default.Dashboard),
    GEMINI("Gemini", Icons.Default.Terminal),
}

/** Bottom navigation bar — used on compact/phone layouts. */
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

/**
 * Navigation rail — used on medium/expanded (tablet) layouts.
 *
 * Shown on the leading edge of the screen instead of a bottom bar,
 * following Material3 adaptive navigation guidance.
 *
 * Pattern: Bitwarden BitwardenNavigationRail.kt
 */
@Composable
fun DispatchNavigationRail(
    currentTab: DispatchTab,
    onTabSelected: (DispatchTab) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        DispatchTab.entries.forEach { tab ->
            NavigationRailItem(
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

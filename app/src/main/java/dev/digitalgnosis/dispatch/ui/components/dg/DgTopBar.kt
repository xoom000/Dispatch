package dev.digitalgnosis.dispatch.ui.components.dg

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.digitalgnosis.dispatch.ui.theme.LocalDgColorScheme

/**
 * DgTopBar — semantic top app bar from the DG design system.
 *
 * Uses [LocalDgColorScheme] tokens for container and content colors so the bar
 * stays consistent with the rest of the Dispatch UI when the color scheme changes.
 *
 * @param title Title text shown in the bar.
 * @param navigationIcon Optional leading icon (e.g. back arrow). Slot composable.
 * @param actions Trailing action icons. Slot composable.
 * @param modifier Optional modifier for the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DgTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalDgColorScheme.current
    TopAppBar(
        title = { Text(text = title, color = colors.textPrimary) },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.backgroundSurface,
            titleContentColor = colors.textPrimary,
            navigationIconContentColor = colors.iconPrimary,
            actionIconContentColor = colors.iconPrimary,
        ),
    )
}

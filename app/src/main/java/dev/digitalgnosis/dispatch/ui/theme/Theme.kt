package dev.digitalgnosis.dispatch.ui.theme

import androidx.compose.runtime.Composable

/**
 * DispatchTheme — top-level theme wrapper.
 *
 * Delegates to DgTheme which provides both MaterialTheme and the semantic DgColorScheme
 * via CompositionLocal. Kept for backward compatibility with existing call sites.
 */
@Composable
fun DispatchTheme(content: @Composable () -> Unit) {
    DgTheme(content = content)
}

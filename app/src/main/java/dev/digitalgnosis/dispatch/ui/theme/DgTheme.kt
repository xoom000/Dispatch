package dev.digitalgnosis.dispatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * DgTheme — Dispatch design system entry point.
 *
 * Wraps MaterialTheme and provides the semantic DgColorScheme via CompositionLocal.
 * All Dispatch UI should be wrapped in DgTheme (or DispatchTheme which delegates here).
 *
 * Usage:
 * ```kotlin
 * DgTheme {
 *     val colors = LocalDgColorScheme.current
 *     Text(color = colors.textPrimary, ...)
 * }
 * ```
 */
@Composable
fun DgTheme(
    colorScheme: DgColorScheme = DgDarkColorScheme,
    content: @Composable () -> Unit,
) {
    val materialColorScheme = darkColorScheme(
        primary = colorScheme.brandPrimary,
        onPrimary = colorScheme.brandOnPrimary,
        primaryContainer = colorScheme.brandPrimaryContainer,
        onPrimaryContainer = colorScheme.brandOnPrimaryContainer,
        secondary = Secondary,
        secondaryContainer = SecondaryContainer,
        tertiary = Tertiary,
        tertiaryContainer = TertiaryContainer,
        error = Error,
        errorContainer = ErrorContainer,
        background = colorScheme.backgroundBase,
        onBackground = colorScheme.textPrimary,
        surface = colorScheme.backgroundBase,
        surfaceDim = SurfaceDim,
        surfaceBright = SurfaceBright,
        surfaceContainerLowest = SurfaceContainerLowest,
        surfaceContainerLow = SurfaceContainerLow,
        surfaceContainer = colorScheme.backgroundSurface,
        surfaceContainerHigh = colorScheme.backgroundElevated,
        surfaceContainerHighest = colorScheme.backgroundHighest,
        onSurface = colorScheme.textPrimary,
        onSurfaceVariant = colorScheme.textSecondary,
        outline = colorScheme.strokeDefault,
        outlineVariant = colorScheme.strokeSubtle,
    )

    CompositionLocalProvider(
        LocalDgColorScheme provides colorScheme,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/** Convenience accessor matching Bitwarden's DgTheme.colorScheme pattern. */
object DgThemeAccessor {
    val colorScheme: DgColorScheme
        @Composable get() = LocalDgColorScheme.current
}

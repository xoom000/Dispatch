package dev.digitalgnosis.dispatch.ui.theme

import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
// import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ExtendedWidthSizeClass - Full Material3 Adaptive v1.2.0 breakpoints
 *
 * Official Android 16 breakpoints include Large and Extra-Large for external monitors:
 * - Compact: < 600dp (phones)
 * - Medium: 600-839dp (tablets portrait, foldables)
 * - Expanded: 840-1199dp (tablets landscape)
 * - Large: 1200-1599dp (large monitors, desktop)
 * - ExtraLarge: >= 1600dp (extra-large monitors, 4K displays)
 */
enum class ExtendedWidthSizeClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge
}

/**
 * DisplayDimensions - Responsive dimension system for Display Companion screens
 *
 * Provides orientation-aware dimensions for external displays (HDMI monitors, tablets).
 * Portrait displays get scaled-down typography and adjusted spacing to fit content
 * properly in the narrower horizontal space.
 *
 * Usage:
 * ```kotlin
 * val dims = LocalDisplayDimensions.current
 * Text(
 *     text = customerName,
 *     fontSize = dims.customerNameSize
 * )
 * Column(modifier = Modifier.padding(dims.screenPadding)) { ... }
 * ```
 *
 * Provided via CompositionLocal in:
 * - DisplayCompanionPresentation (wired HDMI displays)
 * - DisplayCompanionScreen (wireless tablet displays)
 */
data class DisplayDimensions(
    val isPortrait: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float
) {
    // Calculated dp values
    val widthDp: Dp get() = (widthPx / density).dp
    val heightDp: Dp get() = (heightPx / density).dp

    // ============================================================================
    // WINDOW SIZE CLASS - Modern Android 16 adaptive breakpoints
    // ============================================================================

    /**
     * ExtendedWidthSizeClass based on Material3 Adaptive v1.2.0 breakpoints:
     * - Compact: < 600dp (phones)
     * - Medium: 600-839dp (tablets portrait, foldables)
     * - Expanded: 840-1199dp (tablets landscape)
     * - Large: 1200-1599dp (large monitors, desktop windowing)
     * - ExtraLarge: >= 1600dp (extra-large monitors, 4K displays)
     *
     * Use this for Display Companion layouts targeting external monitors.
     */
    val extendedWidthSizeClass: ExtendedWidthSizeClass get() = when {
        widthDp < 600.dp -> ExtendedWidthSizeClass.Compact
        widthDp < 840.dp -> ExtendedWidthSizeClass.Medium
        widthDp < 1200.dp -> ExtendedWidthSizeClass.Expanded
        widthDp < 1600.dp -> ExtendedWidthSizeClass.Large
        else -> ExtendedWidthSizeClass.ExtraLarge
    }

    /**
     * WindowWidthSizeClass for backward compatibility with existing code.
     * Maps Large and ExtraLarge to Expanded since Material3's base library
     * only has 3 tiers.
     */
    /*
    val widthSizeClass: WindowWidthSizeClass get() = when (extendedWidthSizeClass) {
        ExtendedWidthSizeClass.Compact -> WindowWidthSizeClass.Compact
        ExtendedWidthSizeClass.Medium -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded  // Expanded, Large, ExtraLarge all map to Expanded
    }
    */

    // ============================================================================
    // TYPOGRAPHY - Scaled for portrait to fit narrower screens
    // ============================================================================

    /** Customer name - THE BIGGEST (72sp landscape, 48sp portrait) */
    val customerNameSize: TextUnit = if (isPortrait) 48.sp else 72.sp

    /** Stop number, distance - very large (48sp landscape, 36sp portrait) */
    val stopNumberSize: TextUnit = if (isPortrait) 36.sp else 48.sp

    /** Section headers (36sp landscape, 28sp portrait) */
    val sectionHeaderSize: TextUnit = if (isPortrait) 28.sp else 36.sp

    /** Load list items, notes (32sp landscape, 24sp portrait) */
    val headlineSize: TextUnit = if (isPortrait) 24.sp else 32.sp

    /** Secondary headlines (28sp landscape, 22sp portrait) */
    val headlineMediumSize: TextUnit = if (isPortrait) 22.sp else 28.sp

    /** Body text (24sp landscape, 20sp portrait) */
    val bodySize: TextUnit = if (isPortrait) 20.sp else 24.sp

    /** Clock display - extra large for idle screen (120sp landscape, 80sp portrait) */
    val clockSize: TextUnit = if (isPortrait) 80.sp else 120.sp

    // ============================================================================
    // SPACING - Tighter in portrait to maximize vertical real estate
    // ============================================================================

    /** Screen edge padding (24dp landscape, 16dp portrait) */
    val screenPadding: Dp = if (isPortrait) 16.dp else 24.dp

    /** Space between major sections (24dp landscape, 16dp portrait) */
    val sectionSpacing: Dp = if (isPortrait) 16.dp else 24.dp

    /** Space between items in lists (8dp landscape, 6dp portrait) */
    val itemSpacing: Dp = if (isPortrait) 6.dp else 8.dp

    /** Card internal padding (16dp landscape, 12dp portrait) */
    val cardPadding: Dp = if (isPortrait) 12.dp else 16.dp

    /** Inner element spacing (16dp landscape, 12dp portrait) */
    val elementSpacing: Dp = if (isPortrait) 12.dp else 16.dp

    // ============================================================================
    // COMPONENT SIZES - Slightly smaller touch targets in portrait
    // ============================================================================

    /** Checkbox/toggle size (48dp landscape, 40dp portrait) */
    val checkboxSize: Dp = if (isPortrait) 40.dp else 48.dp

    /** Primary icon size (36dp landscape, 28dp portrait) */
    val iconSize: Dp = if (isPortrait) 28.dp else 36.dp

    /** Large icon size (40dp landscape, 32dp portrait) */
    val iconLargeSize: Dp = if (isPortrait) 32.dp else 40.dp

    /** Extra large icon for emphasis (56dp landscape, 48dp portrait) */
    val iconXLargeSize: Dp = if (isPortrait) 48.dp else 56.dp

    /** Hero icons for empty states (120dp landscape, 80dp portrait) */
    val iconHeroSize: Dp = if (isPortrait) 80.dp else 120.dp

    /** Progress bar height (12dp landscape, 10dp portrait) */
    val progressBarHeight: Dp = if (isPortrait) 10.dp else 12.dp

    /** Stop number badge size (56dp landscape, 48dp portrait) */
    val stopBadgeSize: Dp = if (isPortrait) 48.dp else 56.dp

    // ============================================================================
    // LAYOUT DECISIONS
    // ============================================================================

    /** Whether to use side-by-side layout (landscape only) */
    val useSideBySideLayout: Boolean = !isPortrait

    /** Load list section weight when side-by-side (0.6 = 60% of width) */
    val loadListWeight: Float = 0.6f

    /** Notes section weight when side-by-side (0.4 = 40% of width) */
    val notesSectionWeight: Float = 0.4f

    // ============================================================================
    // FRACTIONAL SIZING - Use for constraint-based layouts
    // ============================================================================

    /** Barcode section width as fraction of screen (25% portrait, 20% landscape) */
    val barcodeWidthFraction: Float = if (isPortrait) 0.25f else 0.20f

    /** Header text section weight (complement of barcode) */
    val headerTextWeight: Float = 1f - barcodeWidthFraction

    /** Chip horizontal padding relative to card padding */
    val chipPaddingHorizontal: Dp = cardPadding * 0.75f

    /** Chip vertical padding relative to item spacing */
    val chipPaddingVertical: Dp = itemSpacing

    /** Chip icon size - slightly smaller than regular icon */
    val chipIconSize: Dp = iconSize * 0.7f

    /** Small text size for chips and labels */
    val labelSize: TextUnit = if (isPortrait) 14.sp else 16.sp

    // ============================================================================
    // GRID LAYOUT - Responsive columns for load list
    // ============================================================================

    /**
     * Grid columns for load list based on ExtendedWidthSizeClass.
     * Large and ExtraLarge monitors get 4 columns to use available space.
     */
    val loadListGridColumns: Int get() = when (extendedWidthSizeClass) {
        ExtendedWidthSizeClass.Compact -> 1    // < 600dp (phones)
        ExtendedWidthSizeClass.Medium -> 2     // 600-839dp (tablets portrait, foldables)
        ExtendedWidthSizeClass.Expanded -> 3   // 840-1199dp (tablets landscape)
        ExtendedWidthSizeClass.Large,
        ExtendedWidthSizeClass.ExtraLarge -> 4 // >= 1200dp (large monitors, 4K)
    }

    /** Grid item spacing */
    val gridItemSpacing: Dp = if (isPortrait) 8.dp else 12.dp

    companion object {
        /**
         * Create DisplayDimensions from Android Display object (for Presentation)
         */
        fun fromDisplay(display: Display): DisplayDimensions {
            val (widthPx, heightPx, density) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: Use Display.Mode for dimensions
                val mode = display.mode
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getMetrics(metrics) // Still needed for density
                Triple(mode.physicalWidth, mode.physicalHeight, metrics.density)
            } else {
                // API 26-29: Use deprecated getRealMetrics
                @Suppress("DEPRECATION")
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                Triple(metrics.widthPixels, metrics.heightPixels, metrics.density)
            }
            return DisplayDimensions(
                isPortrait = heightPx > widthPx,
                widthPx = widthPx,
                heightPx = heightPx,
                density = density
            )
        }

        /**
         * Create DisplayDimensions from Android Configuration (for tablet screens)
         */
        fun fromConfiguration(configuration: Configuration, density: Float): DisplayDimensions {
            val widthDp = configuration.screenWidthDp
            val heightDp = configuration.screenHeightDp
            return DisplayDimensions(
                isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
                widthPx = (widthDp * density).toInt(),
                heightPx = (heightDp * density).toInt(),
                density = density
            )
        }

        /**
         * Default landscape dimensions for fallback
         */
        val DEFAULT = DisplayDimensions(
            isPortrait = false,
            widthPx = 1920,
            heightPx = 1080,
            density = 1f
        )
    }
}

/**
 * ContainerTypography - True container-aware typography calculated from actual constraints.
 *
 * Instead of hardcoded sp values multiplied by guesses, this measures actual container
 * dimensions and calculates optimal font sizes to fill the space.
 *
 * Usage:
 * ```kotlin
 * BoxWithConstraints {
 *     val typography = ContainerTypography.calculate(
 *         containerHeightDp = maxHeight.value,
 *         itemCount = items.size
 *     )
 *     Text(fontSize = typography.fontSize)
 * }
 * ```
 */
data class ContainerTypography(
    val baseFontSizeSp: Float,
    val lineHeightSp: Float,
    val containerHeightDp: Float,
    val itemSpacingSp: Float
) {
    /** Font size as TextUnit for Compose Text components */
    val fontSize: TextUnit get() = baseFontSizeSp.sp

    /** Line height as TextUnit */
    val lineHeight: TextUnit get() = lineHeightSp.sp

    /** Item spacing as Dp */
    val itemSpacing: Dp get() = itemSpacingSp.dp

    /** Derived checkbox size proportional to font */
    val checkboxSize: Dp get() = (baseFontSizeSp * 1.2f).dp

    /** Derived icon size proportional to font */
    val iconSize: Dp get() = (baseFontSizeSp * 1.0f).dp

    companion object {
        // Readability bounds
        const val MIN_FONT_SP = 16f   // Absolute minimum for legibility
        const val MAX_FONT_SP = 32f   // Comfortable reading size - not headline huge

        // Layout ratios
        const val LINE_HEIGHT_MULTIPLIER = 1.3f
        const val ITEM_SPACING_RATIO = 0.5f  // Half of font size

        /**
         * Calculate optimal typography for given container constraints.
         *
         * @param containerHeightDp Available height in dp
         * @param itemCount Number of items to display
         * @param paddingDp Total vertical padding already consumed (header, margins)
         * @param columnCount Number of columns (for multi-column layouts)
         */
        fun calculate(
            containerHeightDp: Float,
            itemCount: Int,
            paddingDp: Float = 0f,
            columnCount: Int = 1
        ): ContainerTypography {
            val effectiveItemCount = itemCount.coerceAtLeast(1)
            val availableHeight = (containerHeightDp - paddingDp).coerceAtLeast(0f)

            // Items per column when using multi-column
            val itemsPerColumn = ((effectiveItemCount + columnCount - 1) / columnCount).coerceAtLeast(1)

            // Each CheckableItemRow needs:
            // - Row internal padding: 2 × itemSpacing (top + bottom) = 2 × 0.5 = 1.0
            // - Content height: lineHeight = 1.3
            // - Column spacing between items: itemSpacing = 0.5
            // Total per item = 1.0 + 1.3 + 0.5 = 2.8
            val perItemMultiplier = 2.8f

            val idealFontSize = availableHeight / (itemsPerColumn * perItemMultiplier)
            val clampedFontSize = idealFontSize.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

            return ContainerTypography(
                baseFontSizeSp = clampedFontSize,
                lineHeightSp = clampedFontSize * LINE_HEIGHT_MULTIPLIER,
                containerHeightDp = containerHeightDp,
                itemSpacingSp = clampedFontSize * ITEM_SPACING_RATIO
            )
        }

        /**
         * Determine optimal column count based on container width and item count.
         * Aligned with Material3 WindowSizeClass breakpoints:
         * - Compact: < 600dp → 1 column
         * - Medium: 600-839dp → 2 columns if enough items
         * - Expanded: >= 840dp → 2 columns
         */
        fun optimalColumnCount(containerWidthDp: Float, itemCount: Int): Int {
            return when {
                containerWidthDp < 600f -> 1       // Compact: single column
                itemCount < 4 -> 1                 // Not enough items to justify split
                containerWidthDp >= 840f -> 2     // Expanded: always 2 columns
                containerWidthDp >= 600f && itemCount >= 6 -> 2  // Medium with enough items
                else -> 1
            }
        }
    }
}

/**
 * CompositionLocal for accessing display dimensions throughout display UI tree.
 *
 * Must be provided by DisplayCompanionPresentation or DisplayCompanionScreen.
 */
val LocalDisplayDimensions = compositionLocalOf<DisplayDimensions> {
    // Fallback to default landscape if not provided (shouldn't happen in practice)
    DisplayDimensions.DEFAULT
}

package dev.digitalgnosis.dispatch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DgColorScheme — semantic color system for Dispatch.
 *
 * Mirrors the Bitwarden pattern: semantic categories instead of raw Material3 tokens.
 * All UI code should reference these semantically-named tokens rather than hardcoded hex values.
 *
 * Categories:
 * - text: content foreground
 * - background: surface fills
 * - stroke: borders and dividers
 * - icon: icon foreground
 * - status: state indicators (active/error/warning/neutral)
 * - bubble: chat bubble fills and text
 * - dept: department/channel accent colors
 */
@Immutable
data class DgColorScheme(
    // ── Text ─────────────────────────────────────────────────────────────
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val textLink: Color,

    // ── Background / Surface ─────────────────────────────────────────────
    val backgroundBase: Color,
    val backgroundSurface: Color,
    val backgroundElevated: Color,
    val backgroundHighest: Color,

    // ── Stroke / Outline ─────────────────────────────────────────────────
    val strokeDefault: Color,
    val strokeSubtle: Color,

    // ── Primary brand ────────────────────────────────────────────────────
    val brandPrimary: Color,
    val brandPrimaryContainer: Color,
    val brandOnPrimary: Color,
    val brandOnPrimaryContainer: Color,

    // ── Icon ─────────────────────────────────────────────────────────────
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconOnBrand: Color,

    // ── Status ───────────────────────────────────────────────────────────
    val statusActive: Color,
    val statusError: Color,
    val statusErrorDark: Color,
    val statusWarning: Color,
    val statusNeutral: Color,
    val statusParked: Color,

    // ── Chat Bubbles ─────────────────────────────────────────────────────
    val bubbleDispatchFill: Color,
    val bubbleDispatchText: Color,
    val bubbleToolFill: Color,
    val bubbleToolText: Color,
    val bubbleGeminiFill: Color,

    // ── Input accents ────────────────────────────────────────────────────
    val inputFocusGlow: Color,
)

/** Default dark color scheme wired to existing Color.kt tokens. */
val DgDarkColorScheme = DgColorScheme(
    // Text
    textPrimary = OnSurface,
    textSecondary = OnSurfaceVariant,
    textDisabled = Outline,
    textLink = Primary,

    // Background
    backgroundBase = Surface,
    backgroundSurface = SurfaceContainer,
    backgroundElevated = SurfaceContainerHigh,
    backgroundHighest = SurfaceContainerHighest,

    // Stroke
    strokeDefault = OutlineVariant,
    strokeSubtle = Color(0xFF2A2C2E),

    // Brand
    brandPrimary = Primary,
    brandPrimaryContainer = PrimaryContainer,
    brandOnPrimary = OnPrimary,
    brandOnPrimaryContainer = OnPrimaryContainer,

    // Icons
    iconPrimary = OnSurface,
    iconSecondary = OnSurfaceVariant,
    iconOnBrand = OnPrimary,

    // Status
    statusActive = DgStatusActive,
    statusError = DgStatusError,
    statusErrorDark = DgStatusErrorDark,
    statusWarning = DgStatusWarning,
    statusNeutral = DgStatusNeutral,
    statusParked = DgStatusParked,

    // Bubbles
    bubbleDispatchFill = DgDispatchBubble,
    bubbleDispatchText = DgDispatchText,
    bubbleToolFill = DgToolBubble,
    bubbleToolText = DgToolText,
    bubbleGeminiFill = DgGeminiBubble,

    // Input
    inputFocusGlow = DgNeonCyan,
)

/** CompositionLocal provider for DgColorScheme. Crashes clearly if not provided. */
val LocalDgColorScheme = staticCompositionLocalOf<DgColorScheme> {
    error("LocalDgColorScheme not provided — wrap with DgTheme { }")
}

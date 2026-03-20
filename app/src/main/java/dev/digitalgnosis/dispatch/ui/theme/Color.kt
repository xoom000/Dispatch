package dev.digitalgnosis.dispatch.ui.theme

import androidx.compose.ui.graphics.Color

// Surfaces
val Surface = Color(0xFF0A0A0C)
val SurfaceDim = Color(0xFF0A0A0C)
val SurfaceBright = Color(0xFF37393B)
val SurfaceContainerLowest = Color(0xFF080808)
val SurfaceContainerLow = Color(0xFF121212)
val SurfaceContainer = Color(0xFF161618)
val SurfaceContainerHigh = Color(0xFF1E2022)
val SurfaceContainerHighest = Color(0xFF282A2C)

// Text / On-Surface
val OnSurface = Color(0xFFE3E3E3)
val OnSurfaceVariant = Color(0xFFC4C7C5)
val Outline = Color(0xFF8E918F)
val OutlineVariant = Color(0xFF444746)

// Primary (Google Blue)
val Primary = Color(0xFFA8C7FA)
val PrimaryContainer = Color(0xFF0842A0)
val OnPrimary = Color(0xFF041E49)
val OnPrimaryContainer = Color(0xFFD3E3FD)

// Secondary
val Secondary = Color(0xFFC4C7C5)
val SecondaryContainer = Color(0xFF444746)

// Tertiary (Green)
val Tertiary = Color(0xFF6DD58C)
val TertiaryContainer = Color(0xFF0F5223)

// Error
val Error = Color(0xFFF2B8B5)
val ErrorContainer = Color(0xFF8C1D18)

// DG-specific bubble colors
val DgDispatchBubble = Color(0xFF0A1A3A)
val DgDispatchText = Color(0xFF80BFFF)
val DgToolBubble = Color(0xFF12141A)
val DgToolText = Color(0xFF689B7A)

// ── Semantic Status Colors ──────────────────────────────────────────
// Use these instead of hardcoded hex values throughout the app.
val DgStatusActive = Color(0xFF4CAF50)       // Green 500 — active, success, live, running
val DgStatusError = Color(0xFFFF5252)        // Red A200 — error, blocked, high priority, failed
val DgStatusErrorDark = Color(0xFFE53935)    // Red 600 — error in context bar, failed events
val DgStatusErrorDeep = Color(0xFFB71C1C)    // Red 900 — assert-level log severity
val DgStatusWarning = Color(0xFFFFA726)      // Orange 400 — warning, ended, caution
val DgStatusWarningAlt = Color(0xFFFF9800)   // Orange 500 — log warning level
val DgStatusNeutral = Color(0xFF9E9E9E)      // Grey 500 — done, completed, default, inactive
val DgStatusParked = Color(0xFFFFCA28)       // Amber 400 — parked, paused, boardroom accent

// ── Department / Channel Colors ─────────────────────────────────────
val DgDeptEngineering = Color(0xFF42A5F5)    // Blue 400
val DgDeptResearch = Color(0xFF26C6DA)       // Cyan 400
val DgDeptBoardroom = Color(0xFFAB47BC)      // Purple 400
val DgDeptHunter = Color(0xFFFF7043)         // Deep Orange 400
val DgDeptDispatch = Color(0xFF66BB6A)       // Green 400
val DgDeptIT = Color(0xFF78909C)             // Blue Grey 400
val DgDeptNigel = Color(0xFFFFCA28)          // Amber 400
val DgDeptDefault = Color(0xFF90A4AE)        // Blue Grey 300
val DgChannelActivity = Color(0xFF26A69A)    // Teal 400
val DgChannelCmail = Color(0xFF5C6BC0)       // Indigo 400

// ── Gemini-specific ─────────────────────────────────────────────────
val DgGeminiBubble = Color(0xFF004D40)       // Teal 900

// ── Input / UI Accents ───────────────────────────────────────────────
val DgNeonCyan = Color(0xFF00E5FF)           // Cyan A400 — input pill focus glow

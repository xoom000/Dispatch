package dev.digitalgnosis.dispatch.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Unified time formatting utilities used across all screens.
 * All functions handle null/blank/parse errors by returning empty string.
 */

/**
 * Formats an ISO timestamp as a relative human-readable age.
 * Examples: "just now", "2m ago", "1h ago", "Yesterday", "Mar 12"
 */
fun formatRelativeTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    return try {
        val date = parseIso(isoTimestamp) ?: return ""
        val diffMs = System.currentTimeMillis() - date.time
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            hours < 48 -> "Yesterday"
            else -> SimpleDateFormat("MMM d", Locale.US).format(date)
        }
    } catch (_: Exception) { "" }
}

/**
 * Formats an ISO timestamp smartly based on recency.
 * Today → "14:30", Yesterday → "Yesterday", This week → "Mon", Older → "Mar 12"
 */
fun formatSmartTimestamp(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    return try {
        val date = parseIso(isoTimestamp) ?: return ""
        val diffMs = System.currentTimeMillis() - date.time
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        when {
            hours < 24 -> SimpleDateFormat("HH:mm", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(date)
            hours < 48 -> "Yesterday"
            days < 7 -> SimpleDateFormat("EEE", Locale.US).format(date)
            else -> SimpleDateFormat("MMM d", Locale.US).format(date)
        }
    } catch (_: Exception) { "" }
}

/**
 * Always returns the time of day in "HH:mm" format (local time).
 */
fun formatTimeOfDay(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    return try {
        val date = parseIso(isoTimestamp) ?: return ""
        SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(date)
    } catch (_: Exception) { "" }
}

/**
 * Formats a unix epoch (seconds) as a relative compact timestamp.
 * Examples: "now", "5m", "3h", "2d", "Mar 12"
 */
fun formatRelativeAge(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val nowMs = System.currentTimeMillis()
    val postMs = epochSeconds * 1000L
    val diffMs = nowMs - postMs
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
    val days = TimeUnit.MILLISECONDS.toDays(diffMs)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(postMs))
    }
}

/**
 * Compact relative timestamp from an ISO string — no "ago" suffix.
 * Examples: "now", "5m", "3h", then falls back to "HH:mm" for anything older than 24h.
 * Used for dense feeds like EventFeedScreen where space is tight.
 */
fun formatCompactTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""
    return try {
        val date = parseIso(isoTimestamp) ?: return ""
        val diffMs = System.currentTimeMillis() - date.time
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            else -> SimpleDateFormat("HH:mm", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(date)
        }
    } catch (_: Exception) { "" }
}

/**
 * Returns the age of an ISO timestamp in milliseconds. Returns -1 on parse failure.
 * Useful for comparison logic (e.g., "is this thread < 24h old?").
 */
fun isoAgeMs(isoTimestamp: String): Long {
    if (isoTimestamp.isBlank()) return -1L
    return try {
        val date = parseIso(isoTimestamp) ?: return -1L
        System.currentTimeMillis() - date.time
    } catch (_: Exception) { -1L }
}

// ─── Internal helpers ─────────────────────────────────────────────────────────

private val ISO_FORMATS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss",
)

/**
 * Attempts to parse an ISO-8601 timestamp string using common format variants.
 * Returns null if all attempts fail.
 */
private fun parseIso(iso: String): Date? {
    for (pattern in ISO_FORMATS) {
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(iso)
            if (date != null) return date
        } catch (_: Exception) {
            // Try next format
        }
    }
    return null
}

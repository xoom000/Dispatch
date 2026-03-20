package dev.digitalgnosis.dispatch.util

fun formatModelName(model: String): String {
    return when {
        model.contains("opus-4-6") -> "Opus 4.6"
        model.contains("opus-4-5") -> "Opus 4.5"
        model.contains("sonnet-4-6") -> "Sonnet 4.6"
        model.contains("sonnet-4-5") -> "Sonnet 4.5"
        model.contains("haiku-4-5") -> "Haiku 4.5"
        model.contains("opus") -> "Opus"
        model.contains("sonnet") -> "Sonnet"
        model.contains("haiku") -> "Haiku"
        else -> model.take(20)
    }
}

fun formatActivityTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = sdf.parse(iso) ?: return iso

        val now = System.currentTimeMillis()
        val diff = now - date.time
        val hours = diff / (1000 * 60 * 60)
        val minutes = diff / (1000 * 60)

        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            hours < 48 -> "yesterday"
            else -> {
                val display = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                display.format(date)
            }
        }
    } catch (_: Exception) {
        iso
    }
}

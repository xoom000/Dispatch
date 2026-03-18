package dev.digitalgnosis.dispatch.data

/**
 * A single Pulse post from a channel.
 * Matches the shape returned by /pulse/feed and /pulse/channel/{name}.
 */
data class PulsePost(
    val ts: Long,
    val dept: String,
    val msg: String,
    val tags: List<String> = emptyList(),
    val channel: String = "",
)

/**
 * Channel metadata from /pulse/channels.
 */
data class PulseChannel(
    val name: String,
    val postCount: Int,
    val latestTs: Long = 0,
)

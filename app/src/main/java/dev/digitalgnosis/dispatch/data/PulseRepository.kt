package dev.digitalgnosis.dispatch.data

/**
 * Domain interface for Pulse feed data.
 */
interface PulseRepository {

    fun fetchPulseFeed(
        hours: Int = 48,
        limit: Int = 50,
        department: String? = null,
        tag: String? = null,
    ): PulseFeedResult

    fun fetchPulseChannels(): PulseChannelsResult

    fun fetchPulseChannel(
        channelName: String,
        hours: Int = 48,
        limit: Int = 50,
    ): PulseFeedResult

    data class PulseFeedResult(val posts: List<PulsePost>, val total: Int)
    data class PulseChannelsResult(val channels: List<PulseChannel>)
}

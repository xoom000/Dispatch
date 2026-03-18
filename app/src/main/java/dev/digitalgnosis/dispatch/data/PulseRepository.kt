package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Pulse feed data.
 *
 * Fetches ambient awareness posts from all departments.
 */
@Singleton
class PulseRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Fetch the merged pulse feed across all channels, newest first.
     */
    fun fetchPulseFeed(
        hours: Int = 48,
        limit: Int = 50,
        department: String? = null,
        tag: String? = null,
    ): PulseFeedResult {
        val params = buildString {
            append("hours=$hours&limit=$limit")
            if (department != null) append("&department=$department")
            if (tag != null) append("&tag=$tag")
        }
        
        val body = client.get("pulse/feed?$params") ?: return PulseFeedResult(emptyList(), 0)
        
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("posts") ?: JSONArray()
            val posts = mutableListOf<PulsePost>()
            for (i in 0 until arr.length()) {
                posts.add(parsePulsePost(arr.getJSONObject(i)))
            }
            PulseFeedResult(posts, json.optInt("total", posts.size))
        } catch (e: Exception) {
            Timber.e(e, "PulseFeed: parse failed")
            PulseFeedResult(emptyList(), 0)
        }
    }

    /**
     * Fetch list of available pulse channels with post counts.
     */
    fun fetchPulseChannels(): PulseChannelsResult {
        val body = client.get("pulse/channels") ?: return PulseChannelsResult(emptyList())
        
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("channels") ?: JSONArray()
            val channels = mutableListOf<PulseChannel>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                channels.add(PulseChannel(
                    name = c.optString("name", ""),
                    postCount = c.optInt("post_count", 0),
                    latestTs = c.optLong("latest_ts", 0),
                ))
            }
            PulseChannelsResult(channels)
        } catch (e: Exception) {
            Timber.e(e, "PulseChannels: parse failed")
            PulseChannelsResult(emptyList())
        }
    }

    /**
     * Fetch posts from a specific pulse channel.
     */
    fun fetchPulseChannel(
        channelName: String,
        hours: Int = 48,
        limit: Int = 50,
    ): PulseFeedResult {
        val params = "hours=$hours&limit=$limit"
        val body = client.get("pulse/channel/$channelName?$params") ?: return PulseFeedResult(emptyList(), 0)
        
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("posts") ?: JSONArray()
            val posts = mutableListOf<PulsePost>()
            for (i in 0 until arr.length()) {
                posts.add(parsePulsePost(arr.getJSONObject(i), channelName))
            }
            PulseFeedResult(posts, json.optInt("total", posts.size))
        } catch (e: Exception) {
            Timber.e(e, "PulseChannel[$channelName]: parse failed")
            PulseFeedResult(emptyList(), 0)
        }
    }

    private fun parsePulsePost(p: JSONObject, defaultChannel: String = ""): PulsePost {
        val tagsArr = p.optJSONArray("tags")
        val tags = mutableListOf<String>()
        if (tagsArr != null) {
            for (t in 0 until tagsArr.length()) {
                tags.add(tagsArr.getString(t))
            }
        }
        return PulsePost(
            ts = p.optLong("ts", 0),
            dept = p.optString("dept", ""),
            msg = p.optString("msg", ""),
            tags = tags,
            channel = p.optString("channel", defaultChannel),
        )
    }

    /** Data wrapper for pulse feed responses. */
    data class PulseFeedResult(val posts: List<PulsePost>, val total: Int)
    
    /** Data wrapper for pulse channels responses. */
    data class PulseChannelsResult(val channels: List<PulseChannel>)
}

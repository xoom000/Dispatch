package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [PulseRepository].
 * Injected via [PulseRepository] interface — never inject this class directly.
 */
@Singleton
class PulseRepositoryImpl @Inject constructor(
    private val client: BaseFileBridgeClient
) : PulseRepository {

    override fun fetchPulseFeed(
        hours: Int,
        limit: Int,
        department: String?,
        tag: String?,
    ): PulseRepository.PulseFeedResult {
        val params = buildString {
            append("hours=$hours&limit=$limit")
            if (department != null) append("&department=$department")
            if (tag != null) append("&tag=$tag")
        }
        Timber.d("PulseRepo: fetchPulseFeed — requesting (hours=%d, limit=%d, dept=%s)", hours, limit, department)
        val body = client.get("pulse/feed?$params") ?: return PulseRepository.PulseFeedResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("posts") ?: JSONArray()
            val posts = mutableListOf<PulsePost>()
            for (i in 0 until arr.length()) {
                posts.add(parsePulsePost(arr.getJSONObject(i)))
            }
            val result = PulseRepository.PulseFeedResult(posts, json.optInt("total", posts.size))
            Timber.d("PulseRepo: fetchPulseFeed — got %d posts (total=%d)", result.posts.size, result.total)
            result
        } catch (e: Exception) {
            Timber.e(e, "PulseRepo: fetchPulseFeed parse failed")
            PulseRepository.PulseFeedResult(emptyList(), 0)
        }
    }

    override fun fetchPulseChannels(): PulseRepository.PulseChannelsResult {
        Timber.d("PulseRepo: fetchPulseChannels — requesting")
        val body = client.get("pulse/channels") ?: return PulseRepository.PulseChannelsResult(emptyList())

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
            val result = PulseRepository.PulseChannelsResult(channels)
            Timber.d("PulseRepo: fetchPulseChannels — got %d channels", result.channels.size)
            result
        } catch (e: Exception) {
            Timber.e(e, "PulseRepo: fetchPulseChannels parse failed")
            PulseRepository.PulseChannelsResult(emptyList())
        }
    }

    override fun fetchPulseChannel(
        channelName: String,
        hours: Int,
        limit: Int,
    ): PulseRepository.PulseFeedResult {
        val params = "hours=$hours&limit=$limit"
        Timber.d("PulseRepo: fetchPulseChannel — requesting (channel=%s, hours=%d, limit=%d)", channelName, hours, limit)
        val body = client.get("pulse/channel/$channelName?$params") ?: return PulseRepository.PulseFeedResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("posts") ?: JSONArray()
            val posts = mutableListOf<PulsePost>()
            for (i in 0 until arr.length()) {
                posts.add(parsePulsePost(arr.getJSONObject(i), channelName))
            }
            val result = PulseRepository.PulseFeedResult(posts, json.optInt("total", posts.size))
            Timber.d("PulseRepo: fetchPulseChannel — got %d posts from %s (total=%d)", result.posts.size, channelName, result.total)
            result
        } catch (e: Exception) {
            Timber.e(e, "PulseRepo: fetchPulseChannel parse failed (channel=%s)", channelName)
            PulseRepository.PulseFeedResult(emptyList(), 0)
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
}

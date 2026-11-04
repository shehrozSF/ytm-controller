package com.shehrozsf.ytmcontroller

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val TAG = "Holodex"

/**
 * Holodex (holodex.net): index of VTuber channels and videos. There's no "similar songs"
 * data, so for a song on (or clipped from) a VTuber's channel it suggests that VTuber's
 * covers and originals, plus recent covers from their agency. Needs a free API key
 * (`holodex.apiKey` in secrets.properties); the key is only ever sent to holodex.net.
 */
object Holodex {
    private const val BASE = "https://holodex.net/api/v2"

    /**
     * Holodex's music topic IDs. Its docs don't list topics, so these come from the site's
     * topic filter; if they're wrong every music query comes back empty, which is logged.
     */
    private val MUSIC_TOPICS = listOf("Music_Cover", "Original_Song")

    val hasKey get() = BuildConfig.HOLODEX_API_KEY.isNotEmpty()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Tests the key with a one-channel listing: 401/403 means Holodex rejected it. */
    suspend fun checkKey(): KeyCheck {
        if (!hasKey) return KeyCheck.INVALID
        val status = httpsStatus("$BASE/channels?limit=1", holodexKey = BuildConfig.HOLODEX_API_KEY)
            ?: return KeyCheck.UNKNOWN
        return when (status) {
            401, 403 -> KeyCheck.INVALID
            in 200..299 -> KeyCheck.VALID
            else -> KeyCheck.UNKNOWN // e.g. 5xx: Holodex is having trouble, not a key verdict
        }
    }

    suspend fun related(videoId: String, limit: Int = 25): List<Suggestion> = coroutineScope {
        require(isValidVideoId(videoId))
        // 404 (not a video Holodex tracks) means "not a VTuber song": no suggestions.
        val video = getObject("$BASE/videos/$videoId?include=sources,mentions")
            ?: return@coroutineScope emptyList()
        val channelId = vtuberChannel(video) ?: return@coroutineScope emptyList()
        val org = getObject("$BASE/channels/${enc(channelId)}")?.optString("org")
            ?.takeIf { it.isNotBlank() && it != "Independents" }

        // Same VTuber's music first, then their agency's recent covers.
        val own = MUSIC_TOPICS.map { topic ->
            async { videos("channel_id=${enc(channelId)}&topic=$topic&limit=10") }
        }
        val agency = org?.let { async { videos("org=${enc(it)}&topic=Music_Cover&limit=20") } }
        val ownVideos = own.awaitAll().flatten().sortedByDescending { it.second }.map { it.first }
        val agencyVideos = agency?.await().orEmpty().map { it.first }

        val out = (ownVideos + agencyVideos)
            .filter { it.videoId != videoId }
            .distinctBy { it.videoId }
            .take(limit)
        Log.i(TAG, "$videoId → channel $channelId (org=$org): ${ownVideos.size} own, ${agencyVideos.size} agency")
        if (out.isEmpty()) Log.i(TAG, "no music videos found; check MUSIC_TOPICS ids $MUSIC_TOPICS")
        out
    }

    /**
     * The VTuber behind this video: its own channel, or for a fan clip, the channel of the
     * stream it was clipped from (or the first VTuber it mentions).
     */
    private fun vtuberChannel(video: JSONObject): String? {
        val channel = video.optJSONObject("channel")
        if (video.optString("type") != "clip" && channel?.optString("type") == "vtuber") return channel.optString("id")
        val fromSource = video.optJSONArray("sources")?.optJSONObject(0)?.optJSONObject("channel")?.optString("id")
        val fromMention = objects(video.optJSONArray("mentions")).firstOrNull { it.optString("type") == "vtuber" }?.optString("id")
        return (fromSource ?: fromMention)?.takeIf { it.isNotBlank() }
    }

    /** Past music videos matching [query], as (suggestion, publish time) pairs. */
    private suspend fun videos(query: String): List<Pair<Suggestion, String>> {
        val list = getArray("$BASE/videos?$query&type=stream&status=past") ?: return emptyList()
        return objects(list).mapNotNull { v ->
            val id = v.optString("id").takeIf(::isValidVideoId) ?: return@mapNotNull null
            val ch = v.optJSONObject("channel")
            val by = ch?.optString("english_name")?.takeIf { it.isNotBlank() } ?: ch?.optString("name").orEmpty()
            Suggestion(
                title = v.optString("title").trim().ifEmpty { return@mapNotNull null },
                artist = by,
                imageUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                videoId = id,
                sources = listOf(SuggestionSource.HOLODEX),
            ) to v.optString("published_at")
        }
    }

    private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

    private suspend fun getObject(url: String): JSONObject? =
        httpsRequest(url, holodexKey = BuildConfig.HOLODEX_API_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private suspend fun getArray(url: String): JSONArray? =
        httpsRequest(url, holodexKey = BuildConfig.HOLODEX_API_KEY)?.let { runCatching { JSONArray(it) }.getOrNull() }
}

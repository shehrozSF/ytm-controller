package com.shehrozsf.ytmcontroller

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

private const val TAG = "VideoIdLookup"
private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** Every ID we put into a URL or intent must pass this; responses are untrusted. */
fun isValidVideoId(id: String?) = id != null && VIDEO_ID.matches(id)

internal const val YTM_ORIGIN = "https://music.youtube.com"

/**
 * The anonymous YTM web client context shared by every unofficial request: no cookies or
 * tokens, so results are never personalised to the user's account.
 */
internal fun ytmContext(): JSONObject = JSONObject().put(
    "client",
    JSONObject()
        .put("clientName", "WEB_REMIX")
        .put("clientVersion", "1.20260101.01.00")
        .put("hl", "en"),
)

/**
 * Finds the YouTube video ID for a song, since YTM's MediaSession doesn't expose it.
 * Tries YTM's own (unofficial, free) search first, then the official Data API.
 */
object VideoIdLookup {
    /** This session's lookups (newest 500); [Caches] keeps them across restarts too. */
    private val cache = android.util.LruCache<String, String>(500)

    suspend fun find(title: String, artist: String, durationMs: Long): String? {
        val key = "${title.trim()}\u0000${artist.trim()}".lowercase()
        cache.get(key)?.let { return it }
        Caches.videoId(key)?.let { saved ->
            cache.put(key, saved)
            return saved
        }
        val id = runCatching { searchYtm(title, artist, durationMs) }
            .onFailure { Log.w(TAG, "YTM search failed", it) }.getOrNull()?.takeIf(::isValidVideoId)
            ?: runCatching { searchDataApi(title, artist) }
                .onFailure { Log.w(TAG, "Data API search failed", it) }.getOrNull()?.takeIf(::isValidVideoId)
        return id?.also {
            cache.put(key, it)
            Caches.putVideoId(key, it)
        }
    }

    private fun titleMatches(candidate: String, wanted: String): Boolean {
        val c = normalizeTitle(candidate)
        val w = normalizeTitle(wanted)
        return w.isNotEmpty() && (c == w || c.contains(w))
    }

    // --- Unofficial: YTM web client search (songs + videos). Can break without notice. ---

    private const val YTM_SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private val DURATION = Regex("^(\\d+:)?\\d{1,2}:\\d{2}$")

    private class Candidate(val videoId: String, val title: String, val durationMs: Long?)

    /** Search filter for "Videos" only, used when the mixed search finds no match. */
    private const val VIDEOS_FILTER = "EgWKAQIQAWoMEA4QChADEAQQCRAF"

    private suspend fun searchYtm(title: String, artist: String, durationMs: Long): String? =
        searchYtmOnce(title, artist, durationMs, params = null)
            ?: searchYtmOnce(title, artist, durationMs, params = VIDEOS_FILTER)

    private suspend fun searchYtmOnce(title: String, artist: String, durationMs: Long, params: String?): String? {
        // Built with JSONObject so the song text is always escaped, never spliced in.
        val body = JSONObject()
            .put("context", ytmContext())
            .put("query", "$title $artist")
        if (params != null) body.put("params", params)
        val json = httpsRequest(YTM_SEARCH, body.toString(), origin = YTM_ORIGIN) ?: return null

        val candidates = mutableListOf<Candidate>()
        collectRows(JSONObject(json), candidates, depth = 0)
        val titled = candidates.filter { titleMatches(it.title, title) }
        // Prefer a title + length match; YTM durations are whole seconds.
        val match = titled.firstOrNull { it.durationMs != null && abs(it.durationMs - durationMs) <= 3_000 }
            ?: titled.firstOrNull { it.durationMs == null || durationMs <= 0 }
        if (match == null) {
            Log.i(TAG, "no match for \"${logSafe(title)}\" (${durationMs}ms, filter=$params) among: " +
                logSafe(candidates.joinToString { "\"${it.title}\" ${it.durationMs}ms" }, 600))
        }
        return match?.videoId
    }

    /** Walks the response and collects every song/video row (and the top-result card). */
    private fun collectRows(node: Any?, out: MutableList<Candidate>, depth: Int) {
        if (depth > 40 || out.size >= 50) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { row -> parseRow(row)?.let(out::add) }
                node.optJSONObject("musicCardShelfRenderer")?.let { card -> parseTopCard(card)?.let(out::add) }
                for (k in node.keys()) collectRows(node.opt(k), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) collectRows(node.opt(i), out, depth + 1)
        }
    }

    private fun runTexts(runs: JSONArray?): List<String> =
        (0 until (runs?.length() ?: 0)).map { runs!!.optJSONObject(it)?.optString("text").orEmpty() }

    /** The "Top result" card: title runs carry the watch endpoint, subtitle carries the length. */
    private fun parseTopCard(card: JSONObject): Candidate? {
        val titleRuns = card.optJSONObject("title")?.optJSONArray("runs") ?: return null
        val id = titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: card.optJSONObject("onTap")?.optJSONObject("watchEndpoint")?.optString("videoId")
        if (!isValidVideoId(id)) return null
        val subtitle = runTexts(card.optJSONObject("subtitle")?.optJSONArray("runs"))
        val duration = subtitle.firstOrNull { DURATION.matches(it.trim()) }
        return Candidate(id!!, runTexts(titleRuns).joinToString(""), duration?.let(::parseDuration))
    }

    private fun parseRow(row: JSONObject): Candidate? {
        val id = row.optJSONObject("playlistItemData")?.optString("videoId")
        if (!isValidVideoId(id)) return null
        val columns = row.optJSONArray("flexColumns") ?: return null
        val texts = (0 until columns.length()).map { i ->
            runTexts(
                columns.optJSONObject(i)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs")
            )
        }
        val rowTitle = texts.firstOrNull()?.joinToString("") ?: return null
        val duration = texts.drop(1).flatten().firstOrNull { DURATION.matches(it.trim()) }
        return Candidate(id!!, rowTitle, duration?.let(::parseDuration))
    }

    private fun parseDuration(text: String): Long =
        text.trim().split(":").fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000

    // --- Official: YouTube Data API v3 search (100 quota units per call). ---

    /**
     * Supplies the signed-in user's OAuth access token, or null when signed out.
     * The token is only ever sent to googleapis.com, never to the unofficial endpoint.
     */
    @Volatile var accessToken: (suspend () -> String?)? = null

    private suspend fun searchDataApi(title: String, artist: String): String? {
        val token = accessToken?.invoke() ?: return null
        val q = URLEncoder.encode("$title $artist", "UTF-8")
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video" +
            "&videoCategoryId=10&maxResults=5&q=$q"
        val items = JSONObject(httpsRequest(url, bearer = token) ?: return null).optJSONArray("items") ?: return null

        // Prefer the auto-generated "<Artist> - Topic" upload, which is the YTM song itself.
        val firstArtist = artist.split("&", ",", " feat", " ft.").first().trim().lowercase()
        var fallback: String? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optJSONObject("id")?.optString("videoId")
            if (!isValidVideoId(id)) continue
            val snippet = item.optJSONObject("snippet") ?: continue
            // Video titles are often "Artist - Title (Official Video)", so require containment.
            if (!titleMatches(snippet.optString("title"), title)) continue
            if (snippet.optString("channelTitle").lowercase() == "$firstArtist - topic") return id
            if (fallback == null) fallback = id
        }
        return fallback
    }
}

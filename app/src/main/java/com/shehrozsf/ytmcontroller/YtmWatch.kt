package com.shehrozsf.ytmcontroller

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a YouTube Music queue without playing it: a song's radio, or the songs of any
 * playlist. Uses YTM's unofficial `next` endpoint anonymously (no cookies or tokens, so never
 * personalised); can break without notice.
 */
internal object YtmWatch {
    private const val NEXT = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    /** Playlist IDs: PL…, OLAK5uy_…, RD…, TLGG…, LL and friends. */
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{2,80}$")

    fun isValidPlaylistId(id: String?) = id != null && PLAYLIST_ID.matches(id)

    /** The song's radio (the `RDAMVM<id>` mix "Generate radio" starts), minus the song itself. */
    suspend fun radio(seedVideoId: String): List<Suggestion> =
        queue(seedVideoId, "RDAMVM$seedVideoId").filter { it.videoId != seedVideoId }

    /**
     * The songs of [playlistId], starting at [videoId] if given. YTM returns the first page
     * only (typically up to ~50 songs), which is as many as a temporary playlist holds anyway.
     */
    suspend fun queue(videoId: String?, playlistId: String): List<Suggestion> {
        require(videoId == null || isValidVideoId(videoId))
        require(isValidPlaylistId(playlistId))
        // Built with JSONObject so the IDs are always escaped, never spliced in.
        val body = JSONObject()
            .put("context", ytmContext())
            .put("playlistId", playlistId)
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
        if (videoId != null) body.put("videoId", videoId)
        val json = httpsRequest(NEXT, body.toString(), origin = YTM_ORIGIN)
            ?: throw SuggestionsException("couldn't reach YouTube Music")
        val rows = mutableListOf<Suggestion>()
        collect(JSONObject(json), rows, depth = 0)
        return rows.distinctBy { it.videoId }
    }

    /** Finds every queue row wherever it sits (YTM nests them differently between versions). */
    private fun collect(node: Any?, out: MutableList<Suggestion>, depth: Int) {
        if (depth > 40 || out.size >= 100) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistPanelVideoRenderer")?.let { row -> parse(row)?.let(out::add) }
                for (k in node.keys()) collect(node.opt(k), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
        }
    }

    private fun runs(obj: JSONObject?, name: String): List<String> {
        val runs = obj?.optJSONObject(name)?.optJSONArray("runs") ?: return emptyList()
        return (0 until runs.length()).map { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun parse(row: JSONObject): Suggestion? {
        val id = row.optString("videoId").takeIf(::isValidVideoId) ?: return null
        val title = runs(row, "title").joinToString("").trim().ifEmpty { return null }
        // shortByline is just the artist; longByline is "Artist • Album • Year".
        val artist = runs(row, "shortBylineText").joinToString("").trim().ifEmpty {
            runs(row, "longBylineText").takeWhile { it.trim() != "•" }.joinToString("").trim()
        }
        val thumbs = row.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = (0 until (thumbs?.length() ?: 0))
            .mapNotNull { thumbs!!.optJSONObject(it)?.optString("url") }
            .lastOrNull { it.startsWith("https://") } // last = largest
        return Suggestion(title, artist, imageUrl = thumb, videoId = id, sources = listOf(SuggestionSource.YTM))
    }
}

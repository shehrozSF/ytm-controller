package com.shehrozsf.ytmcontroller

import org.json.JSONObject
import java.net.URLEncoder

data class PlaylistInfo(val id: String, val title: String, val itemCount: Int?, val thumbnailUrl: String? = null)

data class PlaylistTrack(val videoId: String, val title: String)

/** Read-only calls to the official YouTube Data API v3 (1 quota unit per page of 50). */
object YouTubeApi {
    private const val BASE = "https://www.googleapis.com/youtube/v3"
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{2,64}$")

    /** YouTube's built-in playlist of the user's liked videos (includes YTM likes). */
    const val LIKED = "LL"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun myPlaylists(token: String): List<PlaylistInfo> {
        val out = mutableListOf(PlaylistInfo(LIKED, "Liked songs", null))
        var page: String? = null
        do {
            val url = "$BASE/playlists?part=snippet,contentDetails&mine=true&maxResults=50" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(httpsRequest(url, bearer = token) ?: error("couldn't load playlists"))
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (!PLAYLIST_ID.matches(id)) continue
                val snippet = item.optJSONObject("snippet")
                val thumbs = snippet?.optJSONObject("thumbnails")
                out += PlaylistInfo(
                    id = id,
                    title = snippet?.optString("title").orEmpty(),
                    itemCount = item.optJSONObject("contentDetails")?.optInt("itemCount"),
                    // ArtworkLoader re-checks the host, so an unexpected URL is simply not shown.
                    thumbnailUrl = (thumbs?.optJSONObject("medium") ?: thumbs?.optJSONObject("default"))
                        ?.optString("url")?.ifEmpty { null },
                )
            }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null && out.size < 500)
        return out
    }

    suspend fun playlistTracks(token: String, playlistId: String, limit: Int = 5000): List<PlaylistTrack> {
        require(PLAYLIST_ID.matches(playlistId))
        val out = mutableListOf<PlaylistTrack>()
        var page: String? = null
        do {
            val url = "$BASE/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=${enc(playlistId)}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(httpsRequest(url, bearer = token) ?: error("couldn't load playlist"))
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(i) ?: continue
                val id = item.optJSONObject("contentDetails")?.optString("videoId")
                val title = item.optJSONObject("snippet")?.optString("title").orEmpty()
                // Removed videos stay in playlists as placeholders.
                if (!isValidVideoId(id) || title == "Deleted video" || title == "Private video") continue
                out += PlaylistTrack(id!!, title)
            }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null && out.size < limit)
        return out
    }
}

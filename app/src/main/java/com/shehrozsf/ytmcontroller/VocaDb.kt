package com.shehrozsf.ytmcontroller

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val TAG = "VocaDb"

/**
 * VocaDB (vocadb.net): community database of Vocaloid/UTAU/vocal-synth songs. Free, no key.
 * Its related-songs data doesn't depend on scrobbles: songs liked by the same users, songs
 * with matching tags, and songs by the same producer. Only answers for songs it knows, so
 * non-Vocaloid music returns nothing rather than noise.
 */
object VocaDb {
    private const val BASE = "https://vocadb.net/api"
    private const val FIELDS = "PVs,ThumbUrl,AdditionalNames"
    /** Same-producer songs are the weakest signal; keep a few for variety. */
    private const val MAX_ARTIST_MATCHES = 5

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Tags YTM and uploaders add that VocaDB titles don't have. */
    private val BRACKET_TAGS = Regex("【[^】]*】")
    private val NOISE_PARENS = Regex(
        "[(（\\[][^)）\\]]*(feat|ft\\.|ver|cover|mv|pv|歌ってみた|official)[^)）\\]]*[)）\\]]",
        RegexOption.IGNORE_CASE,
    )

    fun cleanTitle(title: String) =
        title.replace(BRACKET_TAGS, " ").replace(NOISE_PARENS, " ").replace(Regex("\\s+"), " ").trim()

    suspend fun related(title: String, artist: String, limit: Int = 25): List<Suggestion> {
        val song = findSong(title, artist) ?: return emptyList()
        val json = get("$BASE/songs/${song.getInt("id")}/related?fields=$FIELDS&lang=Default") ?: return emptyList()
        val like = parse(json.optJSONArray("likeMatches"))
        val tag = parse(json.optJSONArray("tagMatches"))
        val byArtist = parse(json.optJSONArray("artistMatches")).take(MAX_ARTIST_MATCHES)
        // Alternate the two strongest signals, then add a few same-producer songs.
        val mixed = (0 until maxOf(like.size, tag.size)).flatMap { listOfNotNull(like.getOrNull(it), tag.getOrNull(it)) } + byArtist
        val current = normalizeTitle(cleanTitle(title))
        val out = mixed.filter { normalizeTitle(it.title) != current }.distinctBy { it.artist to it.title }.take(limit)
        Log.i(TAG, "\"$title\" → VocaDB #${song.optInt("id")}: ${like.size} liked, ${tag.size} tag, ${byArtist.size} artist")
        return out
    }

    /** The VocaDB entry for this song, or null if VocaDB doesn't know it. */
    private suspend fun findSong(title: String, artist: String): JSONObject? {
        val query = cleanTitle(title).ifEmpty { return null }
        val url = "$BASE/songs?query=${enc(query)}&maxResults=10&nameMatchMode=Auto&preferAccurateMatches=true" +
            "&sort=FavoritedTimes&fields=$FIELDS&lang=Default"
        val items = get(url)?.optJSONArray("items") ?: return null
        val wanted = normalizeTitle(query)
        val candidates = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.filter { song ->
            names(song).any { it == wanted }
        }
        if (candidates.isEmpty()) {
            Log.i(TAG, "\"$query\" not on VocaDB")
            return null
        }
        // Several songs can share a title; prefer the one whose credits mention the artist.
        val by = normalizeTitle(LastFm.mainArtist(artist))
        return candidates.firstOrNull { by.isNotEmpty() && by in normalizeTitle(it.optString("artistString")) }
            ?: candidates.first()
    }

    /** Main, default and alternate names (romaji, English…), normalised for comparison. */
    private fun names(song: JSONObject): List<String> =
        (listOf(song.optString("name"), song.optString("defaultName")) + song.optString("additionalNames").split(","))
            .map { normalizeTitle(it.trim()) }.filter { it.isNotEmpty() }

    private fun parse(songs: JSONArray?): List<Suggestion> =
        (0 until (songs?.length() ?: 0)).mapNotNull { i ->
            val s = songs!!.optJSONObject(i) ?: return@mapNotNull null
            val name = s.optString("name").trim().ifEmpty { return@mapNotNull null }
            val youtube = youtubePv(s.optJSONArray("pvs"))
            Suggestion(
                title = name,
                artist = s.optString("artistString").trim(),
                // A YouTube PV gives both a playable ID and a thumbnail on an allowed host.
                imageUrl = youtube?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                    ?: s.optString("thumbUrl").takeIf { it.startsWith("https://") },
                videoId = youtube,
                sources = listOf(SuggestionSource.VOCADB),
            )
        }

    /** The song's YouTube upload, preferring the original over reprints. */
    private fun youtubePv(pvs: JSONArray?): String? {
        val yt = (0 until (pvs?.length() ?: 0)).mapNotNull { pvs!!.optJSONObject(it) }
            .filter { it.optString("service") == "Youtube" && isValidVideoId(it.optString("pvId")) }
        return (yt.firstOrNull { it.optString("pvType") == "Original" } ?: yt.firstOrNull())?.optString("pvId")
    }

    private suspend fun get(url: String): JSONObject? =
        httpsRequest(url)?.let { runCatching { JSONObject(it) }.getOrNull() }
}

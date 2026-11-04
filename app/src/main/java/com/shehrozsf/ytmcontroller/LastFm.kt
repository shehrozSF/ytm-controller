package com.shehrozsf.ytmcontroller

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val TAG = "LastFm"

enum class SuggestionSource(val label: String) {
    YTM("YT Music"), LASTFM("Last.fm"), VOCADB("VocaDB"), HOLODEX("Holodex"),
}

/**
 * One suggested song. [imageUrl] is a real cover when the source gave one (YTM and Holodex
 * always do, VocaDB usually, Last.fm rarely: see [LastFm.coverArt]). [videoId] is set when
 * the source knows the YouTube video, so the row plays without a search. [sources] lists
 * every source that suggested it.
 */
data class Suggestion(
    val title: String,
    val artist: String,
    val imageUrl: String? = null,
    val videoId: String? = null,
    val sources: List<SuggestionSource> = listOf(SuggestionSource.LASTFM),
)

/**
 * A suggestion list plus what it's based on. When Last.fm has no similar tracks, the list
 * comes from the artist instead, so two songs by the same artist get the same list.
 */
data class Suggestions(val items: List<Suggestion>, val basis: String)

/** Result of a cover lookup. Only [Found] and [None] are cached; [Failed] retries later. */
sealed interface Cover {
    data class Found(val url: String) : Cover
    data object None : Cover
    data object Failed : Cover
}

/** A Last.fm error worth showing, e.g. "Invalid API key". */
class LastFmException(message: String) : Exception(message)

/** Result of testing an API key. [UNKNOWN]: the service couldn't be reached, so no verdict. */
enum class KeyCheck { VALID, INVALID, UNKNOWN }

/** Suggestions from Last.fm, based on what its listeners play together. */
object LastFm {
    private const val BASE = "https://ws.audioscrobbler.com/2.0/"
    /** Last.fm's "Track/Artist not found": no data, not a failure. */
    private const val ERROR_NOT_FOUND = 6

    val hasKey get() = BuildConfig.LASTFM_API_KEY.isNotEmpty()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun mainArtist(artist: String) =
        artist.split("&", ",", " feat", " ft.", " x ", "×").first().trim().ifEmpty { artist }

    /**
     * Step 1: tracks Last.fm's listeners play alongside this one. Often empty for newer or
     * non-English songs; [SuggestionFeed] then tries VocaDB/Holodex, then [similarArtistTracks].
     */
    suspend fun similarSongs(title: String, artist: String, limit: Int = 25): List<Suggestion> {
        val main = mainArtist(artist)
        return similarTracks(title, artist, limit)
            .ifEmpty { if (main != artist) similarTracks(title, main, limit) else emptyList() }
            .tidy(title, limit)
    }

    /** Last resort: top tracks of similar artists (artist data is far denser than track data). */
    suspend fun similarArtistTracks(title: String, artist: String, limit: Int = 25): List<Suggestion> =
        fromSimilarArtists(mainArtist(artist), limit).tidy(title, limit)

    // distinctBy (not distinct): the same song can repeat with different image links,
    // and the UI keys rows by artist + title.
    private fun List<Suggestion>.tidy(currentTitle: String, limit: Int): List<Suggestion> {
        val current = normalizeTitle(currentTitle)
        return filter { normalizeTitle(it.title) != current }.distinctBy { it.artist to it.title }.take(limit)
    }

    private fun songKey(title: String, artist: String) = "$artist\u0000$title"

    private suspend fun similarTracks(title: String, artist: String, limit: Int): List<Suggestion> {
        val tracks = call("track.getsimilar", "track" to title, "artist" to artist, "limit" to "$limit")
            ?.optJSONObject("similartracks")?.optJSONArray("track")
        return parseTracks(tracks)
    }

    private suspend fun fromSimilarArtists(artist: String, limit: Int): List<Suggestion> = coroutineScope {
        val artists = call("artist.getsimilar", "artist" to artist, "limit" to "10")
            ?.optJSONObject("similarartists")?.optJSONArray("artist")
        val names = (0 until (artists?.length() ?: 0))
            .mapNotNull { artists!!.optJSONObject(it)?.optString("name")?.trim()?.ifEmpty { null } }
            .take(8)
        val perArtist = names.map { name ->
            async { runCatching { topTracks(name, 3) }.getOrDefault(emptyList()) }
        }.awaitAll()
        // Interleave (1st of each artist, then 2nd…) so the list isn't one artist at a time.
        (0 until 3).flatMap { rank -> perArtist.mapNotNull { it.getOrNull(rank) } }.take(limit)
    }

    private suspend fun topTracks(artist: String, count: Int): List<Suggestion> {
        val tracks = call("artist.gettoptracks", "artist" to artist, "limit" to "$count")
            ?.optJSONObject("toptracks")?.optJSONArray("track")
        return parseTracks(tracks)
    }

    private fun parseTracks(tracks: JSONArray?): List<Suggestion> =
        (0 until (tracks?.length() ?: 0)).mapNotNull { i ->
            val t = tracks!!.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name").trim()
            val by = t.optJSONObject("artist")?.optString("name").orEmpty().trim()
            if (name.isEmpty() || by.isEmpty()) null else Suggestion(name, by, bestImage(t.optJSONArray("image")))
        }

    /**
     * Last.fm's grey-star placeholder. Since 2019 track lists return it for nearly every
     * track, so it means "no image".
     */
    private const val PLACEHOLDER = "2a96cbd8b46e442fc41c2b86b821562f"

    /** Largest real image in a Last.fm `image` array, or null. */
    private fun bestImage(images: JSONArray?): String? {
        val bySize = (0 until (images?.length() ?: 0)).mapNotNull { images!!.optJSONObject(it) }
            .associate { it.optString("size") to it.optString("#text").trim() }
        return listOf("extralarge", "large", "medium", "small")
            .firstNotNullOfOrNull { size -> bySize[size]?.takeIf { it.startsWith("https://") && PLACEHOLDER !in it } }
    }

    private val coverLookups = Semaphore(4)

    /**
     * Covers already looked up this session ([Cover.Found] or [Cover.None]), newest 500. The
     * cover images themselves are kept on disk by [Caches], as are Last.fm's responses when
     * Last.fm marks them cacheable.
     */
    private val covers = android.util.LruCache<String, Cover>(500)

    /** Instant answer when known, so revisited rows don't flash a loading state. */
    fun cachedCover(title: String, artist: String): Cover? = covers.get(songKey(title, artist))

    /**
     * Album cover for a track via `track.getInfo`, the one call that still returns real art.
     * One request per track, cached for the session.
     */
    suspend fun coverArt(title: String, artist: String): Cover {
        if (!hasKey) return Cover.None
        val key = songKey(title, artist)
        covers.get(key)?.let { return it }
        val cover = try {
            // At most a few at once: a fresh Suggestions list would otherwise fire one per row
            // simultaneously and starve Up next's thumbnails of bandwidth.
            val track = coverLookups.withPermit {
                call("track.getInfo", "track" to title, "artist" to artist)?.optJSONObject("track")
            }
            bestImage(track?.optJSONObject("album")?.optJSONArray("image"))?.let(Cover::Found) ?: Cover.None
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Row scrolled away mid-lookup: not an answer, so don't cache it.
            throw e
        } catch (e: Exception) {
            // Network or API error: not cached, so the row retries next time it appears.
            Log.w(TAG, "cover lookup failed for \"$title\" / $artist: ${e.message}")
            return Cover.Failed
        }
        covers.put(key, cover)
        return cover
    }

    /** Last.fm's error codes for a bad key: 10 "Invalid API key", 26 "Suspended API key". */
    private val BAD_KEY_ERRORS = setOf(10, 26)

    /** Tests the key with the cheapest call there is (one chart entry). */
    suspend fun checkKey(): KeyCheck {
        if (!hasKey) return KeyCheck.INVALID
        val url = "$BASE?method=chart.gettopartists&limit=1&format=json&api_key=${enc(BuildConfig.LASTFM_API_KEY)}"
        val body = httpsRequest(url, readErrorBody = true) ?: return KeyCheck.UNKNOWN
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return KeyCheck.UNKNOWN
        return if (json.has("error") && json.optInt("error") in BAD_KEY_ERRORS) KeyCheck.INVALID else KeyCheck.VALID
    }

    /** One API call; null when Last.fm simply has no data, [LastFmException] for real errors. */
    private suspend fun call(method: String, vararg params: Pair<String, String>): JSONObject? {
        val url = BASE + "?method=$method&autocorrect=1&format=json" +
            params.joinToString("") { (k, v) -> "&$k=${enc(v)}" } +
            "&api_key=${enc(BuildConfig.LASTFM_API_KEY)}"
        val body = httpsRequest(url, readErrorBody = true) ?: throw LastFmException("couldn't reach Last.fm")
        val json = runCatching { JSONObject(body) }.getOrElse { throw LastFmException("unexpected response") }
        if (json.has("error")) {
            if (json.optInt("error") == ERROR_NOT_FOUND) return null
            throw LastFmException(json.optString("message").ifEmpty { "error ${json.optInt("error")}" })
        }
        return json
    }
}

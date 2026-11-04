package com.shehrozsf.ytmcontroller

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val TAG = "SuggestionFeed"

/** Every source failed (or a source's own "not found"); [message] says why. */
class SuggestionsException(message: String) : Exception(message)

/**
 * The Suggestions tab. Two lanes, interleaved row by row:
 *  - YouTube Music's radio for the song: rich data for Japanese, Vocaloid and VTuber music,
 *    but popularity-leaning.
 *  - A neutral lane, first source that has anything wins:
 *    1. Last.fm similar songs ("people who play X also play Y"); sparse for that music, so when
 *       it's empty or fails,
 *    2. VocaDB (Vocaloid songs it knows) and Holodex (songs from VTuber channels), together,
 *    3. Last.fm's similar artists' top tracks.
 * A song several sources suggest appears once, listing all of them.
 */
object SuggestionFeed {
    private const val LIMIT = 30
    /** YTM radios lean on one artist; cap them so the list stays varied. */
    private const val MAX_PER_ARTIST_YTM = 2

    private val cache = LruCache<String, Suggestions>(30)
    private fun key(title: String, artist: String) = "$artist\u0000$title"

    fun cached(title: String, artist: String): Suggestions? = cache.get(key(title, artist))

    /** One source's outcome, for the summary line and for deciding what to cache. */
    private class Step(val name: String, val result: Result<List<Suggestion>>, val skipped: String? = null) {
        val items get() = result.getOrNull().orEmpty()
        fun describe() = skipped?.let { "$name off ($it)" }
            ?: result.fold({ "$name (${it.size})" }, { "$name failed (${it.message})" })
    }

    suspend fun load(title: String, artist: String, durationMs: Long): Suggestions = coroutineScope {
        // The YouTube video ID drives both the YTM radio and Holodex; look it up once.
        val seedJob = async { attempt { VideoIdLookup.find(title, artist, durationMs) } }
        val ytmJob = async {
            val seed = seedJob.await()
            Step("YT Music radio", seed.fold(
                { id -> attempt { YtmWatch.radio(id ?: throw SuggestionsException("song not found")) } },
                { Result.failure(it) },
            ))
        }
        val neutralJob = async { neutralLane(title, artist) { seedJob.await().getOrNull() } }

        val ytm = ytmJob.await()
        val neutral = neutralJob.await()
        val steps = listOf(ytm) + neutral.steps
        if (steps.none { it.result.isSuccess && it.skipped == null }) {
            throw SuggestionsException(steps.joinToString(" · ") { it.describe() })
        }

        val seedTitle = normalizeTitle(title)
        val ytmItems = SuggestionMerge.capPerArtist(
            ytm.items.filterNot { SuggestionMerge.isVersionOf(it.title, seedTitle) },
            MAX_PER_ARTIST_YTM,
        )
        val neutralItems = neutral.items.filterNot { SuggestionMerge.isVersionOf(it.title, seedTitle) }

        val items = SuggestionMerge.interleave(ytmItems, neutralItems).take(LIMIT)
        val basis = steps.joinToString(" · ") { it.describe() }
        Log.i(TAG, "\"${logSafe(title)}\" / ${logSafe(artist)}: ${items.size} suggestions. ${logSafe(basis, 300)}")

        Suggestions(items, basis).also {
            // A source that failed should retry next time, so only cache complete answers.
            if (steps.all { s -> s.result.isSuccess }) cache.put(key(title, artist), it)
        }
    }

    /** The neutral lane's rows plus every step it tried (for the summary line). */
    private class Lane(val steps: List<Step>, val items: List<Suggestion>)

    /** Last.fm songs → (VocaDB + Holodex) → Last.fm artists, stopping at the first with results. */
    private suspend fun neutralLane(title: String, artist: String, seedId: suspend () -> String?): Lane =
        coroutineScope {
            val steps = mutableListOf<Step>()
            val lastFmSongs = if (LastFm.hasKey) {
                Step("Last.fm similar songs", attempt { LastFm.similarSongs(title, artist) })
            } else {
                Step("Last.fm", Result.success(emptyList()), skipped = "no API key")
            }
            steps += lastFmSongs
            if (lastFmSongs.items.isNotEmpty()) return@coroutineScope Lane(steps, lastFmSongs.items)

            // Last.fm had nothing: the two niche sources, side by side.
            val voca = async { Step("VocaDB", attempt { VocaDb.related(title, artist) }) }
            val holo = async {
                if (!Holodex.hasKey) Step("Holodex", Result.success(emptyList()), skipped = "no API key")
                else Step("Holodex", attempt { seedId()?.let { Holodex.related(it) }.orEmpty() })
            }
            val vocaStep = voca.await()
            val holoStep = holo.await()
            steps += vocaStep
            steps += holoStep
            // Row by row, so neither buries the other.
            val niche = SuggestionMerge.interleave(vocaStep.items, holoStep.items)
            if (niche.isNotEmpty()) return@coroutineScope Lane(steps, niche)

            if (!LastFm.hasKey) return@coroutineScope Lane(steps, emptyList())
            val artists = Step("Last.fm similar artists", attempt { LastFm.similarArtistTracks(title, artist) })
            steps += artists
            Lane(steps, artists.items)
        }

    /** runCatching that lets cancellation through (song changed or screen left mid-load). */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "source failed: ${logSafe(e.message)}")
        Result.failure(e)
    }
}

/**
 * The pure list logic behind [SuggestionFeed]: no network or Android types, so it's
 * unit-tested on the JVM.
 */
internal object SuggestionMerge {
    /** Another upload, cover or live take of the current song (YTM radios are full of them). */
    fun isVersionOf(candidate: String, seedNormalized: String): Boolean {
        val c = normalizeTitle(candidate)
        if (c.length < 4 || seedNormalized.length < 4) return c == seedNormalized
        return c.contains(seedNormalized) || seedNormalized.contains(c)
    }

    /** Keeps at most [max] songs per artist (case-insensitive), in order. */
    fun capPerArtist(items: List<Suggestion>, max: Int): List<Suggestion> {
        val counts = HashMap<String, Int>()
        return items.filter { s ->
            val k = s.artist.lowercase().trim()
            val n = counts.getOrDefault(k, 0)
            counts[k] = n + 1
            n < max
        }
    }

    /**
     * a0, b0, a1, b1… A song suggested more than once keeps its first row's position and
     * gains the other sources. Whenever YT Music is one of them, its cover and video win
     * (it's the actual YTM track); otherwise missing ones are filled from the other row.
     */
    fun interleave(a: List<Suggestion>, b: List<Suggestion>): List<Suggestion> {
        val out = LinkedHashMap<String, Suggestion>()
        for (i in 0 until maxOf(a.size, b.size)) {
            for (s in listOfNotNull(a.getOrNull(i), b.getOrNull(i))) {
                val k = normalizeTitle(s.title)
                if (k.isEmpty()) continue
                val existing = out[k]
                out[k] = if (existing == null) s else {
                    val (primary, other) = if (SuggestionSource.YTM in s.sources) s to existing else existing to s
                    existing.copy(
                        sources = (existing.sources + s.sources).distinct(),
                        imageUrl = primary.imageUrl ?: other.imageUrl,
                        videoId = primary.videoId ?: other.videoId,
                    )
                }
            }
        }
        return out.values.toList()
    }
}


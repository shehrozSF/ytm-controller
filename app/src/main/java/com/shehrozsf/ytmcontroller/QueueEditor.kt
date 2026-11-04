package com.shehrozsf.ytmcontroller

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val TAG = "QueueEditor"

/** What a pasted or shared YouTube / YouTube Music link points at. */
data class LinkTarget(val videoId: String?, val playlistId: String?) {
    /** A real playlist, not a radio mix or temporary list YouTube tacks onto song links. */
    val hasPlaylist get() = playlistId != null && !playlistId.startsWith("RD") && !playlistId.startsWith("TLGG")
}

/** Where new songs go in Up next. */
sealed interface InsertAt {
    data object Next : InsertAt
    data object End : InsertAt
    data class After(val queueId: Long) : InsertAt
}

/**
 * Inserts a song or playlist into YTM's Up next.
 *
 * YTM's media session has no add-to-queue command, so the queue is rebuilt instead: the
 * current song, Up next with the new songs spliced in, turned into a temporary playlist
 * (the same watch_videos trick Merge uses) and played from the current song, which then
 * jumps back to where it was. Limits: 50 songs in all (Up next's tail is trimmed to fit),
 * and Up next songs that can't be matched to a YouTube video are dropped. Both are reported.
 */
object QueueEditor {
    /** watch_videos' limit. */
    private const val MAX_SONGS = 50

    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val URL = Regex("https?://\\S+")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lookups = Semaphore(6)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Text shared to the app (Android's Share sheet), waiting for the add-link dialog. */
    private val _shared = MutableStateFlow<String?>(null)
    val shared: StateFlow<String?> = _shared.asStateFlow()

    fun offerShared(text: String) {
        _shared.value = text.take(2_000) // untrusted input; only parsed, never executed
    }

    fun consumeShared() {
        _shared.value = null
    }

    /** Parses the first YouTube / YTM / youtu.be link in [text]; null if there isn't one. */
    fun parseLink(text: String): LinkTarget? {
        val uri = Uri.parse(URL.find(text)?.value ?: text.trim())
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val video = when {
            host == "youtu.be" -> uri.pathSegments.firstOrNull()
            host in YOUTUBE_HOSTS && uri.pathSegments.firstOrNull() == "shorts" -> uri.pathSegments.getOrNull(1)
            host in YOUTUBE_HOSTS -> uri.getQueryParameter("v")
            else -> return null
        }?.takeIf(::isValidVideoId)
        val list = uri.getQueryParameter("list")?.takeIf(YtmWatch::isValidPlaylistId)
        return if (video == null && list == null) null else LinkTarget(video, list)
    }

    /** Adds [target] (the whole playlist if [wholePlaylist], else just the song) at [at]. */
    fun insert(target: LinkTarget, wholePlaylist: Boolean, at: InsertAt) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                _status.value = run(target, wholePlaylist, at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "insert failed", e)
                _status.value = "Couldn't add: ${e.message}"
            } finally {
                _busy.value = false
            }
            delay(8_000)
            if (!_busy.value) _status.value = null
        }
    }

    private suspend fun run(target: LinkTarget, wholePlaylist: Boolean, at: InsertAt): String {
        val track = NowPlaying.track.value ?: error("nothing is playing")
        checkNotNull(NowPlaying.controller) { "open YouTube Music first" }

        _status.value = "Reading the link…"
        val added = if (wholePlaylist && target.playlistId != null) {
            playlistSongs(target.playlistId, target.videoId)
        } else {
            listOfNotNull(target.videoId)
        }.take(MAX_SONGS - 1)
        require(added.isNotEmpty()) { "the link has no playable songs" }

        _status.value = "Matching Up next to YouTube…"
        val currentId = VideoIdLookup.find(track.title, track.artist, track.durationMs)
            ?: error("couldn't find the current song on YouTube")

        // Up next as YTM shows it, and how much of it still fits around the new songs.
        val queue = NowPlaying.queue.value
        val active = queue.indexOfFirst { it.id == NowPlaying.playback.value?.activeQueueId }
        val upNext = if (active >= 0) queue.drop(active + 1) else queue
        val split = when (at) {
            InsertAt.Next -> 0
            InsertAt.End -> upNext.size
            is InsertAt.After -> upNext.indexOfFirst { it.id == at.queueId } + 1 // not found → 0, play next
        }
        val room = MAX_SONGS - 1 - added.size
        val before = upNext.take(split).take(room)
        val after = upNext.drop(split).take(room - before.size)
        val trimmed = upNext.size - before.size - after.size

        val (beforeIds, afterIds) = coroutineScope {
            val b = before.map { async { idOf(it) } }
            val a = after.map { async { idOf(it) } }
            b.awaitAll() to a.awaitAll()
        }
        val unmatched = (beforeIds + afterIds).count { it == null }
        val ids = (listOf(currentId) + beforeIds.filterNotNull() + added + afterIds.filterNotNull()).distinct()

        _status.value = "Updating the queue…"
        // A running Merge batch watcher would fight the new queue.
        MergePlayer.stop()
        val list = MergePlayer.tempPlaylist(ids) ?: error("YouTube didn't create the temporary playlist")
        NowPlaying.playKeepingPosition("https://music.youtube.com/watch?v=$currentId&list=$list")

        Log.i(TAG, "inserted ${added.size} at $at; ${ids.size} songs, $unmatched unmatched, $trimmed trimmed")
        return buildString {
            append(if (added.size == 1) "Added 1 song" else "Added ${added.size} songs")
            if (unmatched > 0) append(" · $unmatched Up next song(s) couldn't be matched and were dropped")
            if (trimmed > 0) append(" · last $trimmed of Up next trimmed (50-song limit)")
        }
    }

    /**
     * A playlist's songs (from [startAt] if given). Public playlists are read anonymously;
     * private ones (and Liked songs) look empty that way, so those are read through the
     * signed-in account's read-only access instead. The token only ever goes to Google's API.
     */
    private suspend fun playlistSongs(playlistId: String, startAt: String?): List<String> {
        // YTM's Liked music ("LM") is YouTube's liked list ("LL") as far as the API is concerned.
        val apiId = if (playlistId == "LM") YouTubeApi.LIKED else playlistId
        val anonymous = if (playlistId == "LM" || playlistId == YouTubeApi.LIKED) {
            emptyList() // personal lists never resolve anonymously
        } else {
            try {
                YtmWatch.queue(startAt, playlistId).mapNotNull { it.videoId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (anonymous.isNotEmpty()) return anonymous

        val token = VideoIdLookup.accessToken?.invoke()
            ?: error("that playlist is private or empty. Sign in on the Merge tab to add your own private playlists")
        val ids = YouTubeApi.playlistTracks(token, apiId, limit = 200).map { it.videoId }
        require(ids.isNotEmpty()) { "that playlist is empty, or isn't yours" }
        // Honour a link that points at a song inside the playlist: start there.
        val from = startAt?.let(ids::indexOf)?.takeIf { it >= 0 } ?: 0
        return ids.drop(from)
    }

    /** YouTube ID of an Up next entry, by title and the artist part of its subtitle. */
    private suspend fun idOf(entry: QueueEntry): String? = lookups.withPermit {
        val artist = entry.subtitle?.split(" • ")?.firstOrNull().orEmpty()
        VideoIdLookup.find(entry.title, artist, durationMs = 0) // handles its own errors
    }
}

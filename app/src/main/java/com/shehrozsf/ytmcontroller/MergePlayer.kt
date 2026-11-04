package com.shehrozsf.ytmcontroller

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays a locally merged + shuffled list in YTM without writing anything to the account.
 *
 * YTM can't accept a custom queue, but youtube.com/watch_videos turns up to ~50 IDs into an
 * anonymous TLGG… playlist that YTM will play. Longer merges are fed in batches: when the
 * last song of a batch ends, the next batch takes over.
 */
object MergePlayer {
    private const val BATCH = 50
    private val TEMP_LIST = Regex("^TLGG[A-Za-z0-9_-]{10,60}$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var batches: List<List<PlaylistTrack>> = emptyList()
    private var nextBatch = 0
    private var watcher: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    val hasNextBatch get() = nextBatch < batches.size

    suspend fun play(tracks: List<PlaylistTrack>) {
        checkNotNull(NowPlaying.controller) { "Open YouTube Music and play anything first" }
        val unique = tracks.distinctBy { it.videoId }.shuffled()
        require(unique.isNotEmpty()) { "no playable songs in those playlists" }
        batches = unique.chunked(BATCH)
        nextBatch = 0
        playNextBatch()
        watchForBatchEnd()
    }

    suspend fun playNextBatch() {
        val batch = batches.getOrNull(nextBatch) ?: return stop()
        val list = tempPlaylist(batch.map { it.videoId }) ?: error("YouTube didn't create the temporary playlist")
        nextBatch++
        NowPlaying.playUri("https://music.youtube.com/watch?v=${batch.first().videoId}&list=$list")
        _status.value = "Playing batch $nextBatch of ${batches.size} (${batches.sumOf { it.size }} songs)"
    }

    fun stop() {
        watcher?.cancel()
        batches = emptyList()
        nextBatch = 0
        _status.value = null
    }

    /** When the current batch's last song gives way to something else, start the next batch. */
    private fun watchForBatchEnd() {
        watcher?.cancel()
        watcher = scope.launch {
            var previous: String? = null
            NowPlaying.track.collect { track ->
                val title = track?.title ?: return@collect
                val last = batches.getOrNull(nextBatch - 1)?.last()
                if (previous != null && title != previous && last != null && sameSong(previous!!, last.title)) {
                    if (hasNextBatch) runCatching { playNextBatch() } else stop()
                }
                previous = title
            }
        }
    }

    /** YTM metadata and Data API titles can differ ("Song" vs "Artist - Song (Official Video)"). */
    private fun sameSong(a: String, b: String): Boolean {
        val x = normalizeTitle(a)
        val y = normalizeTitle(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    /** Resolves watch_videos' redirect to the anonymous list ID; no cookies or tokens are sent. */
    internal suspend fun tempPlaylist(videoIds: List<String>): String? {
        require(videoIds.all(::isValidVideoId))
        val location = httpsRedirectTarget(
            "https://www.youtube.com/watch_videos?video_ids=" + videoIds.joinToString(",")
        ) ?: return null
        val uri = Uri.parse(location)
        val host = uri.host ?: return null
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return null
        return uri.getQueryParameter("list")?.takeIf(TEMP_LIST::matches)
    }
}

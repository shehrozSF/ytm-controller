package com.shehrozsf.ytmcontroller

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Official YTM and patched builds (ReVanced, RVX, Vanced…) all keep the
 * `.youtube.music` suffix even when they rename the package.
 */
fun isYtmPackage(packageName: String) = packageName.endsWith(".youtube.music")
private const val TAG = "NowPlaying"

data class Track(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val art: Bitmap?,
    val mediaId: String?,
)

data class QueueEntry(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val art: Bitmap?,
    val artUri: String?,
)

data class Playback(
    val isPlaying: Boolean,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val activeQueueId: Long,
) {
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long =
        if (isPlaying) positionMs + ((now - updatedAt) * speed).toLong() else positionMs
}

/** Shared state between [MediaListenerService] (writer) and the UI (reader). */
object NowPlaying {
    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    /** Album-art colours for the playbar; null means "use the app theme". */
    private val _playbarColors = MutableStateFlow<PlaybarColors?>(null)
    val playbarColors: StateFlow<PlaybarColors?> = _playbarColors.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var colorJob: Job? = null
    private var colorKey: String? = null

    var controller: MediaController? = null
        internal set

    /** Human-readable listener state, shown on screen while debugging session discovery. */
    val status = MutableStateFlow("listener not connected")

    fun onMetadata(m: MediaMetadata?) {
        if (m == null) {
            _track.value = null
            return
        }
        val track = Track(
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = m.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
            // Same precedence as SystemUI's MediaDataLoader (ART before ALBUM_ART), so the
            // playbar colours come from the same image the system player uses.
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            mediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        )
        _track.value = track
        updatePlaybarColors(track)
    }

    /**
     * YTM sends several metadata updates per song (art often arrives after the title), so
     * colours are keyed per track: cached results apply instantly, an in-flight extraction
     * for the same track isn't restarted, and a new track without art falls back to the theme.
     */
    private fun updatePlaybarColors(track: Track) {
        val key = track.mediaId?.takeIf { it.isNotBlank() } ?: "${track.title}\u0000${track.artist}"
        PlaybarColorExtractor.cached(key)?.let {
            colorJob?.cancel()
            colorKey = key
            _playbarColors.value = it
            return
        }
        val art = track.art
        if (key == colorKey && (art == null || colorJob?.isActive == true)) return
        colorJob?.cancel()
        colorKey = key
        if (art == null) {
            _playbarColors.value = null
            return
        }
        colorJob = scope.launch {
            // Off the main thread inside extract(); only the StateFlow write happens here.
            _playbarColors.value = PlaybarColorExtractor.extract(key, art)
        }
    }

    fun onPlaybackState(s: PlaybackState?) {
        _playback.value = s?.let {
            Playback(
                isPlaying = it.state == PlaybackState.STATE_PLAYING,
                positionMs = it.position,
                updatedAt = it.lastPositionUpdateTime,
                speed = it.playbackSpeed,
                activeQueueId = it.activeQueueItemId,
            )
        }
    }

    fun onQueue(items: List<MediaSession.QueueItem>?) {
        _queue.value = items.orEmpty().map {
            QueueEntry(
                id = it.queueId,
                title = it.description.title?.toString().orEmpty(),
                subtitle = it.description.subtitle?.toString(),
                art = it.description.iconBitmap,
                artUri = it.description.iconUri?.toString(),
            )
        }
    }

    fun clear() {
        controller = null
        _track.value = null
        _playback.value = null
        _queue.value = emptyList()
        colorJob?.cancel()
        colorKey = null
        _playbarColors.value = null
    }

    fun jumpTo(queueId: Long) {
        val c = controller ?: return
        val state = c.playbackState
        if (state != null && state.actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L) {
            c.transportControls.skipToQueueItem(queueId)
            return
        }
        // YTM doesn't advertise jump-to-item, so press "next" until we get there.
        val q = _queue.value
        val from = q.indexOfFirst { it.id == state?.activeQueueItemId }
        val to = q.indexOfFirst { it.id == queueId }
        if (from < 0 || to <= from) return
        repeat(to - from) { c.transportControls.skipToNext() }
    }

    fun playUri(uri: String) = controller?.transportControls?.playFromUri(Uri.parse(uri), null)

    /** Plays another song now, followed by its radio so the queue stays relevant. */
    fun playSong(videoId: String) {
        require(isValidVideoId(videoId))
        playUri("https://music.youtube.com/watch?v=$videoId&list=RDAMVM$videoId")
    }

    /** Video ID + title of the song we last started a radio from. */
    private var radioSeed: Pair<String, String?>? = null

    /** True while the seed song of the radio we started is still the one playing. */
    fun isOnRadioOf(videoId: String): Boolean {
        val (seedId, seedTitle) = radioSeed ?: return false
        return seedId == videoId && seedTitle == _track.value?.title
    }

    /**
     * A radio link always starts with its seed song, so YTM restarts the current track.
     * Remember the position and jump back once the same song has reloaded.
     */
    suspend fun startRadio(videoId: String) {
        require(isValidVideoId(videoId))
        radioSeed = videoId to _track.value?.title
        playKeepingPosition("https://music.youtube.com/watch?v=$videoId&list=RDAMVM$videoId")
    }

    /**
     * Plays [uri], which must start with the song that's playing now (YTM restarts it), then
     * seeks back to where it was. Used whenever the queue is rebuilt around the current song.
     */
    suspend fun playKeepingPosition(uri: String) {
        val title = _track.value?.title
        val resumeAt = _playback.value?.positionAt() ?: 0L
        playUri(uri)
        if (title == null || resumeAt < 5_000) return

        // Wait for YTM to report the reloaded track playing near the start, then seek back.
        withTimeoutOrNull(10_000) {
            _playback.first { pb ->
                pb != null && pb.isPlaying && pb.positionAt() < resumeAt - 3_000 && _track.value?.title == title
            }
        } ?: return
        controller?.transportControls?.seekTo(resumeAt)
    }

    fun playPause() {
        val controls = controller?.transportControls ?: return
        if (_playback.value?.isPlaying == true) controls.pause() else controls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    fun previous() = controller?.transportControls?.skipToPrevious()
}

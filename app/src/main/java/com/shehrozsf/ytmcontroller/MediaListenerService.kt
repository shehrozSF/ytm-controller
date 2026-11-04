package com.shehrozsf.ytmcontroller

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log

private const val TAG = "MediaListener"

private val ACTION_NAMES = mapOf(
    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "PLAY_FROM_MEDIA_ID",
    PlaybackState.ACTION_PLAY_FROM_SEARCH to "PLAY_FROM_SEARCH",
    PlaybackState.ACTION_PLAY_FROM_URI to "PLAY_FROM_URI",
    PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to "PREPARE_FROM_MEDIA_ID",
    PlaybackState.ACTION_PREPARE_FROM_SEARCH to "PREPARE_FROM_SEARCH",
    PlaybackState.ACTION_PREPARE_FROM_URI to "PREPARE_FROM_URI",
    PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "SKIP_TO_QUEUE_ITEM",
    PlaybackState.ACTION_SEEK_TO to "SEEK_TO",
    PlaybackState.ACTION_SET_RATING to "SET_RATING",
)

/**
 * We never read notifications; being a notification listener is simply what
 * grants permission to see other apps' MediaSessions.
 */
class MediaListenerService : NotificationListenerService() {
    private lateinit var sessions: MediaSessionManager
    private var controller: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        report("sessions changed: ${it?.map(MediaController::getPackageName)}")
        attach(it)
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = NowPlaying.onMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = NowPlaying.onPlaybackState(state)
        override fun onQueueChanged(queue: List<MediaSession.QueueItem>?) = NowPlaying.onQueue(queue)
        override fun onSessionDestroyed() = detach()
    }

    override fun onListenerConnected() {
        report("listener connected")
        try {
            sessions = getSystemService(MediaSessionManager::class.java)
            val self = ComponentName(this, MediaListenerService::class.java)
            sessions.addOnActiveSessionsChangedListener(sessionsListener, self)
            val active = sessions.getActiveSessions(self)
            report("listener connected, sessions: ${active.map(MediaController::getPackageName)}")
            attach(active)
        } catch (e: Exception) {
            report("listener connected, but failed: $e")
        }
    }

    override fun onListenerDisconnected() {
        report("listener disconnected")
        sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        detach()
    }

    private fun attach(active: List<MediaController>?) {
        val ytm = active?.firstOrNull { isYtmPackage(it.packageName) }
        if (ytm != null && ytm.sessionToken == controller?.sessionToken) return
        detach()
        if (ytm == null) return

        report("attached to YTM session, extras=${ytm.extras?.keySet()}")
        controller = ytm
        ytm.registerCallback(callback)
        NowPlaying.controller = ytm
        NowPlaying.onMetadata(ytm.metadata)
        NowPlaying.onPlaybackState(ytm.playbackState)
        NowPlaying.onQueue(ytm.queue)
        logCapabilities(ytm)
    }

    /** Which commands YTM accepts decides how a merged playlist can be fed to it. */
    private fun logCapabilities(c: MediaController) {
        val state = c.playbackState ?: return
        val supported = ACTION_NAMES.filterKeys { state.actions and it != 0L }.values
        Log.i(TAG, "actions: $supported")
        Log.i(TAG, "customActions: ${state.customActions.map { "${it.action} (${it.name})" }}")
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        NowPlaying.clear()
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        NowPlaying.status.value = message
    }
}

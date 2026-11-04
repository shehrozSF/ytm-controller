package com.shehrozsf.ytmcontroller

import android.content.Context
import android.content.SharedPreferences
import android.net.http.HttpResponseCache
import android.util.Log
import java.io.File
import java.security.MessageDigest

private const val TAG = "Caches"

/**
 * What survives the app being closed. Memory caches elsewhere are fast but die with the
 * process; these live in the app's private storage:
 *
 *  - HTTP disk cache (20 MB): downloaded thumbnails, and any API response the server marks
 *    cacheable. Oldest entries are dropped first when full. Android's own cache, so the server's
 *    expiry rules are respected.
 *  - Video IDs (newest 1,000 songs, forgotten after 30 days): the YouTube lookup is the slowest
 *    thing the app repeats, and a song's ID doesn't change.
 *
 * Both are under the app's cache/private storage: Android may clear the HTTP cache when the
 * phone runs low on space, and Settings → Apps → Storage → Clear cache / Clear data wipes them.
 */
object Caches {
    private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024
    private const val MAX_VIDEO_IDS = 1_000
    private const val VIDEO_ID_TTL_MS = 30L * 24 * 60 * 60 * 1000

    @Volatile private var videoIds: SharedPreferences? = null

    /** Safe to call from every entry point (activity, listener service); only the first does work. */
    fun install(context: Context) {
        val app = context.applicationContext
        if (HttpResponseCache.getInstalled() == null) {
            runCatching { HttpResponseCache.install(File(app.cacheDir, "http"), HTTP_CACHE_BYTES) }
                .onFailure { Log.w(TAG, "HTTP cache unavailable", it) }
        }
        if (videoIds == null) {
            synchronized(this) {
                if (videoIds == null) videoIds = app.getSharedPreferences("video_ids", Context.MODE_PRIVATE).also(::prune)
            }
        }
    }

    /** Writes pending HTTP cache entries; call when the app goes to the background. */
    fun flush() {
        HttpResponseCache.getInstalled()?.flush()
    }

    // Song keys are hashed: no titles on disk, and no characters XML can't store.
    private fun hashed(songKey: String) = MessageDigest.getInstance("SHA-256")
        .digest(songKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** A remembered video ID for this song, or null (unknown, expired, or not yet installed). */
    fun videoId(songKey: String): String? {
        val prefs = videoIds ?: return null
        val k = hashed(songKey)
        val (id, savedAt) = prefs.getString(k, null)?.split('|')?.takeIf { it.size == 2 } ?: return null
        val fresh = (savedAt.toLongOrNull() ?: 0L) > System.currentTimeMillis() - VIDEO_ID_TTL_MS
        if (!fresh || !isValidVideoId(id)) {
            prefs.edit().remove(k).apply()
            return null
        }
        return id
    }

    fun putVideoId(songKey: String, id: String) {
        val prefs = videoIds ?: return
        if (!isValidVideoId(id)) return
        prefs.edit().putString(hashed(songKey), "$id|${System.currentTimeMillis()}").apply()
        // Prune in batches rather than on every write.
        if (prefs.all.size > MAX_VIDEO_IDS + 100) prune(prefs)
    }

    /** Drops expired entries, then the oldest until at most [MAX_VIDEO_IDS] remain. */
    private fun prune(prefs: SharedPreferences) {
        val cutoff = System.currentTimeMillis() - VIDEO_ID_TTL_MS
        val entries = prefs.all.mapNotNull { (k, v) ->
            (v as? String)?.substringAfter('|')?.toLongOrNull()?.let { k to it }
        }
        val drop = entries.filter { it.second < cutoff }.map { it.first } +
            entries.filter { it.second >= cutoff }.sortedByDescending { it.second }.drop(MAX_VIDEO_IDS).map { it.first }
        if (drop.isEmpty()) return
        prefs.edit().apply { drop.forEach(::remove) }.apply()
        Log.i(TAG, "pruned ${drop.size} saved video IDs")
    }
}

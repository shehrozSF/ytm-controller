package com.shehrozsf.ytmcontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tiny thumbnail loader: HTTPS from YouTube/Google/Last.fm image hosts only (or a content:// URI
 * shared by YTM), downsampled to list size and kept in a small memory cache.
 */
object ArtworkLoader {
    private const val TARGET_PX = 160
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val ALLOWED_HOSTS = listOf(
        "ytimg.com", "ggpht.com", "googleusercontent.com",
        // Last.fm's image CDNs (suggestion covers).
        "lastfm-img.freetls.fastly.net", "lastfm.freetls.fastly.net", "lastfm-img2.akamaized.net",
        // VocaDB thumbnails for songs without a YouTube upload (its own and NicoNico's).
        "static.vocadb.net", "nicovideo.cdn.nimg.jp",
    )
    /**
     * Decoded thumbnails in memory, limited by size (KB) rather than count: 1/8 of the app's
     * memory, at most 32 MB. Downloads are also kept on disk by [Caches], so anything evicted
     * here reloads from disk instead of the network.
     */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtMost(32 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    /** Android is short on memory: drop decoded thumbnails (they reload from the disk cache). */
    fun trim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.evictAll()
        else cache.trimToSize(cache.maxSize() / 2)
    }

    private fun allowedHttps(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return uri.scheme == "https" && ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Already-downloaded art, so revisited rows can show it on the first frame. */
    fun cached(uriString: String): Bitmap? = cache.get(uriString)

    suspend fun load(context: Context, uriString: String): Bitmap? {
        cache.get(uriString)?.let { return it }
        val uri = Uri.parse(uriString)
        val bytes = when {
            allowedHttps(uri) -> httpsBytes(uriString, MAX_BYTES)
            uri.scheme == "content" -> withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readNBytes(MAX_BYTES + 1).takeIf { it.size <= MAX_BYTES }
                    }
                }.getOrNull()
            }
            else -> null
        } ?: run {
            // Host only, never the full URL. Mostly catches a CDN missing from ALLOWED_HOSTS.
            Log.w("Artwork", "no bytes from ${uri.scheme}://${uri.host} (allowed=${allowedHttps(uri)})")
            return null
        }
        return withContext(Dispatchers.Default) { decodeSmall(bytes) }?.also { cache.put(uriString, it) }
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private sealed interface ArtState {
    data object Loading : ArtState
    data object None : ArtState
    data class Shown(val bitmap: Bitmap) : ArtState
}

private fun initialArt(bitmap: Bitmap?, uri: String?, pending: Boolean): ArtState = when {
    bitmap != null -> ArtState.Shown(bitmap)
    uri != null -> ArtworkLoader.cached(uri)?.let(ArtState::Shown) ?: ArtState.Loading
    pending -> ArtState.Loading
    else -> ArtState.None
}

/**
 * Square thumbnail. A small spinner while the art is being found or downloaded ([pending]
 * covers the "still looking up where the art is" step), a ♪ when there is none, else the art.
 */
@Composable
fun Thumbnail(bitmap: Bitmap?, uri: String?, size: Dp = 48.dp, pending: Boolean = false) {
    val context = LocalContext.current
    val art by produceState(initialArt(bitmap, uri, pending), bitmap, uri, pending) {
        // produceState keeps its last value when the keys change (e.g. a reused list row
        // now showing a different song), so reset before loading the new art.
        value = initialArt(bitmap, uri, pending)
        if (value == ArtState.Loading && uri != null) {
            value = ArtworkLoader.load(context, uri)?.let(ArtState::Shown) ?: ArtState.None
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val a = art) {
            is ArtState.Shown -> Image(
                a.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            ArtState.Loading -> CircularProgressIndicator(Modifier.size(size * 0.4f), color = muted, strokeWidth = 2.dp)
            ArtState.None -> Text("♪", color = muted.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium)
        }
    }
}

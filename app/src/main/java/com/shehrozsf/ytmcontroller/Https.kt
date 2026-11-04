package com.shehrozsf.ytmcontroller

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val TAG = "Https"
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

/** Hosts allowed to receive the user's OAuth token. */
private val TOKEN_HOSTS = setOf("www.googleapis.com")

/** Hosts allowed to receive the Holodex API key. */
private val HOLODEX_KEY_HOSTS = setOf("holodex.net")

/**
 * The single place the app talks to the network: HTTPS only, timeouts, no redirects
 * followed, a capped response size, no cookies, and the OAuth token pinned to Google's API.
 */
internal suspend fun httpsRequest(
    url: String,
    jsonBody: String? = null,
    bearer: String? = null,
    origin: String? = null,
    /** Return the body of non-200 responses too, for APIs that explain errors in JSON. */
    readErrorBody: Boolean = false,
    /** Holodex API key, sent as X-APIKEY and only ever to holodex.net. */
    holodexKey: String? = null,
): String? = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as? HttpsURLConnection ?: return@withContext null
    guarded(conn) {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false
        if (bearer != null) {
            require(conn.url.host in TOKEN_HOSTS) { "token sent to unexpected host" }
            conn.setRequestProperty("Authorization", "Bearer $bearer")
        }
        if (holodexKey != null) {
            require(conn.url.host in HOLODEX_KEY_HOSTS) { "Holodex key sent to unexpected host" }
            conn.setRequestProperty("X-APIKEY", holodexKey)
        }
        origin?.let { conn.setRequestProperty("Origin", it) }
        if (jsonBody != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(jsonBody.toByteArray()) }
        }
        val ok = conn.responseCode == HttpURLConnection.HTTP_OK
        if (!ok) {
            // Host only: URLs may carry query text (and API keys) we don't want in logs.
            Log.w(TAG, "HTTP ${conn.responseCode} from ${conn.url.host}")
            if (!readErrorBody) return@guarded null
        }
        val stream = (if (ok) conn.inputStream else conn.errorStream) ?: return@guarded null
        stream.use { readCapped(it, MAX_RESPONSE_BYTES) }?.toString(Charsets.UTF_8)
    }
}

/**
 * Runs one request. A network failure (timeout, dropped connection…) returns null like any
 * other failed request instead of throwing, which used to crash the app from a thumbnail.
 * A fully read response leaves its connection open so the next request to that host reuses
 * it (no new TLS handshake per thumbnail); anything else closes it.
 */
private inline fun <T : Any> guarded(conn: HttpsURLConnection, block: () -> T?): T? {
    var result: T? = null
    try {
        result = block()
        return result
    } catch (e: IOException) {
        Log.w(TAG, "${e.javaClass.simpleName} from ${conn.url.host}")
        return null
    } finally {
        if (result == null) conn.disconnect()
    }
}

private fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size() + n > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Downloads a small binary file (e.g. a thumbnail); same rules as [httpsRequest], no credentials. */
internal suspend fun httpsBytes(url: String, maxBytes: Int = MAX_RESPONSE_BYTES): ByteArray? = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as? HttpsURLConnection ?: return@withContext null
    guarded(conn) {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return@guarded null
        conn.inputStream.use { readCapped(it, maxBytes) }
    }
}

/**
 * Just the HTTP status of a GET (body discarded), e.g. to test an API key; null when the
 * server couldn't be reached. Same rules as [httpsRequest], key pinned to holodex.net.
 */
internal suspend fun httpsStatus(url: String, holodexKey: String? = null): Int? = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as? HttpsURLConnection ?: return@withContext null
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false
        if (holodexKey != null) {
            require(conn.url.host in HOLODEX_KEY_HOSTS) { "Holodex key sent to unexpected host" }
            conn.setRequestProperty("X-APIKEY", holodexKey)
        }
        conn.responseCode
    } catch (e: IOException) {
        Log.w(TAG, "${e.javaClass.simpleName} from ${conn.url.host}")
        null
    } finally {
        conn.disconnect()
    }
}

/** Reads a redirect's Location header without following it or sending any credentials. */
internal suspend fun httpsRedirectTarget(url: String): String? = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as? HttpsURLConnection ?: return@withContext null
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false
        if (conn.responseCode !in 300..399) return@withContext null
        conn.getHeaderField("Location")
    } catch (e: IOException) {
        Log.w(TAG, "${e.javaClass.simpleName} from ${conn.url.host}")
        null
    } finally {
        // The redirect body is never read, so this connection can't be reused anyway.
        conn.disconnect()
    }
}

/**
 * Makes untrusted text (song titles, API error messages…) safe to log: control characters
 * and line/paragraph separators become spaces, so a crafted title can't forge extra log
 * lines, and long values are cut short.
 */
private const val LINE_SEPARATOR = 0x2028      // Unicode line separator
private const val PARAGRAPH_SEPARATOR = 0x2029 // Unicode paragraph separator

internal fun logSafe(s: Any?, max: Int = 120): String {
    val clean = s?.toString().orEmpty()
        .map { if (it.isISOControl() || it.code == LINE_SEPARATOR || it.code == PARAGRAPH_SEPARATOR) ' ' else it }
        .joinToString("")
    return if (clean.length > max) clean.take(max) + "…" else clean
}

/** Letters and digits only (any script), so "Bunny Girl / 輪堂千速（cover）" compares reliably. */
internal fun normalizeTitle(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

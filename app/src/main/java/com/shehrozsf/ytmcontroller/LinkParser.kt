package com.shehrozsf.ytmcontroller

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * Turns untrusted text (pasted, or shared from another app) into a YouTube song and/or
 * playlist ID. Plain Kotlin with no Android types, so it's unit-tested on the JVM.
 *
 * The output is the only thing that ever reaches a URL or intent, and it's built from
 * validated IDs alone: everything else in the input (other hosts, schemes, parameters,
 * path tricks, encoded characters) is ignored or rejected.
 */
object LinkParser {
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private const val MAX_INPUT = 2_000

    /** The first YouTube / YT Music / youtu.be link in [text], or null if there isn't one. */
    fun parse(text: String): LinkTarget? {
        val input = text.take(MAX_INPUT)
        val raw = URL.find(input)?.value ?: input.trim()
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        // userInfo ("https://youtube.com@evil.example/…") or an explicit port: not a real YouTube link.
        if (uri.rawUserInfo != null || uri.port != -1) return null
        val host = uri.host?.lowercase() ?: return null

        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = queryParams(uri.rawQuery)
        val video = when {
            host == "youtu.be" -> segments.firstOrNull()
            host in YOUTUBE_HOSTS && segments.firstOrNull() == "shorts" -> segments.getOrNull(1)
            host in YOUTUBE_HOSTS -> query["v"]
            else -> return null
        }?.takeIf(::isValidVideoId)
        val list = query["list"]?.takeIf(YtmWatch::isValidPlaylistId)
        return if (video == null && list == null) null else LinkTarget(video, list)
    }

    /** First value of each query parameter, percent-decoded (validation happens afterwards). */
    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: continue
            out.putIfAbsent(key, decoded)
        }
        return out
    }
}

package com.shehrozsf.ytmcontroller

import com.shehrozsf.ytmcontroller.SuggestionSource.LASTFM
import com.shehrozsf.ytmcontroller.SuggestionSource.VOCADB
import com.shehrozsf.ytmcontroller.SuggestionSource.YTM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionMergeTest {
    private fun s(
        title: String,
        artist: String = "A",
        src: List<SuggestionSource> = listOf(LASTFM),
        image: String? = null,
        video: String? = null,
    ) = Suggestion(title, artist, imageUrl = image, videoId = video, sources = src)

    @Test fun `interleave alternates the two lanes`() {
        val out = SuggestionMerge.interleave(listOf(s("a1"), s("a2"), s("a3")), listOf(s("b1")))
        assertEquals(listOf("a1", "b1", "a2", "a3"), out.map { it.title })
    }

    @Test fun `duplicate song appears once and lists every source`() {
        val out = SuggestionMerge.interleave(listOf(s("Lemon", src = listOf(YTM))), listOf(s("lemon!", src = listOf(LASTFM))))
        assertEquals(1, out.size)
        assertEquals(listOf(YTM, LASTFM), out.single().sources)
    }

    @Test fun `YT Music cover and video win even when its row comes second`() {
        val lastFmFirst = s("Lemon", src = listOf(LASTFM), image = "https://lastfm/img.png")
        val ytmSecond = s("Lemon", src = listOf(YTM), image = "https://i.ytimg.com/vi/x/hq.jpg", video = "dQw4w9WgXcQ")
        val merged = SuggestionMerge.interleave(listOf(lastFmFirst), listOf(ytmSecond)).single()
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", merged.imageUrl)
        assertEquals("dQw4w9WgXcQ", merged.videoId)
    }

    @Test fun `without YT Music the first image is kept and gaps are filled`() {
        val a = s("Song", src = listOf(LASTFM), image = null)
        val b = s("Song", src = listOf(VOCADB), image = "https://static.vocadb.net/x.jpg", video = "dQw4w9WgXcQ")
        val merged = SuggestionMerge.interleave(listOf(a), listOf(b)).single()
        assertEquals("https://static.vocadb.net/x.jpg", merged.imageUrl)
        assertEquals("dQw4w9WgXcQ", merged.videoId)
    }

    @Test fun `titles with no letters or digits are dropped`() {
        assertTrue(SuggestionMerge.interleave(listOf(s("!!!")), emptyList()).isEmpty())
    }

    @Test fun `capPerArtist limits each artist case-insensitively and keeps order`() {
        val items = listOf(s("1", "Ado"), s("2", "ado"), s("3", "ADO "), s("4", "Reol"))
        assertEquals(listOf("1", "2", "4"), SuggestionMerge.capPerArtist(items, 2).map { it.title })
    }

    @Test fun `isVersionOf catches covers and re-uploads of the current song`() {
        val seed = normalizeTitle("Lemon")
        assertTrue(SuggestionMerge.isVersionOf("Lemon (Cover)", seed))
        assertTrue(SuggestionMerge.isVersionOf("米津玄師 MV「Lemon」", seed))
        assertFalse(SuggestionMerge.isVersionOf("Flamingo", seed))
    }

    @Test fun `isVersionOf needs an exact match for very short titles`() {
        val seed = normalizeTitle("A")
        assertTrue(SuggestionMerge.isVersionOf("a", seed))
        assertFalse(SuggestionMerge.isVersionOf("Alone", seed)) // would be a false positive with contains()
    }
}

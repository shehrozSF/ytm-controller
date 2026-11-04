package com.shehrozsf.ytmcontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Links arrive from the clipboard or other apps, so they're untrusted input. */
class LinkParserTest {
    private val id = "dQw4w9WgXcQ"
    private val playlist = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"

    // --- Accepted forms ---

    @Test fun `music youtube song link`() {
        assertEquals(LinkTarget(id, null), LinkParser.parse("https://music.youtube.com/watch?v=$id"))
    }

    @Test fun `youtu be short link with tracking parameter`() {
        assertEquals(LinkTarget(id, null), LinkParser.parse("https://youtu.be/$id?si=AbCdEf123"))
    }

    @Test fun `shorts link`() {
        assertEquals(LinkTarget(id, null), LinkParser.parse("https://www.youtube.com/shorts/$id"))
    }

    @Test fun `playlist link`() {
        val t = LinkParser.parse("https://music.youtube.com/playlist?list=$playlist")
        assertEquals(LinkTarget(null, playlist), t)
        assertTrue(t!!.hasPlaylist)
    }

    @Test fun `song inside playlist keeps both`() {
        assertEquals(LinkTarget(id, playlist), LinkParser.parse("https://www.youtube.com/watch?v=$id&list=$playlist&index=3"))
    }

    @Test fun `radio mix list is not treated as a playlist`() {
        val t = LinkParser.parse("https://music.youtube.com/watch?v=$id&list=RDAMVM$id")
        assertFalse(t!!.hasPlaylist)
    }

    @Test fun `link inside shared text`() {
        val shared = "Check this out! https://youtu.be/$id great song"
        assertEquals(LinkTarget(id, null), LinkParser.parse(shared))
    }

    @Test fun `scheme and host are case-insensitive`() {
        assertEquals(LinkTarget(id, null), LinkParser.parse("HTTPS://Music.YouTube.com/watch?v=$id"))
    }

    // --- Rejected: not YouTube, or trying to look like it ---

    @Test fun `other hosts are rejected`() {
        assertNull(LinkParser.parse("https://evil.example/watch?v=$id"))
        assertNull(LinkParser.parse("https://youtube.com.evil.example/watch?v=$id"))
        assertNull(LinkParser.parse("https://notyoutube.com/watch?v=$id"))
    }

    @Test fun `userinfo trick is rejected`() {
        assertNull(LinkParser.parse("https://youtube.com@evil.example/watch?v=$id"))
    }

    @Test fun `explicit port is rejected`() {
        assertNull(LinkParser.parse("https://youtube.com:8443/watch?v=$id"))
    }

    @Test fun `non-web schemes are rejected`() {
        assertNull(LinkParser.parse("javascript:alert(1)"))
        assertNull(LinkParser.parse("intent://watch?v=$id#Intent;scheme=https;end"))
        assertNull(LinkParser.parse("file:///sdcard/watch?v=$id"))
        assertNull(LinkParser.parse("content://media/watch?v=$id"))
    }

    // --- Rejected: IDs that aren't IDs ---

    @Test fun `injected characters in the video id are rejected`() {
        assertNull(LinkParser.parse("https://youtube.com/watch?v=$id%26list%3DPLevil"))
        assertNull(LinkParser.parse("https://youtube.com/watch?v=$id%0Aextra"))
        assertNull(LinkParser.parse("https://youtube.com/watch?v=../../../etc"))
        assertNull(LinkParser.parse("https://youtu.be/$id%2F..%2Fadmin"))
    }

    @Test fun `wrong length ids are rejected`() {
        assertNull(LinkParser.parse("https://youtube.com/watch?v=short"))
        assertNull(LinkParser.parse("https://youtube.com/watch?v=${id}X"))
    }

    @Test fun `bad playlist id is dropped but a valid song survives`() {
        assertEquals(LinkTarget(id, null), LinkParser.parse("https://youtube.com/watch?v=$id&list=PL%3Cscript%3E"))
    }

    @Test fun `duplicate parameters use the first value`() {
        assertEquals(LinkTarget(id, null), LinkParser.parse("https://youtube.com/watch?v=$id&v=AAAAAAAAAAA"))
    }

    @Test fun `garbage and empty input give null`() {
        assertNull(LinkParser.parse(""))
        assertNull(LinkParser.parse("   "))
        assertNull(LinkParser.parse("not a link at all"))
        assertNull(LinkParser.parse("https://"))
        assertNull(LinkParser.parse("https://youtube.com/watch?v="))
    }

    @Test fun `very long input is bounded and does not throw`() {
        val huge = "x".repeat(1_000_000) + " https://youtu.be/$id"
        assertNull(LinkParser.parse(huge)) // the link is beyond the 2,000-character window
    }
}

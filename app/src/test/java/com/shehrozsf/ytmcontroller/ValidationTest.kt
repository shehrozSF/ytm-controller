package com.shehrozsf.ytmcontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ID checks every URL and intent in the app depends on, plus log sanitising. */
class ValidationTest {
    @Test fun `valid video ids`() {
        assertTrue(isValidVideoId("dQw4w9WgXcQ"))
        assertTrue(isValidVideoId("a-b_c-d_e-f"))
    }

    @Test fun `invalid video ids`() {
        // Full-width look-alike of a real ID, built from char codes (U+FF41 = 'a' … ).
        val fullWidth = "dQw4w9WgXcQ".map { if (it.isLetterOrDigit()) Char(it.code + 0xFEE0) else it }.joinToString("")
        listOf(
            null, "", "short", "dQw4w9WgXcQX", "dQw4w9WgXc!", "dQw4w9WgXc/", "dQw4w9WgXc ",
            "dQw4w9WgXc\n", "dQw4w9WgXc&", "../../../..", "<script>xx>", fullWidth,
        ).forEach { assertFalse("accepted: $it", isValidVideoId(it)) }
    }

    @Test fun `valid playlist ids`() {
        listOf("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", "OLAK5uy_abc-123", "LL", "LM", "RDAMVMdQw4w9WgXcQ")
            .forEach { assertTrue("rejected: $it", YtmWatch.isValidPlaylistId(it)) }
    }

    @Test fun `invalid playlist ids`() {
        listOf(null, "", "P", "PL&v=x", "PL/../x", "PL<x>", "PL x", "P".repeat(81))
            .forEach { assertFalse("accepted: $it", YtmWatch.isValidPlaylistId(it)) }
    }

    @Test fun `logSafe neutralises line breaks and control characters`() {
        // Built from char codes so the source file itself contains no invisible characters.
        val nul = Char(0)
        val lineSeparator = Char(0x2028)
        val forged = "Song\nE/AndroidRuntime: FATAL EXCEPTION\r" + nul + lineSeparator + "x"
        val safe = logSafe(forged)
        assertFalse(safe.any { it == '\n' || it == '\r' || it == nul || it == lineSeparator })
        assertTrue(safe.startsWith("Song E/AndroidRuntime"))
    }

    @Test fun `logSafe truncates long values`() {
        assertEquals("abc…", logSafe("abcdef", max = 3))
        assertEquals("", logSafe(null))
    }

    @Test fun `normalizeTitle keeps letters and digits in any script`() {
        assertEquals("bunnygirl輪堂千速cover", normalizeTitle("Bunny Girl / 輪堂千速（cover）"))
        assertEquals("ado", normalizeTitle("  A.D.O!! "))
    }

    @Test fun `VocaDB title cleaning strips upload noise but keeps the song name`() {
        assertEquals("千本桜", VocaDb.cleanTitle("【初音ミク】千本桜【オリジナル】"))
        assertEquals("パリィ", VocaDb.cleanTitle("パリィ (feat. 鏡音レン&鏡音リン)"))
        assertEquals("人生", VocaDb.cleanTitle("人生 (KAITO Ver.)"))
        assertEquals("Song (Remix)", VocaDb.cleanTitle("Song (Remix)"))
    }
}

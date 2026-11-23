package com.tuxundown.android

import com.tuxundown.android.core.LinkSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSchemesTest {

    @Test
    fun supportedSchemesPass() {
        assertTrue(LinkSchemes.isSupported("http://a.com/f.zip"))
        assertTrue(LinkSchemes.isSupported("https://a.com/f.zip"))
        assertTrue(LinkSchemes.isSupported("ftp://a.com/f.zip"))
        // Scheme case is not significant.
        assertTrue(LinkSchemes.isSupported("HTTP://a.com/f.zip"))
        assertTrue(LinkSchemes.isSupported("Https://a.com/f.zip"))
        // Surrounding whitespace is the caller's paste, not part of the link.
        assertTrue(LinkSchemes.isSupported("  https://a.com/f.zip  "))
    }

    @Test
    fun hostileAndUselessSchemesAreRefused() {
        // The audit's point: none of these may become a task.
        assertFalse(LinkSchemes.isSupported("file:///sdcard/secret.zip"))
        assertFalse(LinkSchemes.isSupported("content://media/external/file/1"))
        assertFalse(LinkSchemes.isSupported("intent://x#Intent;scheme=https;end"))
        assertFalse(LinkSchemes.isSupported("javascript:alert(1)"))
        assertFalse(LinkSchemes.isSupported("data:text/plain;base64,AAAA"))
        assertFalse(LinkSchemes.isSupported("blob:https://a.com/1234"))
        assertFalse(LinkSchemes.isSupported("magnet:?xt=urn:btih:abc"))
        assertFalse(LinkSchemes.isSupported("thunder://QUFodHRwOi8vYS5jb20vZi56aXBaWg=="))
        assertFalse(LinkSchemes.isSupported("ed2k://|file|x|1|h|/"))
    }

    @Test
    fun schemelessAndMalformedAreRefused() {
        assertFalse(LinkSchemes.isSupported("a.com/f.zip"))
        assertFalse(LinkSchemes.isSupported("www.a.com/f.zip"))
        assertFalse(LinkSchemes.isSupported("/sdcard/f.zip"))
        assertFalse(LinkSchemes.isSupported("http:example.com"))
        assertFalse(LinkSchemes.isSupported("https:/one-slash.com/f"))
        assertFalse(LinkSchemes.isSupported(""))
        assertFalse(LinkSchemes.isSupported("   "))
    }

    // v6.3 step C: the mirror fields' cleaning IS isSupported —
    // these pin the cleanMirrors layer itself (the scheme verdicts
    // above already pin isSupported on its own).

    @Test
    fun mirrorCleaningAcceptsHttpHttpsFtp() {
        val kept = LinkSchemes.cleanMirrors(
            listOf(
                "http://m1.com/f.zip",
                "https://m2.com/f.zip",
                // An ftp mirror was NOT addable under the old
                // startsWith("http") caliber; now it is.
                "ftp://m3.com/f.zip",
            ),
            primaryUrl = "https://a.com/f.zip",
        )
        assertEquals(
            listOf(
                "http://m1.com/f.zip",
                "https://m2.com/f.zip",
                "ftp://m3.com/f.zip",
            ),
            kept,
        )
    }

    @Test
    fun mirrorCleaningAcceptsUppercaseSchemeAndTrims() {
        val kept = LinkSchemes.cleanMirrors(
            listOf("  HTTP://m1.com/f.zip  ", "\tHttps://m2.com/f.zip "),
            primaryUrl = "https://a.com/f.zip",
        )
        assertEquals(
            listOf("HTTP://m1.com/f.zip", "Https://m2.com/f.zip"),
            kept,
        )
    }

    @Test
    fun mirrorCleaningRejectsUnsupportedAndGarbage() {
        val kept = LinkSchemes.cleanMirrors(
            listOf(
                "file:///sdcard/f.zip",
                // Merely starts with the letters "http": the old
                // caliber kept these, isSupported refuses them.
                "http",
                "http乱写",
                "http:example.com/f.zip",
                "",
                "   ",
            ),
            primaryUrl = "https://a.com/f.zip",
        )
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun mirrorCleaningDropsPrimaryAndDuplicatesKeepingOrder() {
        val kept = LinkSchemes.cleanMirrors(
            listOf(
                "https://m2.com/f.zip",
                "https://a.com/f.zip", // the primary itself
                "https://m1.com/f.zip",
                " https://m2.com/f.zip ", // duplicate after trim
            ),
            primaryUrl = "https://a.com/f.zip",
        )
        assertEquals(
            listOf("https://m2.com/f.zip", "https://m1.com/f.zip"),
            kept,
        )
        // An empty field cleans to an empty list.
        assertEquals(
            emptyList<String>(),
            LinkSchemes.cleanMirrors(emptyList(), "https://a.com/f.zip"),
        )
    }

    @Test
    fun sharedTextIsTrimmedAndCapped() {
        assertEquals("", LinkSchemes.sanitizeSharedText(null))
        assertEquals("abc", LinkSchemes.sanitizeSharedText("  abc  "))
        val huge = "x".repeat(LinkSchemes.MAX_SHARED_CHARS + 5000)
        assertEquals(
            LinkSchemes.MAX_SHARED_CHARS,
            LinkSchemes.sanitizeSharedText(huge).length,
        )
        // A normal share passes through untouched.
        val normal = "看看这个 https://a.com/f.zip 好东西"
        assertEquals(normal, LinkSchemes.sanitizeSharedText(normal))
    }

    @Test
    fun userNoteIsTrimmedAndCapped() {
        assertEquals("给娃下的", LinkSchemes.sanitizeUserNote("  给娃下的  "))
        assertEquals("", LinkSchemes.sanitizeUserNote("   "))
        val long = "注".repeat(LinkSchemes.MAX_USER_NOTE_CHARS + 50)
        assertEquals(
            LinkSchemes.MAX_USER_NOTE_CHARS,
            LinkSchemes.sanitizeUserNote(long).length,
        )
    }
}

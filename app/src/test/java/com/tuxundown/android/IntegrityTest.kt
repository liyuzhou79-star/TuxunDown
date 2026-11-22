package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 audit round 10 (byte integrity): the pure verdicts the
 * engine binds chunk / single-stream responses to the probed
 * file with — Content-Range parsing and the strong-ETag rule.
 */
class IntegrityTest {

    @Test
    fun contentRangeTotalParses() {
        assertEquals(1024L, DownloadEngine.contentRangeTotal("bytes 0-0/1024"))
        assertEquals(
            271440852L,
            DownloadEngine.contentRangeTotal("bytes 100-199/271440852"),
        )
        assertEquals(-1L, DownloadEngine.contentRangeTotal("bytes 0-0/*"))
        assertEquals(-1L, DownloadEngine.contentRangeTotal(""))
        assertEquals(-1L, DownloadEngine.contentRangeTotal(null))
        assertEquals(-1L, DownloadEngine.contentRangeTotal("garbage"))
    }

    @Test
    fun contentRangeStartParses() {
        assertEquals(0L, DownloadEngine.contentRangeStart("bytes 0-0/1024"))
        assertEquals(500L, DownloadEngine.contentRangeStart("bytes 500-999/2000"))
        assertEquals(-1L, DownloadEngine.contentRangeStart(""))
        assertEquals(-1L, DownloadEngine.contentRangeStart(null))
        assertEquals(-1L, DownloadEngine.contentRangeStart("bytes */2000"))
    }

    @Test
    fun missingContentRangeStartCondemnsAnyOffset() {
        // v4.1 audit round 26: the segmented path judges a 206
        // by contentRangeStart(cr) != from — a missing or
        // garbled header parses to -1, which matches NO legal
        // offset (from >= 0), so the old "unparsable = excused"
        // hole is closed by this parse alone.
        assertTrue(DownloadEngine.contentRangeStart(null) != 0L)
        assertTrue(DownloadEngine.contentRangeStart("") != 500L)
        assertTrue(DownloadEngine.contentRangeStart("garbage") != 500L)
        assertEquals(500L, DownloadEngine.contentRangeStart("bytes 500-999/2000"))
    }

    @Test
    fun strongEtagMismatchCondemns() {
        assertTrue(DownloadEngine.etagChanged("\"abc123\"", "\"def456\""))
        assertFalse(DownloadEngine.etagChanged("\"abc123\"", "\"abc123\""))
    }

    @Test
    fun weakOrMissingEtagNeverCondemns() {
        // A weak tag does not testify byte identity (RFC 9110).
        assertFalse(DownloadEngine.etagChanged("W/\"abc\"", "\"def\""))
        assertFalse(DownloadEngine.etagChanged("\"abc\"", "W/\"def\""))
        assertFalse(DownloadEngine.etagChanged("", "\"def\""))
        assertFalse(DownloadEngine.etagChanged("\"abc\"", ""))
        assertFalse(DownloadEngine.etagChanged("", ""))
    }

    @Test
    fun tsSegmentDetection() {
        assertTrue(DownloadEngine.isTsSegment("https://cdn.example.com/v/seg-12.ts"))
        assertTrue(DownloadEngine.isTsSegment("https://cdn.example.com/v/seg.TS?token=1"))
        assertFalse(DownloadEngine.isTsSegment("https://cdn.example.com/v/audio.aac"))
        assertFalse(DownloadEngine.isTsSegment("https://cdn.example.com/v/seg.mp3"))
        assertFalse(DownloadEngine.isTsSegment("https://cdn.example.com/v/seg.ts.m3u8"))
    }
}

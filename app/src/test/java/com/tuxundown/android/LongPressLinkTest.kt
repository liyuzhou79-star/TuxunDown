package com.tuxundown.android

import com.tuxundown.android.core.LongPressLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressLinkTest {

    @Test
    fun downloadableLinkHitAsksForConfirmation() {
        assertEquals(
            LongPressLink.Action.CONFIRM_DOWNLOAD,
            LongPressLink.actionFor(true, "https://example.com/files/big.zip"),
        )
        assertEquals(
            LongPressLink.Action.CONFIRM_DOWNLOAD,
            LongPressLink.actionFor(true, "http://example.com/a.apk"),
        )
        assertEquals(
            LongPressLink.Action.CONFIRM_DOWNLOAD,
            LongPressLink.actionFor(true, "ftp://example.com/pub/x.iso"),
        )
        // Scheme case does not matter.
        assertEquals(
            LongPressLink.Action.CONFIRM_DOWNLOAD,
            LongPressLink.actionFor(true, "HTTPS://example.com/a.apk"),
        )
    }

    @Test
    fun nonDownloadableLinkHitExplains() {
        // The schemes the WebView hands over that the engine
        // cannot fetch at all — the press must SAY so, not
        // silently do nothing (A-F1).
        assertEquals(
            LongPressLink.Action.EXPLAIN_NOT_DOWNLOADABLE,
            LongPressLink.actionFor(true, "blob:https://example.com/9f2c"),
        )
        assertEquals(
            LongPressLink.Action.EXPLAIN_NOT_DOWNLOADABLE,
            LongPressLink.actionFor(true, "data:text/plain;base64,SGVsbG8="),
        )
        assertEquals(
            LongPressLink.Action.EXPLAIN_NOT_DOWNLOADABLE,
            LongPressLink.actionFor(true, "file:///sdcard/secret.txt"),
        )
        assertEquals(
            LongPressLink.Action.EXPLAIN_NOT_DOWNLOADABLE,
            LongPressLink.actionFor(true, "javascript:void(0)"),
        )
    }

    @Test
    fun emptyOrNonLinkHitIsIgnored() {
        assertEquals(
            LongPressLink.Action.IGNORE,
            LongPressLink.actionFor(true, null),
        )
        assertEquals(
            LongPressLink.Action.IGNORE,
            LongPressLink.actionFor(true, ""),
        )
        assertEquals(
            LongPressLink.Action.IGNORE,
            LongPressLink.actionFor(true, "   "),
        )
        // Not a link hit: even a perfect URL under the finger
        // is not ours to act on.
        assertEquals(
            LongPressLink.Action.IGNORE,
            LongPressLink.actionFor(false, "https://example.com/files/big.zip"),
        )
        assertEquals(
            LongPressLink.Action.IGNORE,
            LongPressLink.actionFor(false, null),
        )
    }

    @Test
    fun explanationWordingMatchesDownloadListener() {
        // The long-press toast and the download-listener toast
        // are the SAME sentence (A-F1: reuse, do not reword) —
        // both call sites build it here.
        assertEquals(
            "抓到的不是能下载的链接（blob），已忽略",
            LongPressLink.notDownloadableMessage("blob:https://example.com/9f2c"),
        )
        assertEquals(
            "抓到的不是能下载的链接（data），已忽略",
            LongPressLink.notDownloadableMessage("DATA:text/plain,x"),
        )
    }

    @Test
    fun downloadableSchemesMatchTheEngine() {
        assertTrue(LongPressLink.isDownloadable("http://a.b/c"))
        assertTrue(LongPressLink.isDownloadable("https://a.b/c"))
        assertTrue(LongPressLink.isDownloadable("ftp://a.b/c"))
        assertFalse(LongPressLink.isDownloadable("blob:https://a.b/c"))
        assertFalse(LongPressLink.isDownloadable("magnet:?xt=urn:btih:x"))
        assertFalse(LongPressLink.isDownloadable(""))
    }
}

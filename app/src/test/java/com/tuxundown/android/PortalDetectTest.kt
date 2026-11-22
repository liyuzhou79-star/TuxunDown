package com.tuxundown.android

import com.tuxundown.android.core.PortalDetect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.4 (audit round 29): the ONE captive-portal detector. */
class PortalDetectTest {

    @Test
    fun htmlContentTypesArePortals() {
        assertTrue(PortalDetect.isHtmlContentType("text/html; charset=utf-8"))
        assertTrue(PortalDetect.isHtmlContentType("Text/HTML"))
        assertTrue(PortalDetect.isHtmlContentType("application/xhtml+xml"))
    }

    @Test
    fun fileContentTypesAreNotPortals() {
        assertFalse(PortalDetect.isHtmlContentType("application/octet-stream"))
        assertFalse(PortalDetect.isHtmlContentType("application/vnd.apple.mpegurl"))
        assertFalse(PortalDetect.isHtmlContentType("video/mp2t"))
        assertFalse(PortalDetect.isHtmlContentType(null))
        assertFalse(PortalDetect.isHtmlContentType(""))
    }

    @Test
    fun htmlHeadsAreDetected() {
        assertTrue(PortalDetect.looksLikeHtml("<!DOCTYPE html><html><body>login"))
        assertTrue(PortalDetect.looksLikeHtml("  \n<html lang=\"zh\">"))
        assertTrue(PortalDetect.looksLikeHtml("<head><title>WiFi 登录</title>"))
        assertTrue(
            PortalDetect.looksLikeHtml(
                "<!doctype html><html>".toByteArray(Charsets.UTF_8)
            )
        )
        // BOM + leading whitespace still counts.
        assertTrue(PortalDetect.looksLikeHtml("\uFEFF <html>"))
    }

    @Test
    fun fileHeadsAreNeverHtml() {
        // A playlist.
        assertFalse(PortalDetect.looksLikeHtml("#EXTM3U\n#EXT-X-VERSION:3"))
        // A .ts segment starts with the 0x47 sync byte.
        val ts = byteArrayOf(0x47, 0x40, 0x11, 0x10, 0x00, 0x42)
        assertFalse(PortalDetect.looksLikeHtml(ts, ts.size))
        // An AES key is arbitrary binary.
        val key = ByteArray(16) { (it * 37 + 11).toByte() }
        assertFalse(PortalDetect.looksLikeHtml(key, key.size))
        // Empty bodies judge nothing.
        assertFalse(PortalDetect.looksLikeHtml(ByteArray(0), 0))
        assertFalse(PortalDetect.looksLikeHtml(""))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.NetRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetRetryTest {

    @Test
    fun classifiesOutageMessagesAsNetworkErrors() {
        assertTrue(NetRetry.isNetworkError("Unable to resolve host \"x.com\": No address associated with hostname"))
        assertTrue(NetRetry.isNetworkError("Failed to connect to /1.2.3.4:443"))
        assertTrue(NetRetry.isNetworkError("Read timed out"))
        assertTrue(NetRetry.isNetworkError("Network is unreachable"))
        assertTrue(NetRetry.isNetworkError("Connection reset"))
    }

    @Test
    fun serverRefusalsAndLocalProblemsAreNotNetworkErrors() {
        assertFalse(NetRetry.isNetworkError("服务器返回 HTTP 403"))
        assertFalse(NetRetry.isNetworkError("服务器返回 HTTP 404"))
        assertFalse(NetRetry.isNetworkError("手机存储空间不足，下载已停下。清出空间后点继续即可续传。"))
        assertFalse(NetRetry.isNetworkError(""))
        // A timeout INSIDE an HTTP-status message must not sneak in.
        assertFalse(NetRetry.isNetworkError("服务器返回 HTTP 408 timeout"))
    }

    @Test
    fun deferralIsBounded() {
        assertTrue(NetRetry.shouldDefer(0))
        assertTrue(NetRetry.shouldDefer(NetRetry.MAX_ATTEMPTS - 1))
        assertFalse(NetRetry.shouldDefer(NetRetry.MAX_ATTEMPTS))
    }

    @Test
    fun bufferShrinksAsThreadsGrowButStaysInRange() {
        assertEquals(DownloadEngine.BUFFER_SIZE, DownloadEngine.bufferSizeFor(1))
        assertEquals(DownloadEngine.BUFFER_SIZE, DownloadEngine.bufferSizeFor(8))
        // v4.2: the total budget doubled to 4 MB with the 64-thread
        // ceiling, so 32 connections are back at the 128 KB cap...
        assertEquals(DownloadEngine.BUFFER_SIZE, DownloadEngine.bufferSizeFor(32))
        // ...and 64 connections get 64 KB each.
        assertEquals(64 * 1024, DownloadEngine.bufferSizeFor(64))
        // Worst case total stays at the 4 MB budget.
        assertTrue(DownloadEngine.bufferSizeFor(64) * 64 <= 4 * 1024 * 1024)
        assertEquals(DownloadEngine.BUFFER_SIZE, DownloadEngine.bufferSizeFor(0))
    }
}

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

    @Test
    fun bufferThreadsForUsesTheLargerCount() {
        // Audit round 27: sizing follows max(target, live workers).
        assertEquals(64, DownloadEngine.bufferThreadsFor(64, 8))
        assertEquals(64, DownloadEngine.bufferThreadsFor(8, 64))
        assertEquals(16, DownloadEngine.bufferThreadsFor(16, 16))
        assertEquals(1, DownloadEngine.bufferThreadsFor(0, 0))
    }

    @Test
    fun liveBuffersNeverExceedThePerTaskBudget() {
        // Audit round 27: for EVERY (target, live workers) pair,
        // live workers x their buffer stays within the 4 MB
        // per-task account — including the post-shrink case
        // (small target, many workers still draining) that
        // sizing by the target alone would have blown past
        // (64 workers x 128 KB at target 2 = 8 MB).
        for (target in 1..64) {
            for (live in 1..64) {
                val total = live *
                    DownloadEngine.bufferSizeFor(
                        DownloadEngine.bufferThreadsFor(target, live)
                    )
                assertTrue(
                    "target=$target live=$live total=$total",
                    total <= 4 * 1024 * 1024,
                )
            }
        }
    }

    // ---- v4.4 (audit round 29): the RAW-failure classifier ----

    @Test
    fun rawOutageExceptionsClassifyAsNetworkErrors() {
        assertTrue(NetRetry.isNetworkError(java.net.ConnectException("Failed to connect")))
        assertTrue(NetRetry.isNetworkError(java.net.UnknownHostException("x.com")))
        assertTrue(NetRetry.isNetworkError(java.net.SocketTimeoutException("timeout")))
        assertTrue(NetRetry.isNetworkError(java.net.SocketException("Connection reset")))
        // Wrapped failures still classify — the walk follows causes.
        assertTrue(
            NetRetry.isNetworkError(
                RuntimeException("boom", java.net.ConnectException("refused"))
            )
        )
        assertTrue(
            NetRetry.isNetworkError(
                java.io.IOException("Unable to resolve host \"x.com\"")
            )
        )
    }

    @Test
    fun rawNonOutagesDoNotClassify() {
        // Our own plain-Chinese refusals: never an outage.
        assertFalse(NetRetry.isNetworkError(IllegalStateException("服务器不支持 Range")))
        assertFalse(
            NetRetry.isNetworkError(
                IllegalStateException(com.tuxundown.android.core.Errors.httpMessage(403))
            )
        )
        // A full disk is an IOException with an errno message —
        // no needle, no listed type, no park.
        assertFalse(
            NetRetry.isNetworkError(java.io.IOException("No space left on device"))
        )
        // A portal verdict is its own signal, not an outage.
        assertFalse(
            NetRetry.isNetworkError(com.tuxundown.android.core.PortalBlockedException())
        )
        assertFalse(NetRetry.isNetworkError(null as Throwable?))
    }

    @Test
    fun compositionProofHumanizedTextKillsTheStringClassifier() {
        // THE round-29 headline, pinned: the engine's failure
        // text passes through Errors.human, which drops the raw
        // English — the STRING classifier sees nothing (this is
        // why the v1.5 deferral was dead for HTTP paths), while
        // the Throwable classifier, fed the same original
        // exception the engine caught, still judges correctly.
        val raw = java.net.ConnectException("Failed to connect to /1.2.3.4:443")
        val humanized = com.tuxundown.android.core.Errors.human(raw)
        assertFalse(NetRetry.isNetworkError(humanized))
        assertTrue(NetRetry.isNetworkError(raw))

        val rawDns = java.net.UnknownHostException("Unable to resolve host \"x.com\"")
        assertFalse(NetRetry.isNetworkError(com.tuxundown.android.core.Errors.human(rawDns)))
        assertTrue(NetRetry.isNetworkError(rawDns))
    }

    // ---- v5.3 (audit round 38): idle-wait notification text ----

    @Test
    fun deferredWaitTextNamesTheActualPark() {
        // Portal-only: the network is UP, only the login is
        // missing — the text must not claim 等待网络恢复 (the
        // round-38 divergence: the drain's generic text used to
        // overwrite the portal park's own 等待登录 WiFi post).
        assertEquals(
            "等待 WiFi 登录" to "这个 WiFi 要先登录，登录后自动继续",
            NetRetry.deferredWaitText(hasNetParked = false, hasPortalParked = true),
        )
        assertEquals(
            "等待网络恢复" to "断网的任务已排队，网络一回来就自动继续",
            NetRetry.deferredWaitText(hasNetParked = true, hasPortalParked = false),
        )
    }

    @Test
    fun deferredWaitTextNetWinsWhenBothParkedAndNullWhenNone() {
        // With the network actually down a login cannot happen
        // anyway — the outage is the first blocker.
        assertEquals(
            "等待网络恢复" to "断网的任务已排队，网络一回来就自动继续",
            NetRetry.deferredWaitText(hasNetParked = true, hasPortalParked = true),
        )
        assertEquals(
            null,
            NetRetry.deferredWaitText(hasNetParked = false, hasPortalParked = false),
        )
    }
}

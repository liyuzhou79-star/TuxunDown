package com.tuxundown.android

import com.tuxundown.android.core.Format
import com.tuxundown.android.core.RetryPolicy
import com.tuxundown.android.core.TrafficGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4 policies: auto-retry backoff, traffic protection, formatting. */
class PolicyTest {

    @Test
    fun retryRespectsLimit() {
        assertFalse(RetryPolicy.shouldRetry(0, 0))
        assertTrue(RetryPolicy.shouldRetry(0, 3))
        assertTrue(RetryPolicy.shouldRetry(2, 3))
        assertFalse(RetryPolicy.shouldRetry(3, 3))
    }

    @Test
    fun backoffGrowsAndCaps() {
        assertEquals(10_000L, RetryPolicy.backoffMs(0))
        assertEquals(30_000L, RetryPolicy.backoffMs(1))
        assertEquals(90_000L, RetryPolicy.backoffMs(2))
        assertEquals(180_000L, RetryPolicy.backoffMs(3))
        assertEquals(180_000L, RetryPolicy.backoffMs(9))
    }

    @Test
    fun trafficGuardBlocksOnlyBigUnconfirmedMobile() {
        val gb = 1024L * 1024 * 1024
        assertTrue(TrafficGuard.shouldBlock(onWifi = false, totalSize = gb, thresholdMB = 200, confirmed = false))
        assertFalse(TrafficGuard.shouldBlock(onWifi = true, totalSize = gb, thresholdMB = 200, confirmed = false))
        assertFalse(TrafficGuard.shouldBlock(onWifi = false, totalSize = gb, thresholdMB = 200, confirmed = true))
        assertFalse(TrafficGuard.shouldBlock(onWifi = false, totalSize = gb, thresholdMB = 0, confirmed = false))
        assertFalse(TrafficGuard.shouldBlock(onWifi = false, totalSize = 10L * 1024 * 1024, thresholdMB = 200, confirmed = false))
        assertFalse(TrafficGuard.shouldBlock(onWifi = false, totalSize = -1, thresholdMB = 200, confirmed = false))
    }

    @Test
    fun durationFormats() {
        assertEquals("45 秒", Format.duration(45_000))
        assertEquals("3 分 12 秒", Format.duration(192_000))
        assertEquals("3 分", Format.duration(180_000))
        assertEquals("1 小时 2 分", Format.duration(3_720_000))
        assertEquals("1 小时", Format.duration(3_600_000))
    }
}

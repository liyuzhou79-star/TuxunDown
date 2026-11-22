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
    fun userActionFailuresAreNeverAutoRetried() {
        // Audit round 28: an expired SAF grant and a vanished
        // storage device can only be fixed by the user.
        assertTrue(RetryPolicy.needsUserAction("自定义文件夹不可写（授权可能已失效，请在设置里重选）"))
        assertTrue(RetryPolicy.needsUserAction("自定义文件夹打不开，请在设置里重选下载目录"))
        assertTrue(RetryPolicy.needsUserAction("存储设备好像出问题了（SD 卡可能被拔出），下载已停下。"))
        assertFalse(RetryPolicy.needsUserAction("服务器那边出错了（HTTP 500）：不是你的问题，过一会儿再试"))
        assertFalse(RetryPolicy.needsUserAction("找不到这个服务器：检查一下链接对不对，或者换个网络再试"))
        assertFalse(RetryPolicy.needsUserAction(""))
    }

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

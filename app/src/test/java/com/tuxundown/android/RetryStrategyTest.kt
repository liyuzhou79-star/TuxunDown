package com.tuxundown.android

import com.tuxundown.android.core.RetryStrategy
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.UserAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.7: batch strategy-retry decisions. */
class RetryStrategyTest {

    @Test
    fun onlyFailedTasksAreEligible() {
        assertTrue(RetryStrategy.eligible(TaskStatus.FAILED))
        for (s in listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.DONE)) {
            assertFalse(RetryStrategy.eligible(s))
        }
        assertEquals(
            2,
            RetryStrategy.countEligible(
                listOf(TaskStatus.FAILED, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.PAUSED)
            ),
        )
    }

    @Test
    fun uaNullLeavesHeadersUntouched() {
        val old = mapOf("User-Agent" to "X", "Cookie" to "session=1")
        assertEquals(old, RetryStrategy.headersWithUa(old, null))
    }

    @Test
    fun uaPresetIsWrittenAndOtherHeadersSurvive() {
        val ua = UserAgents.PRESETS.first { it.first.startsWith("电脑 Chrome") }.second
        val out = RetryStrategy.headersWithUa(
            mapOf("User-Agent" to "old", "Cookie" to "session=1", "Referer" to "https://a.b/"),
            ua,
        )
        assertEquals(ua, out["User-Agent"])
        assertEquals("session=1", out["Cookie"])
        assertEquals("https://a.b/", out["Referer"])
    }

    @Test
    fun uaDefaultPresetRemovesTheTaskHeader() {
        // PRESETS[0] is 默认 with "" — picking it means "stop
        // pinning a task UA", not "send an empty UA".
        val out = RetryStrategy.headersWithUa(
            mapOf("User-Agent" to "old", "Cookie" to "session=1"),
            UserAgents.PRESETS[0].second,
        )
        assertFalse(out.containsKey("User-Agent"))
        assertEquals("session=1", out["Cookie"])
    }

    @Test
    fun singleThreadForceIsMonotonic() {
        assertTrue(RetryStrategy.singleThreadAfter(old = false, force = true))
        assertTrue(RetryStrategy.singleThreadAfter(old = true, force = false))
        assertFalse(RetryStrategy.singleThreadAfter(old = false, force = false))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.LinkPrecheck
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPrecheckTest {

    private val now = 1_700_000_000_000L

    private fun queued(url: String = "https://a.com/f.zip") =
        TaskRecord(url = url, status = TaskStatus.QUEUED)

    @Test
    fun only404And410Convict() {
        assertTrue(LinkPrecheck.isDeadCode(404))
        assertTrue(LinkPrecheck.isDeadCode(410))
        // Everything else — refusals, server errors, throttling,
        // odd codes — proves nothing about the file existing.
        for (code in listOf(200, 206, 301, 400, 401, 403, 405, 416, 429, 500, 503)) {
            assertFalse("code $code must not convict", LinkPrecheck.isDeadCode(code))
        }
    }

    @Test
    fun candidatesAreQueuedDueAndFetchable() {
        assertTrue(LinkPrecheck.isCandidate(queued(), now))
        assertTrue(LinkPrecheck.isCandidate(queued("http://a.com/f.zip"), now))
        assertTrue(LinkPrecheck.isCandidate(queued("https://a.com/live.m3u8"), now))

        val running = queued().apply { status = TaskStatus.RUNNING }
        assertFalse(LinkPrecheck.isCandidate(running, now))
        val paused = queued().apply { status = TaskStatus.PAUSED }
        assertFalse(LinkPrecheck.isCandidate(paused, now))
        val parked = queued().apply { netDeferred = true }
        assertFalse(LinkPrecheck.isCandidate(parked, now))
        val future = queued().apply { scheduledAt = now + 60_000 }
        assertFalse(LinkPrecheck.isCandidate(future, now))
        // FTP has no HEAD — never a candidate.
        assertFalse(LinkPrecheck.isCandidate(queued("ftp://a.com/f.zip"), now))
    }

    @Test
    fun cacheTtlThrottlesRechecks() {
        val t = queued().apply { precheckAtMs = now - 1_000 }
        assertFalse(LinkPrecheck.isCandidate(t, now))
        val stale = queued().apply { precheckAtMs = now - LinkPrecheck.CACHE_TTL_MS - 1 }
        assertTrue(LinkPrecheck.isCandidate(stale, now))
    }

    @Test
    fun pickTakesTheQueueFrontOnly() {
        val tasks = (1..6).map { queued("https://a.com/f$it.zip") }
        val picked = LinkPrecheck.pickCandidates(tasks, now)
        assertEquals(LinkPrecheck.MAX_CANDIDATES, picked.size)
        assertEquals(tasks.take(3), picked)
        // A checked task drops out and the next in line moves up.
        tasks[0].precheckAtMs = now
        val repicked = LinkPrecheck.pickCandidates(tasks, now)
        assertEquals(listOf(tasks[1], tasks[2], tasks[3]), repicked)
    }
}

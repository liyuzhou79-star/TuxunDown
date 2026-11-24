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

    // ---- v8.6 (Step B): a cross-host answer cannot convict ----

    @Test
    fun deadVerdictNeedsSameHostChain() {
        // The chain never left the task's host: the answering
        // request carried the task's Cookie, so 404/410 still
        // mean what they always meant.
        assertTrue(LinkPrecheck.isDeadVerdict(404, chainLeftHost = false))
        assertTrue(LinkPrecheck.isDeadVerdict(410, chainLeftHost = false))
        // The chain crossed hosts: from that hop on the request
        // carried no Cookie (SessionScope), so even a 404/410
        // may be a login wall in a not-found mask — it proves
        // nothing and must not raise the flag.
        assertFalse(LinkPrecheck.isDeadVerdict(404, chainLeftHost = true))
        assertFalse(LinkPrecheck.isDeadVerdict(410, chainLeftHost = true))
        // Non-dead codes never convict on either kind of chain.
        assertFalse(LinkPrecheck.isDeadVerdict(403, chainLeftHost = false))
        assertFalse(LinkPrecheck.isDeadVerdict(200, chainLeftHost = true))
        assertFalse(LinkPrecheck.isDeadVerdict(301, chainLeftHost = true))
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

    // ---- v7.6 (Step B-2): empty-pass backoff + wake hook ----

    @Test
    fun backoffResetsToBaseWhenPassFindsCandidates() {
        assertEquals(
            LinkPrecheck.BASE_INTERVAL_MS,
            LinkPrecheck.intervalAfterPass(1, LinkPrecheck.MAX_INTERVAL_MS),
        )
        assertEquals(
            LinkPrecheck.BASE_INTERVAL_MS,
            LinkPrecheck.intervalAfterPass(3, LinkPrecheck.BASE_INTERVAL_MS),
        )
    }

    @Test
    fun backoffDoublesOnEmptyPassesUpToTheCap() {
        var interval = LinkPrecheck.BASE_INTERVAL_MS
        val seen = mutableListOf<Long>()
        repeat(6) {
            interval = LinkPrecheck.intervalAfterPass(0, interval)
            seen.add(interval)
        }
        assertEquals(
            listOf(60_000L, 120_000L, 240_000L, 480_000L, 480_000L, 480_000L),
            seen,
        )
        assertEquals(LinkPrecheck.MAX_INTERVAL_MS, seen.last())
        // A stale / zero previous value still starts from the
        // base's double, never below it.
        assertEquals(60_000L, LinkPrecheck.intervalAfterPass(0, 0L))
    }

    @Test
    fun awaitIntervalReturnsAtOnceWhenGenerationAlreadyMoved() {
        val gen = LinkPrecheck.queueGeneration()
        LinkPrecheck.noteQueueChanged()
        val start = System.currentTimeMillis()
        LinkPrecheck.awaitInterval(10_000L, gen)
        assertTrue(System.currentTimeMillis() - start < 5_000L)
    }

    @Test
    fun awaitIntervalWakesPromptlyOnEnqueueHook() {
        val gen = LinkPrecheck.queueGeneration()
        val waker = Thread {
            Thread.sleep(150)
            LinkPrecheck.noteQueueChanged()
        }
        val start = System.currentTimeMillis()
        waker.start()
        LinkPrecheck.awaitInterval(10_000L, gen)
        waker.join()
        assertTrue(System.currentTimeMillis() - start < 5_000L)
    }

    @Test
    fun awaitIntervalHonoursItsTimeoutWhenNothingHappens() {
        val gen = LinkPrecheck.queueGeneration()
        val start = System.currentTimeMillis()
        LinkPrecheck.awaitInterval(300L, gen)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("elapsed $elapsed must reach the timeout", elapsed >= 250L)
        assertTrue("elapsed $elapsed must not overshoot wildly", elapsed < 5_000L)
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

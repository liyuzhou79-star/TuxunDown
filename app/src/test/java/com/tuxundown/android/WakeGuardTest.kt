package com.tuxundown.android

import com.tuxundown.android.core.WakeLockCounter
import com.tuxundown.android.core.WakeLockHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.9 (audit round 34): WakeGuard's counting core, pinned
 * with a fake lock handle (see WakeLockCounter). The policy
 * input is injected as a parameter precisely so "switch off
 * means never acquire" is testable here without Android
 * prefs: production passes DownloadCenter.keepCpuAwake.
 */
class WakeGuardTest {

    /** Records every underlying acquire / release. */
    private class FakeHandle : WakeLockHandle {
        var acquires = 0
        var releases = 0
        var held = false

        override fun acquire() {
            acquires++
            held = true
        }

        override fun release() {
            releases++
            held = false
        }
    }

    @Test
    fun singleAcquireReleasePair() {
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        assertTrue(counter.acquireIf(enabled = true))
        assertEquals(1, fake.acquires)
        assertTrue(fake.held)
        assertEquals(1, counter.activeCount)
        counter.release()
        assertEquals(1, fake.releases)
        assertFalse(fake.held)
        assertEquals(0, counter.activeCount)
    }

    @Test
    fun twoOverlappingRunsShareOneUnderlyingHold() {
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        // Two engines running at once: one underlying acquire.
        assertTrue(counter.acquireIf(enabled = true))
        assertTrue(counter.acquireIf(enabled = true))
        assertEquals(1, fake.acquires)
        assertEquals(2, counter.activeCount)
        // The first run finishing must NOT release the lock…
        counter.release()
        assertEquals(0, fake.releases)
        assertTrue(fake.held)
        // …only the second (last) release does.
        counter.release()
        assertEquals(1, fake.releases)
        assertFalse(fake.held)
        assertEquals(0, counter.activeCount)
    }

    @Test
    fun releaseWithoutAcquireIsNoOp() {
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        counter.release()
        counter.release()
        assertEquals(0, fake.releases)
        assertEquals(0, counter.activeCount)
    }

    @Test
    fun countNeverGoesNegative() {
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        assertTrue(counter.acquireIf(enabled = true))
        counter.release()
        counter.release()
        counter.release()
        assertEquals(1, fake.releases)
        assertEquals(0, counter.activeCount)
        // And the counter still works after the stray releases.
        assertTrue(counter.acquireIf(enabled = true))
        assertEquals(2, fake.acquires)
        counter.release()
        assertEquals(0, counter.activeCount)
    }

    @Test
    fun switchOffNeverAcquires() {
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        assertFalse(counter.acquireIf(enabled = false))
        assertFalse(counter.acquireIf(enabled = false))
        assertEquals(0, fake.acquires)
        assertEquals(0, counter.activeCount)
        // A release after a refused acquire is still a no-op —
        // it must not eat a hold some other run took later.
        assertTrue(counter.acquireIf(enabled = true))
        counter.release()
        assertEquals(1, fake.acquires)
        assertEquals(1, fake.releases)
        assertEquals(0, counter.activeCount)
    }

    @Test
    fun concurrentRunsBalanceOut() {
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        val threads = (1..8).map {
            Thread {
                repeat(200) {
                    if (counter.acquireIf(enabled = true)) counter.release()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(0, counter.activeCount)
        assertFalse(fake.held)
        // Every 0 -> 1 transition acquired exactly once and
        // every 1 -> 0 transition released exactly once.
        assertEquals(fake.acquires, fake.releases)
        assertTrue(fake.acquires >= 1)
    }
}

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
    fun underlyingAcquireFailureLeavesNoPhantomHold() {
        // Audit round 46: if the platform acquire itself throws
        // (PowerManager hand-off failure), the count must stay
        // at zero — a phantom hold would keep the CPU awake
        // forever, because no caller believes it holds one and
        // nobody will ever release it. WakeGuard.acquireTransfer
        // converts the throw into a false return, which is only
        // sound if the counter is left exactly as it was.
        val flaky = object : WakeLockHandle {
            var fail = true
            var acquires = 0
            var releases = 0

            override fun acquire() {
                if (fail) throw RuntimeException("pm dead")
                acquires++
            }

            override fun release() {
                releases++
            }
        }
        val counter = WakeLockCounter(flaky)
        var threw = false
        try {
            counter.acquireIf(enabled = true)
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(0, counter.activeCount)
        assertEquals(0, flaky.acquires)
        // And the counter is fully usable afterwards.
        flaky.fail = false
        assertTrue(counter.acquireIf(enabled = true))
        assertEquals(1, counter.activeCount)
        counter.release()
        assertEquals(0, counter.activeCount)
        assertEquals(1, flaky.releases)
    }

    // ---------- v6.1 (A46-2): mid-run switch reconcile ----------

    @Test
    fun reconcileMatchingFlagTouchesNothing() {
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        // Not holding + switch off: stays not holding, no calls.
        assertFalse(counter.reconcile(enabled = false, holding = false))
        assertEquals(0, fake.acquires)
        assertEquals(0, fake.releases)
        // Holding + switch on: stays holding, no extra acquire.
        assertTrue(counter.acquireIf(enabled = true))
        assertTrue(counter.reconcile(enabled = true, holding = true))
        assertEquals(1, fake.acquires)
        assertEquals(0, fake.releases)
        assertEquals(1, counter.activeCount)
    }

    @Test
    fun reconcileOffReleasesTheHoldPromptly() {
        // The A46-2 bug, pinned fixed: a run that acquired with
        // the switch on must let go when the switch flips off
        // mid-run — not at the end of the transfer.
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        assertTrue(counter.acquireIf(enabled = true))
        assertFalse(counter.reconcile(enabled = false, holding = true))
        assertEquals(0, counter.activeCount)
        assertEquals(1, fake.releases)
        assertFalse(fake.held)
        // The run's finally then releases nothing (its flag is
        // false) — and a stray release would be a no-op anyway.
        counter.release()
        assertEquals(1, fake.releases)
    }

    @Test
    fun reconcileOnAcquiresMidRun() {
        // The other direction: a run that started with the
        // switch off picks the lock up when the user turns it
        // on mid-run.
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        assertFalse(counter.acquireIf(enabled = false))
        assertTrue(counter.reconcile(enabled = true, holding = false))
        assertEquals(1, counter.activeCount)
        assertEquals(1, fake.acquires)
        assertTrue(fake.held)
    }

    @Test
    fun reconcileOffKeepsOtherRunsHolds() {
        // Two runs hold; one reconciles off — the platform lock
        // must stay held for the other run, and drop only when
        // the second run also lets go. Each run reconciles only
        // its own flag, so no run can eat another's hold.
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        assertTrue(counter.acquireIf(enabled = true))
        assertTrue(counter.acquireIf(enabled = true))
        assertFalse(counter.reconcile(enabled = false, holding = true))
        assertEquals(1, counter.activeCount)
        assertTrue(fake.held)
        assertEquals(0, fake.releases)
        assertFalse(counter.reconcile(enabled = false, holding = true))
        assertEquals(0, counter.activeCount)
        assertEquals(1, fake.releases)
        assertFalse(fake.held)
    }

    @Test
    fun reconcileFlipFlopKeepsThePairing() {
        // Off -> on -> off within one run: every transition is
        // counted exactly once, and the run ends unheld with
        // balanced platform calls.
        val fake = FakeHandle()
        val counter = WakeLockCounter(fake)
        var holding = counter.acquireIf(enabled = true)
        holding = counter.reconcile(enabled = false, holding = holding)
        assertFalse(holding)
        holding = counter.reconcile(enabled = true, holding = holding)
        assertTrue(holding)
        holding = counter.reconcile(enabled = false, holding = holding)
        assertFalse(holding)
        assertEquals(0, counter.activeCount)
        assertEquals(2, fake.acquires)
        assertEquals(2, fake.releases)
        assertFalse(fake.held)
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

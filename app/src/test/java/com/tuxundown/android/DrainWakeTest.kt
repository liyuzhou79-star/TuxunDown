package com.tuxundown.android

import com.tuxundown.android.core.DrainWakeSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainWakeTest {

    @Test
    fun awaitWithoutSignalTimesOut() {
        val signal = DrainWakeSignal()
        val since = signal.snapshot()
        val start = System.nanoTime()
        val woke = signal.await(150, since)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertFalse(woke)
        // The wait really blocked for (about) the full timeout —
        // it is a blocking wait, not a shortened poll.
        assertTrue("elapsed ${elapsedMs}ms", elapsedMs >= 120)
    }

    @Test
    fun signalDuringWaitWakesPromptly() {
        val signal = DrainWakeSignal()
        val since = signal.snapshot()
        val waker = Thread {
            Thread.sleep(120)
            signal.signal()
        }.apply { isDaemon = true; start() }
        val start = System.nanoTime()
        val woke = signal.await(10_000, since)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        waker.join(2_000)
        assertTrue(woke)
        assertTrue("elapsed ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun signalBetweenSnapshotAndAwaitIsNotLost() {
        // The paradigm's core promise: the sender sets state,
        // then signals; the drain snapshot BEFORE evaluating.
        // A signal that lands after the snapshot but before
        // await() is even called must still cut the wait —
        // await checks the generation before it ever blocks.
        val signal = DrainWakeSignal()
        val since = signal.snapshot()
        signal.signal()
        val start = System.nanoTime()
        val woke = signal.await(10_000, since)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(woke)
        assertTrue("elapsed ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun snapshotTakenAfterSignalWaitsForTheNextOne() {
        // The other half of the paradigm: a snapshot taken
        // AFTER a signal is the new baseline (that signal's
        // state is what the following evaluation sees), so
        // awaiting on it blocks until a FURTHER signal.
        val signal = DrainWakeSignal()
        signal.signal()
        val since = signal.snapshot()
        assertFalse(signal.await(120, since))
    }

    @Test
    fun interruptPropagatesOutOfAwait() {
        // A real interrupt keeps its pre-v4.4 meaning: it must
        // surface as InterruptedException (the drain breaks its
        // loop on it), never be swallowed as a signal/timeout.
        val signal = DrainWakeSignal()
        val since = signal.snapshot()
        var interrupted = false
        val waiter = Thread {
            try {
                signal.await(10_000, since)
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(120)
        waiter.interrupt()
        waiter.join(2_000)
        assertTrue(interrupted)
    }

    @Test
    fun zeroTimeoutNeverBlocks() {
        val signal = DrainWakeSignal()
        val since = signal.snapshot()
        assertFalse(signal.await(0, since))
        signal.signal()
        assertTrue(signal.await(0, since))
    }

    @Test
    fun scheduledWaitClampsToTheStandingQuantum() {
        val now = 1_000_000L
        // Due in the past / due now: the 2 s floor.
        assertEquals(2_000L, DrainWakeSignal.scheduledWaitMs(now - 5_000, now))
        assertEquals(2_000L, DrainWakeSignal.scheduledWaitMs(now, now))
        // Inside the band: the exact remaining time.
        assertEquals(5_000L, DrainWakeSignal.scheduledWaitMs(now + 5_000, now))
        assertEquals(30_000L, DrainWakeSignal.scheduledWaitMs(now + 30_000, now))
        // Far future: the 30 s cap.
        assertEquals(30_000L, DrainWakeSignal.scheduledWaitMs(now + 3_600_000, now))
    }
}

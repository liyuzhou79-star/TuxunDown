package com.tuxundown.android

import com.tuxundown.android.core.FinalizeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.4 (finalize pipeline): the lane accounting and the two
 * verdicts the service leans on — the drain must not exit while
 * a finalize is outstanding, and completion side effects need a
 * live record.
 */
class FinalizeGateTest {

    @Test
    fun submitOncePerTaskUntilComplete() {
        val gate = FinalizeGate()
        assertTrue(gate.submit("a"))
        assertFalse(
            "a second hand-off of the same task must be refused",
            gate.submit("a"),
        )
        assertTrue(gate.isPending("a"))
        gate.complete("a")
        assertFalse(gate.isPending("a"))
        assertTrue("after completion the task can be handed off again", gate.submit("a"))
    }

    @Test
    fun pendingCountTracksTheLane() {
        val gate = FinalizeGate()
        assertEquals(0, gate.pendingCount())
        gate.submit("a")
        gate.submit("b")
        assertEquals(2, gate.pendingCount())
        gate.complete("a")
        assertEquals(1, gate.pendingCount())
        gate.complete("never-submitted")
        assertEquals("completing an unknown task is harmless", 1, gate.pendingCount())
        gate.complete("b")
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun pendingIsPerTask() {
        val gate = FinalizeGate()
        gate.submit("a")
        assertTrue(gate.isPending("a"))
        assertFalse(gate.isPending("b"))
    }

    @Test
    fun drainMayExitOnlyWhenNothingIsOutstanding() {
        assertTrue(
            "idle queue, nothing deferred, no finalize -> exit",
            FinalizeGate.drainMayExit(
                anyQueued = false, anyDeferred = false, pendingFinalize = 0,
            ),
        )
        assertFalse(
            "queued work keeps the drain alive",
            FinalizeGate.drainMayExit(
                anyQueued = true, anyDeferred = false, pendingFinalize = 0,
            ),
        )
        assertFalse(
            "deferred (parked) work keeps the drain alive",
            FinalizeGate.drainMayExit(
                anyQueued = false, anyDeferred = true, pendingFinalize = 0,
            ),
        )
        assertFalse(
            "a finalize still on the lane keeps the drain alive — " +
                "stopSelf here would kill an extract mid-file",
            FinalizeGate.drainMayExit(
                anyQueued = false, anyDeferred = false, pendingFinalize = 1,
            ),
        )
        assertFalse(
            FinalizeGate.drainMayExit(
                anyQueued = true, anyDeferred = true, pendingFinalize = 3,
            ),
        )
    }

    @Test
    fun completionSideEffectsNeedALiveRecord() {
        assertTrue(FinalizeGate.mayFireCompletionSideEffects(recordExists = true))
        assertFalse(
            "a delete that landed during finalize silences the " +
                "completion notification / stats / feedback / auto-action",
            FinalizeGate.mayFireCompletionSideEffects(recordExists = false),
        )
    }
}

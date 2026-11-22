package com.tuxundown.android

import com.tuxundown.android.core.FinalizeGate
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
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

    // ---- v4.6 (audit round 31): the drain's park verdict ----

    private fun parkedTask(status: TaskStatus, park: String): TaskRecord {
        val t = TaskRecord(
            url = "https://x/${System.nanoTime()}", status = status,
        )
        when (park) {
            "net" -> t.netDeferred = true
            "portal" -> t.portalDeferred = true
            "charge" -> t.chargeDeferred = true
        }
        return t
    }

    @Test
    fun anyParkedCountsEveryParkFlag() {
        for (park in listOf("net", "portal", "charge")) {
            assertTrue(
                "a QUEUED task parked on $park keeps the drain alive",
                FinalizeGate.anyParked(
                    listOf(parkedTask(TaskStatus.QUEUED, park)),
                ),
            )
        }
        assertFalse(
            FinalizeGate.anyParked(
                listOf(parkedTask(TaskStatus.QUEUED, "none")),
            ),
        )
        assertFalse(FinalizeGate.anyParked(emptyList()))
    }

    @Test
    fun anyParkedIgnoresParksOnNonQueuedTasks() {
        // A park flag left on a task the user paused (or one
        // that already finished) must not pin the service
        // alive: only a QUEUED park is waiting on the drain.
        for (status in listOf(TaskStatus.PAUSED, TaskStatus.DONE, TaskStatus.FAILED)) {
            assertFalse(
                FinalizeGate.anyParked(listOf(parkedTask(status, "charge"))),
            )
            assertFalse(
                FinalizeGate.anyParked(listOf(parkedTask(status, "net"))),
            )
        }
    }

    @Test
    fun chargeOnlyParkBlocksTheDrainExit() {
        // The audit-round-31 regression pin, composed exactly
        // as the drain composes it: a charge-only park (power
        // landed between the start loop's battery read and
        // the gate branch's re-read, so neither gate branch
        // held the service) must still veto the exit through
        // the deferred accounting.
        val tasks = listOf(parkedTask(TaskStatus.QUEUED, "charge"))
        assertFalse(
            FinalizeGate.drainMayExit(
                anyQueued = tasks.any { it.status == TaskStatus.QUEUED },
                anyDeferred = FinalizeGate.anyParked(tasks),
                pendingFinalize = 0,
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

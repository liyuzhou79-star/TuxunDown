package com.tuxundown.android

import com.tuxundown.android.core.FinalizeRescue
import com.tuxundown.android.core.IllegalTransitions
import com.tuxundown.android.core.StateEvent
import com.tuxundown.android.core.StateMachine
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v7.4 (audit round 59, Step B): the state-machine guard.
 * The full-grid test carries its OWN copy of the legal table —
 * a cell only passes when implementation and this independent
 * expectation agree, so an accidental table edit fails loudly.
 */
class StateMachineTest {

    @Before
    fun resetCounter() {
        IllegalTransitions.resetForTest()
    }

    /** The expected legal cells, written out independently. */
    private fun expectedLegal(): Map<Pair<TaskStatus, StateEvent>, TaskStatus> {
        val m = mutableMapOf<Pair<TaskStatus, StateEvent>, TaskStatus>()
        fun put(f: TaskStatus, e: StateEvent, t: TaskStatus) {
            m[f to e] = t
        }
        put(TaskStatus.QUEUED, StateEvent.START, TaskStatus.RUNNING)
        put(TaskStatus.QUEUED, StateEvent.PAUSE, TaskStatus.PAUSED)
        put(TaskStatus.RUNNING, StateEvent.PAUSE, TaskStatus.PAUSED)
        put(TaskStatus.PAUSED, StateEvent.RESUME, TaskStatus.QUEUED)
        put(TaskStatus.QUEUED, StateEvent.RESUME, TaskStatus.QUEUED)
        put(TaskStatus.FAILED, StateEvent.RETRY, TaskStatus.QUEUED)
        put(TaskStatus.DONE, StateEvent.RETRY, TaskStatus.QUEUED)
        put(TaskStatus.FAILED, StateEvent.NET_PARK, TaskStatus.QUEUED)
        put(TaskStatus.FAILED, StateEvent.PORTAL_PARK, TaskStatus.QUEUED)
        put(TaskStatus.QUEUED, StateEvent.CHARGE_PARK, TaskStatus.QUEUED)
        put(TaskStatus.FAILED, StateEvent.BACKOFF_REQUEUE, TaskStatus.QUEUED)
        put(TaskStatus.RUNNING, StateEvent.FINALIZE_SUCCESS, TaskStatus.DONE)
        put(TaskStatus.RUNNING, StateEvent.FINALIZE_FAILURE, TaskStatus.FAILED)
        put(TaskStatus.DONE, StateEvent.FINALIZE_FAILURE, TaskStatus.FAILED)
        put(TaskStatus.RUNNING, StateEvent.DEATH_RECOVERY, TaskStatus.PAUSED)
        put(TaskStatus.PAUSED, StateEvent.DEATH_RECOVERY, TaskStatus.QUEUED)
        put(TaskStatus.DONE, StateEvent.ARCHIVE, TaskStatus.DONE)
        for (s in TaskStatus.values()) {
            put(s, StateEvent.CANCEL, s)
            put(s, StateEvent.DELETE, s)
        }
        return m
    }

    @Test
    fun fullGridMatchesExpectedTable() {
        val expected = expectedLegal()
        var legalSeen = 0
        for (from in TaskStatus.values()) {
            for (event in StateEvent.values()) {
                val want = expected[from to event]
                val got = StateMachine.target(from, event)
                assertEquals("cell $from x $event", want, got)
                assertEquals("isLegal $from x $event", want != null, StateMachine.isLegal(from, event))
                if (want != null) legalSeen++
            }
        }
        assertEquals("legal cell count", expected.size, legalSeen)
        // 5 statuses x 14 events = 70 cells, all covered above.
        assertEquals(70, TaskStatus.values().size * StateEvent.values().size)
    }

    @Test
    fun headlineIllegalCellsAreRefused() {
        // A finished task must never re-enter the queue through
        // a resume, and a running task must never be re-queued.
        assertNull(StateMachine.target(TaskStatus.DONE, StateEvent.RESUME))
        assertNull(StateMachine.target(TaskStatus.RUNNING, StateEvent.RESUME))
        assertNull(StateMachine.target(TaskStatus.RUNNING, StateEvent.START))
        assertNull(StateMachine.target(TaskStatus.DONE, StateEvent.START))
        assertNull(StateMachine.target(TaskStatus.DONE, StateEvent.PAUSE))
        assertNull(StateMachine.target(TaskStatus.PAUSED, StateEvent.START))
        assertNull(StateMachine.target(TaskStatus.QUEUED, StateEvent.FINALIZE_SUCCESS))
    }

    @Test
    fun enqueueGateAcceptsOnlyQueuedPausedFailed() {
        assertTrue(StateMachine.canEnqueue(TaskStatus.QUEUED))
        assertTrue(StateMachine.canEnqueue(TaskStatus.PAUSED))
        assertTrue(StateMachine.canEnqueue(TaskStatus.FAILED))
        assertFalse("RUNNING via the funnel is refused", StateMachine.canEnqueue(TaskStatus.RUNNING))
        assertFalse("DONE via the funnel is refused", StateMachine.canEnqueue(TaskStatus.DONE))
    }

    @Test
    fun commitMovesLegalAndRefusesIllegal() {
        val t = TaskRecord(url = "https://example.com/f.bin", status = TaskStatus.QUEUED)
        assertTrue(StateMachine.commit(t, StateEvent.START, "test"))
        assertEquals(TaskStatus.RUNNING, t.status)
        assertEquals(0, IllegalTransitions.count)

        assertFalse(StateMachine.commit(t, StateEvent.RESUME, "test"))
        assertEquals("a refused commit changes nothing", TaskStatus.RUNNING, t.status)
        assertEquals(1, IllegalTransitions.count)
    }

    @Test
    fun counterRecordsOnlyTheTripleAndTaskId() {
        val t = TaskRecord(url = "https://example.com/secret-path", status = TaskStatus.DONE)
        assertFalse(StateMachine.commit(t, StateEvent.RESUME, "enqueue"))
        assertEquals(1, IllegalTransitions.count)
        val last = IllegalTransitions.last
        assertEquals(t.id, last?.taskId)
        assertEquals(TaskStatus.DONE, last?.from)
        assertEquals(StateEvent.RESUME, last?.event)
        assertEquals("enqueue", last?.source)

        // A legal commit does not touch the counter.
        val ok = TaskRecord(url = "https://example.com/g.bin", status = TaskStatus.PAUSED)
        assertTrue(StateMachine.commit(ok, StateEvent.RESUME, "enqueue"))
        assertEquals(1, IllegalTransitions.count)
    }

    @Test
    fun finalizeRescueNeedsEveryProof() {
        // The one rescuable shape: DONE, savedUri empty, target
        // persisted, .part provably there under a .part name.
        assertTrue(
            FinalizeRescue.needsRescue(
                TaskStatus.DONE, "", "content://dl/1", true, "f.bin.part",
            )
        )
        // Not DONE -> not this rescue's business.
        assertFalse(
            FinalizeRescue.needsRescue(
                TaskStatus.FAILED, "", "content://dl/1", true, "f.bin.part",
            )
        )
        // savedUri already filled -> finalize completed; no-op.
        assertFalse(
            FinalizeRescue.needsRescue(
                TaskStatus.DONE, "content://dl/2", "content://dl/1", true, "f.bin.part",
            )
        )
        // No target Uri -> nothing provable; no-op.
        assertFalse(
            FinalizeRescue.needsRescue(
                TaskStatus.DONE, "", "", true, "f.bin.part",
            )
        )
        // .part missing -> reconcile / ghost territory; no-op.
        assertFalse(
            FinalizeRescue.needsRescue(
                TaskStatus.DONE, "", "content://dl/1", false, null,
            )
        )
        // Entry exists but no longer wears the .part name ->
        // finalize actually landed; no-op.
        assertFalse(
            FinalizeRescue.needsRescue(
                TaskStatus.DONE, "", "content://dl/1", true, "f.bin",
            )
        )
        // Existence without a readable name is not proof enough.
        assertFalse(
            FinalizeRescue.needsRescue(
                TaskStatus.DONE, "", "content://dl/1", true, null,
            )
        )
    }
}

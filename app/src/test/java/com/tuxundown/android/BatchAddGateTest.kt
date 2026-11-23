package com.tuxundown.android

import com.tuxundown.android.core.BatchAddGate
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v7.7 (audit round 62, B-2): batch creation must not pay one
 * full-store write per record. TaskStore itself needs an
 * Android Context, so the flush DECISIONS are pinned here
 * through the pure [BatchAddGate] TaskStore acts on, driving a
 * stand-in store that mirrors add / batchAdds / endBatchAdds
 * branch for branch: in a batch an add only requests the
 * coalesced save; closing the outermost batch performs the
 * one full write (the serialized store text); outside a batch
 * every add writes in full at once.
 */
class BatchAddGateTest {

    private class FakeStore {
        val gate = BatchAddGate()
        val records = mutableListOf<TaskRecord>()
        var fullWrites = 0
        var coalescedSaves = 0
        var diskText = ""

        /** Same branch as TaskStore.add (head insertion incl.). */
        fun add(t: TaskRecord) {
            records.add(0, t)
            if (gate.inBatch()) coalescedSaves++ else flush()
        }

        fun flush() {
            fullWrites++
            diskText = TaskStore.serialize(records)
        }

        /** Same shape as TaskStore.batchAdds. */
        fun <T> batch(block: () -> T): T {
            gate.begin()
            try {
                return block()
            } finally {
                if (gate.end()) flush()
            }
        }
    }

    private fun rec(i: Int) = TaskRecord(url = "https://x.example.com/f$i")

    @Test
    fun singleAddFlushesImmediatelyEveryTime() {
        val s = FakeStore()
        s.add(rec(1))
        s.add(rec(2))
        assertEquals(2, s.fullWrites)
        assertEquals(0, s.coalescedSaves)
        val back = TaskStore.deserialize(s.diskText)
        assertEquals(2, back.size)
    }

    @Test
    fun batchOfFiftyFlushesOnceAtTheEndAndLandsEverything() {
        val s = FakeStore()
        s.batch {
            for (i in 0 until 50) {
                s.add(rec(i))
                // No full write may happen mid-batch.
                assertEquals(0, s.fullWrites)
            }
        }
        assertEquals(1, s.fullWrites)
        assertEquals(50, s.coalescedSaves)
        val back = TaskStore.deserialize(s.diskText)
        assertEquals(50, back.size)
        assertEquals(
            (0 until 50).map { "https://x.example.com/f$it" }.toSet(),
            back.map { it.url }.toSet(),
        )
        // Head-insertion order is the sequential-add order:
        // the last created record sits at the list head.
        assertEquals("https://x.example.com/f49", back.first().url)
    }

    @Test
    fun nestedBatchesFlushOnceAtTheOutermostEnd() {
        val s = FakeStore()
        s.batch {
            s.add(rec(1))
            s.batch {
                s.add(rec(2))
                assertEquals(0, s.fullWrites)
            }
            // The inner end must NOT flush.
            assertEquals(0, s.fullWrites)
            s.add(rec(3))
        }
        assertEquals(1, s.fullWrites)
        assertEquals(3, TaskStore.deserialize(s.diskText).size)
    }

    @Test
    fun batchFlushesOnceEvenWhenTheBlockThrows() {
        val s = FakeStore()
        try {
            s.batch {
                s.add(rec(1))
                throw IllegalStateException("boom")
            }
            fail("the block's exception must propagate")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(1, s.fullWrites)
        assertEquals(1, TaskStore.deserialize(s.diskText).size)
    }

    @Test
    fun endWithoutBeginFlushesNothingAndChangesNothing() {
        val gate = BatchAddGate()
        assertFalse(gate.end())
        assertFalse(gate.inBatch())
        gate.begin()
        assertTrue(gate.inBatch())
        assertTrue(gate.end())
        assertFalse(gate.inBatch())
        assertFalse(gate.end())
    }

    @Test
    fun addsAfterTheBatchFlushImmediatelyAgain() {
        val s = FakeStore()
        s.batch { s.add(rec(1)) }
        assertEquals(1, s.fullWrites)
        s.add(rec(2))
        assertEquals(2, s.fullWrites)
        assertEquals(2, TaskStore.deserialize(s.diskText).size)
    }
}

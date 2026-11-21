package com.tuxundown.android

import com.tuxundown.android.core.Recovery
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6: the interrupted-task verdict behind boot resume and the
 * START_STICKY recovery. The one distinction that matters: the
 * SYSTEM stopping a task mid-transfer (persisted RUNNING, loaded
 * as PAUSED + wasRunning) is recoverable; the USER pausing a
 * task is an order and must never be auto-resumed.
 */
class RecoveryTest {

    private fun task(
        status: TaskStatus,
        wasRunning: Boolean = false,
    ): TaskRecord {
        val t = TaskRecord(url = "https://a.com/f.bin", status = status)
        t.wasRunning = wasRunning
        return t
    }

    @Test
    fun interruptedIsExactlyTheSystemStoppedOnes() {
        val hit = Recovery.interrupted(
            listOf(
                task(TaskStatus.PAUSED, wasRunning = true), // killed mid-run
                task(TaskStatus.PAUSED, wasRunning = false), // user paused
                task(TaskStatus.QUEUED),
                task(TaskStatus.DONE),
                task(TaskStatus.FAILED),
            )
        )
        assertEquals(1, hit.size)
        assertTrue(hit[0].wasRunning)
    }

    @Test
    fun persistedRunningLoadsAsPausedWithTheMarker() {
        // A task saved mid-transfer carries "RUNNING" on disk.
        val running = task(TaskStatus.RUNNING)
        running.downloaded = 0 // serialize derives bytes from the bitset
        val text = TaskStore.serialize(listOf(running))
        val loaded = TaskStore.deserialize(text)
        assertEquals(1, loaded.size)
        assertEquals(TaskStatus.PAUSED, loaded[0].status)
        assertTrue(loaded[0].wasRunning)
    }

    @Test
    fun persistedPausedLoadsWithoutTheMarker() {
        val text = TaskStore.serialize(listOf(task(TaskStatus.PAUSED)))
        val loaded = TaskStore.deserialize(text)
        assertEquals(1, loaded.size)
        assertEquals(TaskStatus.PAUSED, loaded[0].status)
        assertFalse(loaded[0].wasRunning)
    }

    @Test
    fun markerIsTransientAndNeverSerialized() {
        // After the loading process saves again, the record on
        // disk is a plain PAUSED one — the marker cannot leak
        // into a later boot and resurrect a task twice.
        val t = task(TaskStatus.PAUSED, wasRunning = true)
        val text = TaskStore.serialize(listOf(t))
        val loaded = TaskStore.deserialize(text)
        assertFalse(loaded[0].wasRunning)
    }

    @Test
    fun anyQueuedSeesOnlyQueued() {
        assertFalse(Recovery.anyQueued(listOf(task(TaskStatus.PAUSED, wasRunning = true))))
        assertTrue(Recovery.anyQueued(listOf(task(TaskStatus.QUEUED))))
        assertFalse(Recovery.anyQueued(emptyList()))
    }
}

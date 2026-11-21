package com.tuxundown.android

import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4: the store's (de)serialization is shared by disk persistence
 *  and the settings import/export, so a round trip must keep the new
 *  fields (schedule, stats, speed history) intact. */
class TaskStoreJsonTest {

    @Test
    fun roundTripKeepsV14Fields() {
        val t = TaskRecord(
            url = "https://example.com/file.zip",
            fileName = "file.zip",
            totalSize = 10_000,
            status = TaskStatus.DONE,
            scheduledAt = 123_000,
            startedAt = 100_000,
            finishedAt = 160_000,
            elapsedMs = 55_000,
            retryCount = 3,
            autoRetriesUsed = 1,
            mobileConfirmed = true,
        )
        t.peakBps = 2048
        synchronized(t.speedHistory) {
            t.speedHistory.addAll(listOf(100L, 200L, 300L))
        }
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(t.url, r.url)
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals(123_000, r.scheduledAt)
        assertEquals(100_000, r.startedAt)
        assertEquals(160_000, r.finishedAt)
        assertEquals(55_000, r.elapsedMs)
        assertEquals(3, r.retryCount)
        assertEquals(1, r.autoRetriesUsed)
        assertTrue(r.mobileConfirmed)
        assertEquals(2048, r.peakBps)
        assertEquals(listOf(100L, 200L, 300L), r.speedHistory.toList())
    }

    @Test
    fun runningBecomesPausedAndCorruptTextIsEmpty() {
        val t = TaskRecord(url = "https://a.b/c.bin", status = TaskStatus.RUNNING)
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(TaskStatus.PAUSED, back[0].status)
        assertTrue(TaskStore.deserialize("not json at all").isEmpty())
        assertTrue(TaskStore.deserialize("").isEmpty())
    }

    @Test
    fun oldFormatWithoutV14FieldsStillLoads() {
        // A v1.3-era record: none of the v1.4 keys exist.
        val old = """[{"id":"x1","url":"https://a.b/c.bin","fileName":"c.bin","status":"DONE"}]"""
        val back = TaskStore.deserialize(old)
        assertEquals(1, back.size)
        assertEquals(0L, back[0].scheduledAt)
        assertEquals(0, back[0].retryCount)
    }
}

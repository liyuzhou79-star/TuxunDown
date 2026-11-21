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
    fun roundTripKeepsV16Fields() {
        val t = TaskRecord(
            url = "https://example.com/live/index.m3u8",
            fileName = "index.ts",
            status = TaskStatus.PAUSED,
            priority = 1,
            isHls = true,
            hlsTotalSegments = 4,
        )
        t.sources.addAll(listOf("https://mirror1.example.com/f.bin", "https://mirror2.example.com/f.bin"))
        t.hlsDone = booleanArrayOf(true, true, false, false)
        synchronized(t.hlsSizes) { t.hlsSizes.addAll(listOf(1000L, 2000L, -1L, -1L)) }
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(
            listOf("https://mirror1.example.com/f.bin", "https://mirror2.example.com/f.bin"),
            r.sources.toList(),
        )
        assertEquals(1, r.priority)
        assertTrue(r.isHls)
        assertEquals(4, r.hlsTotalSegments)
        assertEquals(listOf(true, true, false, false), r.hlsDone.toList())
        assertEquals(listOf(1000L, 2000L, -1L, -1L), r.hlsSizes.toList())
        // Downloaded bytes are rebuilt from the written segments.
        assertEquals(3000L, r.downloaded)
        // Progress is the merged-segment fraction for HLS tasks.
        assertEquals(0.5f, r.progress, 0.001f)
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
    fun exportStripsCookieButDiskFormatKeepsIt() {
        // v1.9: a backup file must never carry the session
        // credential; the on-disk serialization (default) keeps
        // it, because resuming needs it.
        val t = TaskRecord(url = "https://a.b/f.bin")
        t.headers["Cookie"] = "session=secret"
        t.headers["Referer"] = "https://a.b/page"
        val exported = TaskStore.deserialize(
            TaskStore.serialize(listOf(t), stripSensitiveHeaders = true)
        )
        assertEquals(1, exported.size)
        assertTrue(exported[0].headers.keys.none { it.equals("Cookie", true) })
        assertEquals("https://a.b/page", exported[0].headers["Referer"])
        val onDisk = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals("session=secret", onDisk[0].headers["Cookie"])
    }

    @Test
    fun hostileBackupRecordsAreContained() {
        // v1.9 audit: an imported backup is untrusted input.
        // - a record claiming an astronomical file with 1-byte
        //   chunks must not blow up memory (chunk count clamps);
        // - one structurally broken record must not discard the
        //   healthy records around it;
        // - absurd HLS segment counts clamp.
        val hostile = """
            [
              {"id":"ok1","url":"https://a.b/good.bin","status":"PAUSED"},
              {"id":"boom","url":"https://a.b/evil.bin","totalSize":9223372036854775806,"chunkSize":1,"chunkDone":"ffff","status":"PAUSED"},
              {"id":"broken"},
              {"id":"hls","url":"https://a.b/x.m3u8","isHls":true,"hlsTotalSegments":2147483647,"hlsDone":"ff","status":"PAUSED"}
            ]
        """.trimIndent()
        val back = TaskStore.deserialize(hostile)
        val byId = back.associateBy { it.id }
        assertTrue(byId.containsKey("ok1"))
        assertTrue("the broken record is skipped, the rest survive", byId.size >= 3)
        val hls = byId["hls"]
        assertTrue(hls != null)
        assertTrue(hls!!.hlsTotalSegments <= TaskStore.MAX_HLS_SEGMENTS)
        assertTrue(hls.hlsDone.size <= TaskStore.MAX_HLS_SEGMENTS)
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

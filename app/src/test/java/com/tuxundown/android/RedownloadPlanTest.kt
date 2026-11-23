package com.tuxundown.android

import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.RedownloadPlan
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.5: the 「再下一次」 field-copy table (see RedownloadPlan's
 * doc): what the brand-new record inherits from the finished
 * one, and — just as pinned — everything it must NOT inherit.
 */
class RedownloadPlanTest {

    /** A DONE source carrying a value in every field the plan
     *  could be tempted to copy. */
    private fun richSource(): TaskRecord {
        val t = TaskRecord(
            url = "https://example.com/files/big.zip",
            fileName = "big.zip",
            headers = mutableMapOf(
                "Cookie" to "session=abc",
                "Referer" to "https://example.com/page",
            ),
            totalSize = 123_456L,
            chunkSize = 65_536L,
            singleThreadOnly = true,
            threads = 16,
            status = TaskStatus.DONE,
            note = "系统备注",
            error = "旧错误",
            savedUri = "content://downloads/big.zip",
            savedName = "big.zip",
            targetUri = "content://downloads/big.zip.part",
            etag = "\"v1\"",
            lastModified = "Wed, 01 Jan 2025 00:00:00 GMT",
            startedAt = 111L,
            finishedAt = 222L,
            elapsedMs = 333L,
            retryCount = 4,
            sources = mutableListOf("https://mirror.example.com/big.zip"),
            priority = 1,
            expectedSha256 = "a".repeat(64),
            checksumState = Checksum.STATE_MATCHED,
            userNote = "用户的备注",
            scheduleDaily = true,
            archived = true,
        )
        t.downloaded = 123_456L
        t.chunkDone = booleanArrayOf(true, true)
        t.peakConns = 8
        return t
    }

    @Test
    fun notEligibleSources_getNull() {
        assertNull(RedownloadPlan.newRecord(richSource().apply { url = "" }))
        assertNull(RedownloadPlan.newRecord(richSource().apply { url = "  " }))
        for (status in TaskStatus.values()) {
            if (status == TaskStatus.DONE) continue
            assertNull(
                "$status",
                RedownloadPlan.newRecord(richSource().apply { this.status = status }),
            )
        }
    }

    @Test
    fun copiedFields_urlMirrorsHeadersFileName() {
        val src = richSource()
        val fresh = RedownloadPlan.newRecord(src)!!
        assertEquals("https://example.com/files/big.zip", fresh.url)
        assertEquals(listOf("https://mirror.example.com/big.zip"), fresh.sources)
        assertEquals(
            mapOf(
                "Cookie" to "session=abc",
                "Referer" to "https://example.com/page",
            ),
            fresh.headers,
        )
        assertEquals("big.zip", fresh.fileName)
        assertFalse(fresh.isHls)
    }

    @Test
    fun copiesAreIndependent_newContainers() {
        val src = richSource()
        val fresh = RedownloadPlan.newRecord(src)!!
        fresh.headers["Cookie"] = "changed"
        fresh.sources.add("https://other.example.com/big.zip")
        assertEquals("session=abc", src.headers["Cookie"])
        assertEquals(1, src.sources.size)
    }

    @Test
    fun freshRecord_startsFromZero() {
        val src = richSource()
        val fresh = RedownloadPlan.newRecord(src)!!
        assertNotEquals(src.id, fresh.id)
        assertEquals(TaskStatus.QUEUED, fresh.status)
        assertEquals(0L, fresh.downloaded)
        assertEquals(-1L, fresh.totalSize)
        assertEquals(0L, fresh.chunkSize)
        assertEquals(0, fresh.chunkDone.size)
        assertEquals("", fresh.savedUri)
        assertEquals("", fresh.savedName)
        assertEquals("", fresh.targetUri)
        assertEquals("", fresh.etag)
        assertEquals("", fresh.lastModified)
        assertEquals(0L, fresh.startedAt)
        assertEquals(0L, fresh.finishedAt)
        assertEquals(0L, fresh.elapsedMs)
        assertEquals(0, fresh.retryCount)
        assertEquals("", fresh.expectedSha256)
        assertEquals("", fresh.expectedMd5)
        assertEquals(Checksum.STATE_NONE, fresh.checksumState)
        assertEquals("", fresh.note)
        assertEquals("", fresh.error)
        assertEquals("", fresh.userNote)
        assertEquals(0, fresh.priority)
        assertFalse(fresh.singleThreadOnly)
        assertFalse(fresh.scheduleDaily)
        assertFalse(fresh.archived)
        assertEquals(0, fresh.peakConns)
        assertTrue(fresh.speedHistory.isEmpty())
        assertTrue(fresh.fullSpeedHistory.isEmpty())
        assertTrue(fresh.sourceBytes.isEmpty())
    }

    @Test
    fun sourceRecord_neverMutated() {
        val src = richSource()
        RedownloadPlan.newRecord(src)
        assertEquals(TaskStatus.DONE, src.status)
        assertEquals(123_456L, src.downloaded)
        assertEquals("content://downloads/big.zip", src.savedUri)
        assertEquals(2, src.chunkDone.size)
    }

    @Test
    fun hlsFlag_rederivedFromUrl() {
        val src = richSource().apply {
            url = "https://example.com/live/stream.m3u8?token=1"
            isHls = false // stale flag on the source must not matter
        }
        assertTrue(RedownloadPlan.newRecord(src)!!.isHls)
    }
}

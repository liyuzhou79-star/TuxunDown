package com.tuxundown.android

import com.tuxundown.android.core.Checksum
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
    fun roundTripKeepsV46ProfileFields() {
        val t = TaskRecord(
            url = "https://example.com/file.zip",
            fileName = "file.zip",
            totalSize = 10_000,
            status = TaskStatus.DONE,
        )
        t.peakConns = 24
        // Mirror keys are full URLs — query strings with '&',
        // '=' and non-ASCII must survive the JSON object intact.
        val mirror = "https://cdn.example.com/f.zip?token=a&b=c&name=文件"
        t.sources.add(mirror)
        synchronized(t.sourceBytes) {
            t.sourceBytes[t.url] = 7000
            t.sourceBytes[mirror] = 3000
        }
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(24, r.peakConns)
        synchronized(r.sourceBytes) {
            assertEquals(7000L, r.sourceBytes[t.url])
            assertEquals(3000L, r.sourceBytes[mirror])
        }
        // A pre-v4.6 record (no such keys) parses with the
        // "no data" defaults — the profile rows stay absent.
        val legacy = TaskStore.deserialize(
            """[{"id":"x","url":"https://e.com/f","status":"DONE"}]"""
        )
        assertEquals(1, legacy.size)
        assertEquals(0, legacy[0].peakConns)
        assertTrue(legacy[0].sourceBytes.isEmpty())
    }

    @Test
    fun roundTripKeepsJumpedAtAndOldRecordsDefaultZero() {
        // v4.8: the queue-jump stamp rides the shared serializer.
        val t = TaskRecord(url = "https://a.b/f.bin", status = TaskStatus.QUEUED)
        t.jumpedAt = 1_700_000_123_000L
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        assertEquals(1_700_000_123_000L, back[0].jumpedAt)
        // A pre-v4.8 record has no jumpedAt key: 0 = never jumped.
        val legacy = TaskStore.deserialize(
            """[{"id":"x","url":"https://e.com/f","status":"PAUSED"}]"""
        )
        assertEquals(1, legacy.size)
        assertEquals(0L, legacy[0].jumpedAt)
        // A corrupt negative stamp clamps to the same 0.
        val hostile = TaskStore.deserialize(
            """[{"id":"y","url":"https://e.com/f","status":"PAUSED","jumpedAt":-5}]"""
        )
        assertEquals(1, hostile.size)
        assertEquals(0L, hostile[0].jumpedAt)
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
            hlsFingerprint = "deadbeefcafe",
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
        // v2.1: the playlist fingerprint round-trips with the state.
        assertEquals("deadbeefcafe", r.hlsFingerprint)
        assertEquals(listOf(true, true, false, false), r.hlsDone.toList())
        assertEquals(listOf(1000L, 2000L, -1L, -1L), r.hlsSizes.toList())
        // Downloaded bytes are rebuilt from the written segments.
        assertEquals(3000L, r.downloaded)
        // Progress is the merged-segment fraction for HLS tasks.
        assertEquals(0.5f, r.progress, 0.001f)
    }

    @Test
    fun interruptedVerifyResetsToUncheckedNeverToAVerdict() {
        // v3.2 audit round 18 (VERIFYING x process-kill cell): a
        // task saved mid-verification was interrupted, not judged.
        // The load must reset the state to "not checked" — never
        // leave it at VERIFYING (a spinner forever) and never let
        // it become a verdict the bytes never earned. The DONE
        // itself is honest: verification only starts after the
        // last byte landed and the file was renamed.
        val t = TaskRecord(url = "https://a.b/c.bin", status = TaskStatus.DONE)
        t.checksumState = Checksum.STATE_VERIFYING
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(Checksum.STATE_NONE, back[0].checksumState)
        assertEquals(TaskStatus.DONE, back[0].status)
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
    fun roundTripKeepsUserNoteAndOldRecordsDefaultItEmpty() {
        // v2.8: the user's own note rides the shared serializer —
        // disk persistence AND backup export — and stays distinct
        // from the system's note line.
        val t = TaskRecord(url = "https://a.b/f.bin", note = "系统提示行")
        t.userNote = "给娃下的课件"
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals("给娃下的课件", back[0].userNote)
        assertEquals("系统提示行", back[0].note)
        // A pre-v2.8 record has no userNote key: it defaults to "".
        val old = """[{"id":"x9","url":"https://a.b/c.bin","status":"PAUSED"}]"""
        assertEquals("", TaskStore.deserialize(old)[0].userNote)
    }

    @Test
    fun roundTripKeepsEveryRemainingFieldV31Audit() {
        // v3.1 audit (round 17): ONE test pins the whole task-field
        // backup matrix — every serialized field must survive the
        // shared serializer (disk + backup export use the same one),
        // so a future field cannot silently drop out of backups.
        val t = TaskRecord(
            url = "https://example.com/big.iso",
            fileName = "big.iso",
            totalSize = 8192,
            chunkSize = 4096,
            chunkDone = booleanArrayOf(true, false),
            singleThreadOnly = true,
            threads = 7,
            status = TaskStatus.PAUSED,
            note = "系统行",
            error = "上次的错误",
            savedUri = "content://media/1",
            savedName = "big.iso",
            targetUri = "content://media/2",
            etag = "\"abc123\"",
            lastModified = "Wed, 01 Jan 2025 00:00:00 GMT",
            priority = -1, // v3.1: 低优先级 (legacy: 0 中 / 1 高=插队)
            expectedSha256 = "a".repeat(64),
            expectedMd5 = "b".repeat(32),
            checksumState = 2,
            scheduleDaily = true,
            taskLimitKBps = 1024,
            archived = true,
        )
        t.sources.addAll(listOf("https://mirror1.example.com/big.iso"))
        t.headers["Referer"] = "https://example.com/page"
        t.headers["Cookie"] = "session=secret"
        t.headers["Authorization"] = "Bearer token123"
        val r = TaskStore.deserialize(TaskStore.serialize(listOf(t)))[0]
        assertEquals(8192, r.totalSize)
        assertEquals(4096, r.chunkSize)
        assertEquals(listOf(true, false), r.chunkDone.toList())
        assertTrue(r.singleThreadOnly)
        assertEquals(7, r.threads)
        assertEquals("上次的错误", r.error)
        assertEquals("content://media/1", r.savedUri)
        assertEquals("big.iso", r.savedName)
        assertEquals("content://media/2", r.targetUri)
        assertEquals("\"abc123\"", r.etag)
        assertEquals("Wed, 01 Jan 2025 00:00:00 GMT", r.lastModified)
        assertEquals(-1, r.priority)
        assertEquals(listOf("https://mirror1.example.com/big.iso"), r.sources.toList())
        assertEquals("a".repeat(64), r.expectedSha256)
        assertEquals("b".repeat(32), r.expectedMd5)
        assertEquals(2, r.checksumState)
        assertTrue(r.scheduleDaily)
        assertEquals(1024, r.taskLimitKBps)
        assertTrue(r.archived)
        // On-disk format keeps credentials (resume needs them)…
        assertEquals("session=secret", r.headers["Cookie"])
        assertEquals("Bearer token123", r.headers["Authorization"])
        // …but the EXPORT form strips all three credential headers
        // while keeping the harmless ones.
        val exported = TaskStore.deserialize(
            TaskStore.serialize(listOf(t), stripSensitiveHeaders = true)
        )[0]
        assertEquals("https://example.com/page", exported.headers["Referer"])
        assertEquals(null, exported.headers["Cookie"])
        assertEquals(null, exported.headers["Authorization"])
    }

    @Test
    fun roundTripKeepsV42FineGridFields() {
        // Two-zone grid: fine [0,1k) [1k,2k) [2k,3k), then coarse
        // 2k pieces up to 10k — 7 chunks in all.
        val t = TaskRecord(
            url = "https://example.com/big.iso",
            fileName = "big.iso",
            totalSize = 10_000,
            chunkSize = 2_000,
            fineChunkSize = 1_000,
            fineChunkCount = 3,
            status = TaskStatus.PAUSED,
        )
        t.chunkDone = BooleanArray(7)
        t.chunkDone[2] = true // last fine piece: 1_000 bytes
        t.chunkDone[3] = true // first coarse piece: 2_000 bytes
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(2_000L, r.chunkSize)
        assertEquals(1_000L, r.fineChunkSize)
        assertEquals(3, r.fineChunkCount)
        assertEquals(7, r.chunkDone.size)
        // Downloaded recomputed against the two-zone grid, not
        // uniform math (uniform would say 2 x 2_000).
        assertEquals(3_000L, r.downloaded)
    }

    @Test
    fun recordWithoutFineFieldsParsesAsUniformGrid() {
        // A pre-v4.2 record: chunkSize/totalSize/chunkDone only.
        // Bitset "05" = chunks 0 and 2 done, 1_000 bytes each
        // under the uniform grid it was written with.
        val old = """[{"id":"x42","url":"https://a.b/c.bin","fileName":"c.bin",""" +
            """"totalSize":3000,"chunkSize":1000,"chunkDone":"05","status":"PAUSED"}]"""
        val back = TaskStore.deserialize(old)
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(0L, r.fineChunkSize)
        assertEquals(0, r.fineChunkCount)
        assertEquals(3, r.chunkDone.size)
        assertEquals(2_000L, r.downloaded)
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

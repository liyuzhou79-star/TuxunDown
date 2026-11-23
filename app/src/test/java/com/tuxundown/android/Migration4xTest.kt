package com.tuxundown.android

import com.tuxundown.android.core.Backup
import com.tuxundown.android.core.ChunkPlanner
import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.FailureKind
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round 33 (v4.8): 4.x persisted-field migration
 * consistency.
 *
 * MigrationTest pins the 1.x/2.x generations; this file pins
 * the 4.x additions and the parse door they all share
 * (TaskStore.deserialize — the on-disk store, the settings
 * import and every Backup shape funnel through it). The key
 * sets below follow the coordinator's per-generation diff:
 * v4.2 = the current serialize() key set MINUS the v4.5
 * failure pair (failureKind / failureHttpCode) and the v4.6
 * profile pair (peakConns / sourceBytes); v4.5 adds the
 * failure pair; v4.6 adds the profile pair. The format stayed
 * append-only, so a record from any of these generations must
 * load whole, with the fields its generation did not know
 * landing on their documented defaults.
 *
 * The hostile set pins the round-33 verdicts field by field:
 * fields argued safe are pinned at the behaviour that makes
 * them safe (ChunkPlanner's grid fallback / count clamp, the
 * valueOf guard, the sourceBytes caps), and the five small
 * parse-door fixes (peakConns upper clamp, headers optString,
 * retry counters >= 0, checksumState normalization,
 * speedHistory tail window) are pinned at their new behaviour.
 */
class Migration4xTest {

    // ---- v4.2 (+ fineChunkSize / fineChunkCount) ----
    //
    // A two-zone record: 300 MiB in 4 MiB coarse chunks with a
    // 32 x 1 MiB fine head (exactly what ChunkPlanner.gridFor
    // decides for 300 MiB / 4 MiB / 8 threads). 99 chunks in
    // all; the bitset marks chunk 0 (fine) and chunk 32 (the
    // first coarse chunk) done.

    private val v42TwoZone = """
        {"id":"v42-a","url":"https://files.example.com/big4.bin",
         "fileName":"big4.bin","totalSize":314572800,"chunkSize":4194304,
         "fineChunkSize":1048576,"fineChunkCount":32,
         "chunkDone":"01000000010000000000000000",
         "singleThreadOnly":false,"threads":8,
         "status":"PAUSED","note":"","error":"",
         "createdAt":1700100000000,"savedUri":"","savedName":"",
         "targetUri":"content://media/external/downloads/401",
         "etag":"\"v42\"","lastModified":"",
         "scheduledAt":0,"startedAt":1700100001000,"finishedAt":0,
         "elapsedMs":9000,"retryCount":1,"autoRetriesUsed":0,
         "mobileConfirmed":false,
         "sources":["https://m1.example.com/big4.bin"],
         "priority":0,"isHls":false,"hlsTotalSegments":0,
         "hlsFingerprint":"","expectedSha256":"","expectedMd5":"",
         "checksumState":0,"scheduleDaily":false,"taskLimitKBps":0,
         "archived":false,"userNote":"",
         "hlsDone":"","hlsSizesCsv":"","peakBps":777000,
         "speedHistory":[111,222],
         "headers":{"Referer":"https://files.example.com/"}}
    """.trimIndent()

    @Test
    fun v42TwoZoneGridSurvivesAndLaterFieldsDefault() {
        val back = TaskStore.deserialize("[$v42TwoZone]")
        assertEquals(1, back.size)
        val r = back[0]
        // The fine grid itself is load-bearing: the bitmap only
        // means the same byte ranges if the grid is rebuilt
        // exactly (99 chunks, not the 75 a uniform reading of
        // chunkSize alone would give).
        assertEquals(1048576L, r.fineChunkSize)
        assertEquals(32, r.fineChunkCount)
        assertEquals(99, r.chunkDone.size)
        assertTrue(r.chunkDone[0])
        assertTrue(r.chunkDone[32])
        assertEquals(1048576L + 4194304L, r.downloaded)
        assertEquals(777000L, r.peakBps)
        assertEquals(listOf(111L, 222L), r.speedHistory.toList())
        // Everything invented after v4.2 lands on its default:
        // no failure verdict (consumers fall back to the legacy
        // text matching for exactly this state) and no profile
        // data (the rows stay absent, never fabricated).
        assertEquals(FailureKind.UNKNOWN, r.failureKind)
        assertEquals(0, r.failureHttpCode)
        assertEquals(0, r.peakConns)
        assertTrue(r.sourceBytes.isEmpty())
    }

    // ---- v4.5 (+ failureKind / failureHttpCode) ----

    private val v45Failed = """
        {"id":"v45-a","url":"https://files.example.com/gone.bin",
         "fileName":"gone.bin","totalSize":2048,"chunkSize":0,
         "fineChunkSize":0,"fineChunkCount":0,"chunkDone":"",
         "singleThreadOnly":true,"threads":1,
         "status":"FAILED","note":"","error":"服务器拒绝了请求",
         "createdAt":1700200000000,"savedUri":"","savedName":"",
         "targetUri":"content://media/external/downloads/402",
         "etag":"","lastModified":"",
         "scheduledAt":0,"startedAt":1700200001000,
         "finishedAt":1700200002000,
         "elapsedMs":1000,"retryCount":4,"autoRetriesUsed":2,
         "mobileConfirmed":false,"sources":[],
         "priority":0,"isHls":false,"hlsTotalSegments":0,
         "hlsFingerprint":"","expectedSha256":"","expectedMd5":"",
         "checksumState":0,"failureKind":"HTTP_GONE",
         "failureHttpCode":410,"scheduleDaily":false,
         "taskLimitKBps":0,"archived":false,"userNote":"",
         "hlsDone":"","hlsSizesCsv":"","peakBps":0,
         "speedHistory":[],"headers":{}}
    """.trimIndent()

    @Test
    fun v45FailurePairSurvives() {
        val back = TaskStore.deserialize("[$v45Failed]")
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(TaskStatus.FAILED, r.status)
        // The structured verdict is the whole point of the
        // field: FailureAdvice renders from it after a restart,
        // when only serialized fields survive.
        assertEquals(FailureKind.HTTP_GONE, r.failureKind)
        assertEquals(410, r.failureHttpCode)
        // The profile pair is still a generation away.
        assertEquals(0, r.peakConns)
        assertTrue(r.sourceBytes.isEmpty())
    }

    // ---- v4.6 (+ peakConns / sourceBytes) ----

    private val v46Profiled = """
        {"id":"v46-a","url":"https://files.example.com/prof.bin",
         "fileName":"prof.bin","totalSize":10000,"chunkSize":10000,
         "fineChunkSize":0,"fineChunkCount":0,"chunkDone":"01",
         "singleThreadOnly":false,"threads":16,
         "status":"DONE","note":"","error":"",
         "createdAt":1700300000000,
         "savedUri":"content://media/external/downloads/403",
         "savedName":"prof.bin",
         "targetUri":"content://media/external/downloads/403",
         "etag":"\"v46\"","lastModified":"",
         "scheduledAt":0,"startedAt":1700300001000,
         "finishedAt":1700300009000,
         "elapsedMs":8000,"retryCount":0,"autoRetriesUsed":0,
         "mobileConfirmed":true,
         "sources":["https://m1.example.com/prof.bin"],
         "priority":1,"isHls":false,"hlsTotalSegments":0,
         "hlsFingerprint":"","expectedSha256":"","expectedMd5":"",
         "checksumState":2,"failureKind":"UNKNOWN",
         "failureHttpCode":0,"scheduleDaily":false,
         "taskLimitKBps":0,"archived":false,"userNote":"画像",
         "hlsDone":"","hlsSizesCsv":"","peakBps":1600000,
         "peakConns":16,
         "sourceBytes":{"https://files.example.com/prof.bin":6000,
           "https://m1.example.com/prof.bin":4000},
         "speedHistory":[900],"headers":{}}
    """.trimIndent()

    @Test
    fun v46ProfilePairSurvives() {
        val back = TaskStore.deserialize("[$v46Profiled]")
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(16, r.peakConns)
        // Per-source byte accounting is exact, key by key —
        // the profile's contribution rows are computed from
        // these numbers, so a silent drop or re-keying would
        // misreport which source carried the file.
        assertEquals(
            mapOf(
                "https://files.example.com/prof.bin" to 6000L,
                "https://m1.example.com/prof.bin" to 4000L,
            ),
            r.sourceBytes.toMap(),
        )
        assertEquals(2, r.checksumState)
        assertEquals("画像", r.userNote)
    }

    @Test
    fun mixed4xFileLoadsWhole() {
        val all = listOf(v42TwoZone, v45Failed, v46Profiled)
        val back = TaskStore.deserialize(all.joinToString(",", "[", "]"))
        assertEquals(all.size, back.size)
        assertEquals(
            setOf("v42-a", "v45-a", "v46-a"),
            back.map { it.id }.toSet(),
        )
    }

    // ---- full-field round trips ----

    /** A record with EVERY persisted field off its default. */
    private fun fullRecord(): TaskRecord {
        val t = TaskRecord(
            id = "full-1",
            url = "https://files.example.com/full.bin",
            fileName = "full.bin",
            headers = mutableMapOf(
                "Referer" to "https://files.example.com/page",
                "User-Agent" to "TuxunDown-test",
            ),
            totalSize = 110000,
            chunkSize = 10000,
            fineChunkSize = 0,
            fineChunkCount = 0,
            chunkDone = BooleanArray(11) { it % 2 == 0 },
            singleThreadOnly = true,
            threads = 7,
            status = TaskStatus.PAUSED,
            note = "note-x",
            error = "err-x",
            createdAt = 1700400000000,
            savedUri = "content://media/external/downloads/404",
            savedName = "full.bin",
            targetUri = "content://media/external/downloads/404",
            etag = "\"etag1\"",
            lastModified = "Wed, 01 Nov 2023 00:00:00 GMT",
            scheduledAt = 1700400001000,
            startedAt = 1700400002000,
            finishedAt = 1700400003000,
            elapsedMs = 42000,
            retryCount = 5,
            autoRetriesUsed = 2,
            mobileConfirmed = true,
            sources = mutableListOf(
                "https://m1.example.com/full.bin",
                "https://m2.example.com/full.bin",
            ),
            priority = -1,
            isHls = false,
            hlsTotalSegments = 0,
            hlsFingerprint = "",
            expectedSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            expectedMd5 = "098f6bcd4621d373cade4e832627b4f6",
            checksumState = 3,
            failureKind = FailureKind.HTTP_RATE_LIMITED,
            failureHttpCode = 429,
            taskLimitKBps = 256,
            archived = true,
            userNote = "给娃下的课件",
            scheduleDaily = true,
        )
        t.peakBps = 999000
        t.peakConns = 12
        synchronized(t.sourceBytes) {
            t.sourceBytes[t.url] = 60000
            t.sourceBytes["https://m1.example.com/full.bin"] = 50000
        }
        synchronized(t.speedHistory) {
            t.speedHistory.addAll(listOf(1000L, 2000L, 3000L))
        }
        return t
    }

    /** Field-by-field verdict for [fullRecord] after any round trip. */
    private fun assertFullFields(r: TaskRecord) {
        assertEquals("full-1", r.id)
        assertEquals("https://files.example.com/full.bin", r.url)
        assertEquals("full.bin", r.fileName)
        assertEquals(
            mapOf(
                "Referer" to "https://files.example.com/page",
                "User-Agent" to "TuxunDown-test",
            ),
            r.headers.toMap(),
        )
        assertEquals(110000L, r.totalSize)
        assertEquals(10000L, r.chunkSize)
        assertEquals(0L, r.fineChunkSize)
        assertEquals(0, r.fineChunkCount)
        assertEquals(
            listOf(true, false, true, false, true, false, true, false, true, false, true),
            r.chunkDone.toList(),
        )
        // Downloaded bytes are rebuilt from the bitmap: the six
        // even chunks of 10 000 bytes each.
        assertEquals(60000L, r.downloaded)
        assertTrue(r.singleThreadOnly)
        assertEquals(7, r.threads)
        assertEquals(TaskStatus.PAUSED, r.status)
        assertFalse(r.wasRunning)
        assertEquals("note-x", r.note)
        assertEquals("err-x", r.error)
        assertEquals(1700400000000L, r.createdAt)
        assertEquals("content://media/external/downloads/404", r.savedUri)
        assertEquals("full.bin", r.savedName)
        assertEquals("content://media/external/downloads/404", r.targetUri)
        assertEquals("\"etag1\"", r.etag)
        assertEquals("Wed, 01 Nov 2023 00:00:00 GMT", r.lastModified)
        assertEquals(1700400001000L, r.scheduledAt)
        assertEquals(1700400002000L, r.startedAt)
        assertEquals(1700400003000L, r.finishedAt)
        assertEquals(42000L, r.elapsedMs)
        assertEquals(5, r.retryCount)
        assertEquals(2, r.autoRetriesUsed)
        assertTrue(r.mobileConfirmed)
        assertEquals(
            listOf(
                "https://m1.example.com/full.bin",
                "https://m2.example.com/full.bin",
            ),
            r.sources.toList(),
        )
        assertEquals(-1, r.priority)
        assertFalse(r.isHls)
        assertEquals(0, r.hlsTotalSegments)
        assertEquals("", r.hlsFingerprint)
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            r.expectedSha256,
        )
        assertEquals("098f6bcd4621d373cade4e832627b4f6", r.expectedMd5)
        assertEquals(3, r.checksumState)
        assertEquals(FailureKind.HTTP_RATE_LIMITED, r.failureKind)
        assertEquals(429, r.failureHttpCode)
        assertTrue(r.scheduleDaily)
        assertEquals(256, r.taskLimitKBps)
        assertTrue(r.archived)
        assertEquals("给娃下的课件", r.userNote)
        assertEquals(999000L, r.peakBps)
        assertEquals(12, r.peakConns)
        assertEquals(
            mapOf(
                "https://files.example.com/full.bin" to 60000L,
                "https://m1.example.com/full.bin" to 50000L,
            ),
            r.sourceBytes.toMap(),
        )
        assertEquals(listOf(1000L, 2000L, 3000L), r.speedHistory.toList())
    }

    @Test
    fun fullRecordRoundTripsThroughSerialize() {
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(fullRecord())))
        assertEquals(1, back.size)
        assertFullFields(back[0])
    }

    @Test
    fun realGridForGridRoundTripsExactly() {
        // One EXACT round trip of a grid ChunkPlanner.gridFor
        // actually produces — the resume invariant is that the
        // rebuilt grid is the decided grid, bit for bit.
        val grid = ChunkPlanner.gridFor(314572800, 4194304, 8)
        assertTrue(grid.fineCount > 0)
        val count = ChunkPlanner.chunkCount(grid)
        val t = TaskRecord(
            url = "https://files.example.com/grid.bin",
            totalSize = grid.totalSize,
            chunkSize = grid.coarseSize,
            fineChunkSize = grid.fineSize,
            fineChunkCount = grid.fineCount,
            chunkDone = BooleanArray(count) { it == 0 || it == grid.fineCount },
            status = TaskStatus.PAUSED,
        )
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(
            grid,
            ChunkPlanner.gridOf(r.totalSize, r.chunkSize, r.fineChunkSize, r.fineChunkCount),
        )
        assertEquals(t.chunkDone.toList(), r.chunkDone.toList())
        assertEquals(grid.fineSize + grid.coarseSize, r.downloaded)
    }

    @Test
    fun fullRecordRoundTripsThroughBackupV1() {
        val tasksJson = TaskStore.serialize(listOf(fullRecord()))
        val parsed = Backup.parse(Backup.build(tasksJson, JSONObject(), 1700400000000))
        assertEquals(Backup.FORMAT_VERSION, parsed.formatVersion)
        val back = TaskStore.deserialize(parsed.tasksJson!!)
        assertEquals(1, back.size)
        assertFullFields(back[0])
    }

    @Test
    fun fullRecordRoundTripsThroughBackupV2Migration() {
        val tasksJson = TaskStore.serialize(listOf(fullRecord()))
        val text = Backup.buildMigration(
            tasksJson, JSONObject(), JSONObject().put("doneCount", 3), null, 1700400000000,
        )
        val parsed = Backup.parse(text)
        assertEquals(Backup.MIGRATION_VERSION, parsed.formatVersion)
        val back = TaskStore.deserialize(parsed.tasksJson!!)
        assertEquals(1, back.size)
        assertFullFields(back[0])
    }

    @Test
    fun fullRecordRoundTripsThroughBareV14Array() {
        // The v1.4 shape is the bare TaskStore array itself —
        // Backup.parse must hand it back untouched.
        val tasksJson = TaskStore.serialize(listOf(fullRecord()))
        val parsed = Backup.parse(tasksJson)
        assertEquals(0, parsed.formatVersion)
        val back = TaskStore.deserialize(parsed.tasksJson!!)
        assertEquals(1, back.size)
        assertFullFields(back[0])
    }

    // ---- settings seam (cardFields, v3.7 + v4.6) ----
    //
    // Like MigrationTest's settings pin: applySettingsJson
    // itself needs a Context (SharedPreferences), so it is NOT
    // JVM-testable — what is pinned here is the seam it reads:
    // the snapshot emits every cardShow* key including v4.6's
    // cardShowServerProfile, an older settings object simply
    // does not carry that key, and apply only writes keys that
    // are present (has() + optBoolean with the current value as
    // default, verified in DownloadCenter's source), so an old
    // backup cannot reset a newer card field and a wrong-type
    // value degrades to the current value.

    @Test
    fun cardSettingsSeamCarriesV46KeyAndOlderShapesLackIt() {
        val current = JSONObject()
            .put("cardShowAvgSpeed", true)
            .put("cardShowRetryCount", false)
            .put("cardShowCreatedAt", true)
            .put("cardShowHost", false)
            .put("cardShowChunks", true)
            .put("cardShowServerProfile", true)
        for (k in listOf(
            "cardShowAvgSpeed", "cardShowRetryCount", "cardShowCreatedAt",
            "cardShowHost", "cardShowChunks", "cardShowServerProfile",
        )) {
            assertTrue("missing $k", current.has(k))
        }
        assertTrue(current.getBoolean("cardShowServerProfile"))
        // A pre-v4.6 settings object: the profile key is absent,
        // so restoring it leaves the current toggle untouched.
        val older = JSONObject()
            .put("cardShowAvgSpeed", true)
            .put("cardShowChunks", false)
        assertFalse(older.has("cardShowServerProfile"))
    }

    // ---- hostile values ----

    private fun hostileRecord(id: String, extra: String): String {
        val tail = if (extra.isBlank()) "" else ",$extra"
        return """
            {"id":"$id","url":"https://files.example.com/$id.bin",
             "fileName":"$id.bin","status":"PAUSED","headers":{}$tail}
        """.trimIndent()
    }

    @Test
    fun hostileFineGridFieldsFallBackWithoutCrash() {
        // Negative fine fields: gridOf falls back to uniform.
        val neg = TaskStore.deserialize(
            "[" + hostileRecord(
                "h-neg",
                """"totalSize":2000,"chunkSize":500,
                   "fineChunkSize":-1024,"fineChunkCount":-7,
                   "chunkDone":"05"""",
            ) + "]",
        )
        assertEquals(1, neg.size)
        assertEquals(4, neg[0].chunkDone.size)
        assertEquals(1000L, neg[0].downloaded)

        // Int.MAX fine count whose zone would cover the whole
        // file: not a grid gridFor could have produced, so it
        // falls back to uniform instead of inventing ranges.
        val cover = TaskStore.deserialize(
            "[" + hostileRecord(
                "h-cover",
                """"totalSize":1000000,"chunkSize":262144,
                   "fineChunkSize":1024,"fineChunkCount":2147483647,
                   "chunkDone":"05"""",
            ) + "]",
        )
        assertEquals(1, cover.size)
        assertEquals(4, cover[0].chunkDone.size)
        assertEquals(524288L, cover[0].downloaded)

        // Tiny fine size x huge fine count, zone genuinely
        // smaller than the file: the grid stands, but the chunk
        // count clamps to MAX_CHUNKS — no giant allocation, no
        // crash, the record loads.
        val huge = TaskStore.deserialize(
            "[" + hostileRecord(
                "h-huge",
                """"totalSize":3000000000,"chunkSize":1000000,
                   "fineChunkSize":1,"fineChunkCount":2147483647,
                   "chunkDone":""""",
            ) + "]",
        )
        assertEquals(1, huge.size)
        assertEquals(ChunkPlanner.MAX_CHUNKS, huge[0].chunkDone.size)
        assertEquals(0L, huge[0].downloaded)
    }

    @Test
    fun failureKindGarbageBecomesUnknown() {
        val back = TaskStore.deserialize(
            "[" + hostileRecord(
                "h-kind",
                """"failureKind":"NOT_A_KIND","failureHttpCode":500""",
            ) + "," + hostileRecord(
                "h-kind2",
                """"failureKind":12345,"failureHttpCode":500""",
            ) + "]",
        )
        assertEquals(2, back.size)
        assertEquals(FailureKind.UNKNOWN, back[0].failureKind)
        assertEquals(FailureKind.UNKNOWN, back[1].failureKind)
    }

    @Test
    fun failureHttpCodeHostileValuesParseAsIs() {
        // The code is WRITE-ONLY today (engine writes, service
        // resets; grep finds no decision/display consumer), so
        // the parse door keeps it verbatim — pinned so a future
        // consumer inherits a known contract, not a surprise.
        val back = TaskStore.deserialize(
            "[" + hostileRecord("h-code1", """"failureHttpCode":-1""") +
                "," + hostileRecord("h-code2", """"failureHttpCode":2147483647""") +
                "," + hostileRecord("h-code3", """"failureHttpCode":99999""") + "]",
        )
        assertEquals(3, back.size)
        assertEquals(-1, back[0].failureHttpCode)
        assertEquals(Int.MAX_VALUE, back[1].failureHttpCode)
        assertEquals(99999, back[2].failureHttpCode)
    }

    @Test
    fun peakConnsClampedAtParse() {
        // Round-33 fix: the sampler's pool can never exceed
        // MAX_THREADS, so anything above it is corrupt and is
        // clamped instead of reaching the profile display.
        val back = TaskStore.deserialize(
            "[" + hostileRecord("h-peak1", """"peakConns":2147483647""") +
                "," + hostileRecord("h-peak2", """"peakConns":-5""") +
                "," + hostileRecord("h-peak3", """"peakConns":99999""") +
                "," + hostileRecord("h-peak4", """"peakConns":128""") + "]",
        )
        assertEquals(4, back.size)
        assertEquals(DownloadEngine.MAX_THREADS, back[0].peakConns)
        assertEquals(0, back[1].peakConns)
        assertEquals(DownloadEngine.MAX_THREADS, back[2].peakConns)
        assertEquals(DownloadEngine.MAX_THREADS, back[3].peakConns)
    }

    @Test
    fun peakConnsOldStoredValuesParseUnchanged() {
        // v7.7: the clamp widened 0..64 -> 0..128 with the
        // per-task top. Values stored under the old caliber
        // parse EXACTLY as before (a stored 64 stays 64, never
        // rewritten upward); a stored 100 — impossible under
        // the old top, clamped to 64 back then — is legal now
        // and parses verbatim; only past 128 clamps.
        val back = TaskStore.deserialize(
            "[" + hostileRecord("h-old1", """"peakConns":64""") +
                "," + hostileRecord("h-old2", """"peakConns":100""") +
                "," + hostileRecord("h-old3", """"peakConns":129""") + "]",
        )
        assertEquals(3, back.size)
        assertEquals(64, back[0].peakConns)
        assertEquals(100, back[1].peakConns)
        assertEquals(128, back[2].peakConns)
    }

    @Test
    fun sourceBytesHostileValuesDroppedNotFatal() {
        // Negative and zero values never become contribution
        // rows. A NON-numeric string reads as 0 via optLong and
        // drops the same way — but a NUMERIC string is coerced
        // by org.json's optLong to the number it spells, and is
        // kept as exactly that number. (The round-33 pre-audit
        // claimed "wrong types read 0 and drop"; that is only
        // true for non-numeric strings — verified against the
        // real parser in CI, and pinned here as the actual
        // contract: the coercion invents nothing, the value is
        // the one the file itself wrote.)
        val back = TaskStore.deserialize(
            "[" + hostileRecord(
                "h-src",
                """"sourceBytes":{"https://a.b/f":500,"https://a.b/neg":-5,
                   "https://a.b/str":"900","https://a.b/bad":"not-a-number",
                   "https://a.b/zero":0}""",
            ) + "]",
        )
        assertEquals(1, back.size)
        assertEquals(
            mapOf("https://a.b/f" to 500L, "https://a.b/str" to 900L),
            back[0].sourceBytes.toMap(),
        )

        // Key flood: at most the primary + MAX_SOURCES mirrors
        // can ever be real keys, so the parse caps there.
        val flood = (0 until 30).joinToString(",") { "\"https://h$it.b/f\":100" }
        val flooded = TaskStore.deserialize(
            "[" + hostileRecord("h-flood", "\"sourceBytes\":{$flood}") + "]",
        )
        assertEquals(1, flooded.size)
        assertTrue(flooded[0].sourceBytes.size <= TaskStore.MAX_SOURCES + 1)
        assertTrue(flooded[0].sourceBytes.values.all { it == 100L })
    }

    @Test
    fun headersNonStringValueKeepsWholeRecord() {
        // Round-33 fix: before it, ONE non-string header value
        // made getString throw and the per-record guard dropped
        // the WHOLE record (and only that record). Now the bad
        // entries are skipped and both records survive.
        val bad = """
            {"id":"h-hdr","url":"https://files.example.com/hdr.bin",
             "fileName":"hdr.bin","status":"PAUSED",
             "headers":{"Referer":"https://files.example.com/",
               "Cookie":"s=1","X-Num":123,"X-Obj":{"a":1},
               "X-Arr":[1,2],"":"blank-key-value"}}
        """.trimIndent()
        val good = """
            {"id":"h-hdr2","url":"https://files.example.com/hdr2.bin",
             "fileName":"hdr2.bin","status":"PAUSED","headers":{}}
        """.trimIndent()
        val back = TaskStore.deserialize("[$bad,$good]")
        assertEquals(2, back.size)
        val r = back.first { it.id == "h-hdr" }
        assertEquals(
            mapOf(
                "Referer" to "https://files.example.com/",
                "Cookie" to "s=1",
            ),
            r.headers.toMap(),
        )
    }

    @Test
    fun negativeRetryCountersClampToZero() {
        // Round-33 fix: a negative autoRetriesUsed would make
        // RetryPolicy.shouldRetry (used < limit) grant MORE
        // whole-task retries than configured.
        val back = TaskStore.deserialize(
            "[" + hostileRecord(
                "h-retry",
                """"retryCount":-3,"autoRetriesUsed":-2147483648""",
            ) + "]",
        )
        assertEquals(1, back.size)
        assertEquals(0, back[0].retryCount)
        assertEquals(0, back[0].autoRetriesUsed)
    }

    @Test
    fun checksumStateOutOfRangeNormalizesToNone() {
        // Round-33 fix: values outside 0..3 were inert (every
        // consumer judges by equality) but are normalized at
        // the parse door anyway; the VERIFYING -> NONE rule
        // from v1.7 is untouched, and real verdicts survive.
        val back = TaskStore.deserialize(
            "[" + hostileRecord("h-ck1", """"checksumState":99""") +
                "," + hostileRecord("h-ck2", """"checksumState":-1""") +
                "," + hostileRecord("h-ck3", """"checksumState":3""") +
                "," + hostileRecord("h-ck4", """"checksumState":1""") + "]",
        )
        assertEquals(4, back.size)
        assertEquals(0, back[0].checksumState)
        assertEquals(0, back[1].checksumState)
        assertEquals(3, back[2].checksumState)
        assertEquals(0, back[3].checksumState)
    }

    @Test
    fun speedHistoryKeepsOnlyLast90InOrder() {
        // Round-33 fix: the parser reads only the tail window
        // it keeps — 200 samples in, the last 90 survive, in
        // their original order.
        val hist = (0 until 200).joinToString(",")
        val back = TaskStore.deserialize(
            "[" + hostileRecord("h-hist", "\"speedHistory\":[$hist]") + "]",
        )
        assertEquals(1, back.size)
        assertEquals(90, back[0].speedHistory.size)
        assertEquals((110 until 200).map { it.toLong() }, back[0].speedHistory.toList())
    }

    @Test
    fun runningParksAsPausedWithWasRunningPlainPausedDoesNot() {
        // wasRunning is transient and DERIVED: only a record
        // whose persisted status was RUNNING earns it. Both
        // directions pinned — a plain user pause must never be
        // mistaken for a system stop (Recovery keys off this).
        val running = hostileRecord("h-run", """"status":"RUNNING"""")
            .replace(""""status":"PAUSED",""", "")
        val back = TaskStore.deserialize(
            "[" + running + "," + hostileRecord("h-paused", "") + "]",
        )
        assertEquals(2, back.size)
        val r = back.first { it.id == "h-run" }
        assertEquals(TaskStatus.PAUSED, r.status)
        assertTrue(r.wasRunning)
        val p = back.first { it.id == "h-paused" }
        assertEquals(TaskStatus.PAUSED, p.status)
        assertFalse(p.wasRunning)
    }

    @Test
    fun negativeScalarsLoadWithoutCrash() {
        // Argued-clear in round 33: consumers coerce or guard
        // (StartPlan clamps threads into its band, the grid is
        // re-decided when chunkSize <= 0, avgBps guards
        // elapsedMs > 0) — pinned as a no-crash load with the
        // derived figures staying honest.
        val back = TaskStore.deserialize(
            "[" + hostileRecord(
                "h-neg2",
                """"threads":-3,"totalSize":-100,"chunkSize":-1,
                   "elapsedMs":-50""",
            ) + "]",
        )
        assertEquals(1, back.size)
        assertEquals(0L, back[0].downloaded)
        assertEquals(0L, back[0].avgBps)
        assertEquals(0f, back[0].progress, 0.001f)
    }
}

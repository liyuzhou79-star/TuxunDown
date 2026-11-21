package com.tuxundown.android

import com.tuxundown.android.core.Backup
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round 11 (v2.5): upgrade-migration fixtures.
 *
 * Each fixture below is a tasks.json record written with
 * EXACTLY the key set that generation's serialize() emitted
 * — the sets were diffed against the historical code at the
 * per-version main heads (1.2 b45ebe44, 1.3 8d86e93a,
 * 1.4 d284a650, 1.6 4a074630, 1.7 bedbd7c8, 2.1 ebf0e661),
 * not reconstructed from memory. The format is append-only:
 * no key was ever renamed, retyped or dropped, and the
 * chunk-bitset encoding (hex, bit i = byte i/8 bit i%8) is
 * byte-identical since 1.2. These tests pin that contract:
 * an old user's file must load whole — no crash, no lost
 * task, and the load-bearing fields (chunk bitmap, target
 * Uri, hash expectations) must mean the same thing today.
 */
class MigrationTest {

    // ---- v1.2 (base key set; no etag/lastModified yet) ----

    private val v12Segmented = """
        {"id":"v12-a","url":"https://files.example.com/big.iso",
         "fileName":"big.iso","totalSize":1048576,"chunkSize":262144,
         "chunkDone":"05","singleThreadOnly":false,"threads":8,
         "status":"PAUSED","note":"","error":"",
         "createdAt":1700000000000,"savedUri":"","savedName":"",
         "targetUri":"content://media/external/downloads/101",
         "headers":{"Cookie":"session=old","Referer":"https://files.example.com/"}}
    """.trimIndent()

    private val v12Done = """
        {"id":"v12-b","url":"https://files.example.com/small.txt",
         "fileName":"small.txt","totalSize":2048,"chunkSize":0,
         "chunkDone":"","singleThreadOnly":true,"threads":1,
         "status":"DONE","note":"已保存到公共「下载」目录：small.txt","error":"",
         "createdAt":1700000001000,
         "savedUri":"content://media/external/downloads/102",
         "savedName":"small.txt",
         "targetUri":"content://media/external/downloads/102",
         "headers":{}}
    """.trimIndent()

    @Test
    fun v12RecordsLoadWithBitmapAndTargetIntact() {
        val back = TaskStore.deserialize("[$v12Segmented,$v12Done]")
        assertEquals(2, back.size)
        val a = back.first { it.id == "v12-a" }
        assertEquals(TaskStatus.PAUSED, a.status)
        assertEquals(8, a.threads)
        // Bits [done, -, done, -] over 4 x 256 KiB chunks: the
        // resume position must be rebuilt exactly (2 chunks).
        assertEquals(listOf(true, false, true, false), a.chunkDone.toList())
        assertEquals(524288L, a.downloaded)
        // The .part target and the session headers are the
        // load-bearing fields: losing either loses the resume.
        assertEquals("content://media/external/downloads/101", a.targetUri)
        assertEquals("session=old", a.headers["Cookie"])
        assertEquals("https://files.example.com/", a.headers["Referer"])
        // Everything invented after 1.2 lands on its default.
        assertEquals("", a.etag)
        assertEquals("", a.lastModified)
        assertEquals(0L, a.scheduledAt)
        assertTrue(a.sources.isEmpty())
        assertFalse(a.isHls)
        assertEquals(0, a.checksumState)
        assertEquals(0, a.taskLimitKBps)
        assertFalse(a.archived)
        assertTrue(a.speedHistory.isEmpty())
        assertEquals(0L, a.peakBps)

        val b = back.first { it.id == "v12-b" }
        assertEquals(TaskStatus.DONE, b.status)
        assertTrue(b.singleThreadOnly)
        assertEquals("content://media/external/downloads/102", b.savedUri)
        assertEquals("small.txt", b.savedName)
    }

    // ---- v1.3 (+ etag / lastModified) ----

    private val v13Running = """
        {"id":"v13-a","url":"https://cdn.example.com/fw.bin",
         "fileName":"fw.bin","totalSize":524288,"chunkSize":131072,
         "chunkDone":"03","singleThreadOnly":false,"threads":4,
         "status":"RUNNING","note":"","error":"",
         "createdAt":1700001000000,"savedUri":"","savedName":"",
         "targetUri":"content://media/external/downloads/103",
         "etag":"\"abc123\"","lastModified":"Wed, 01 Nov 2023 00:00:00 GMT",
         "headers":{}}
    """.trimIndent()

    @Test
    fun v13ValidatorsSurviveAndRunningBecomesPaused() {
        val back = TaskStore.deserialize("[$v13Running]")
        assertEquals(1, back.size)
        val r = back[0]
        // The resume validators must not be dropped or mangled:
        // they are what stops a changed file mixing with old bytes.
        assertEquals("\"abc123\"", r.etag)
        assertEquals("Wed, 01 Nov 2023 00:00:00 GMT", r.lastModified)
        assertEquals(TaskStatus.PAUSED, r.status)
        assertEquals(262144L, r.downloaded)
    }

    // ---- v1.4 (+ schedule / stats / speed history) ----

    private val v14Done = """
        {"id":"v14-a","url":"https://a.b/movie.mp4",
         "fileName":"movie.mp4","totalSize":110000,"chunkSize":10000,
         "chunkDone":"ff07","singleThreadOnly":false,"threads":16,
         "status":"DONE","note":"","error":"",
         "createdAt":1700002000000,"savedUri":"content://media/external/downloads/104",
         "savedName":"movie.mp4","targetUri":"content://media/external/downloads/104",
         "etag":"","lastModified":"",
         "scheduledAt":1700001900000,"startedAt":1700001950000,
         "finishedAt":1700002055000,"elapsedMs":55000,
         "retryCount":3,"autoRetriesUsed":1,"mobileConfirmed":true,
         "peakBps":2048,"speedHistory":[100,200,300],
         "headers":{}}
    """.trimIndent()

    @Test
    fun v14StatsAndHistorySurvive() {
        val back = TaskStore.deserialize("[$v14Done]")
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(1700001900000L, r.scheduledAt)
        assertEquals(1700001950000L, r.startedAt)
        assertEquals(1700002055000L, r.finishedAt)
        assertEquals(55000L, r.elapsedMs)
        assertEquals(3, r.retryCount)
        assertEquals(1, r.autoRetriesUsed)
        assertTrue(r.mobileConfirmed)
        assertEquals(2048L, r.peakBps)
        assertEquals(listOf(100L, 200L, 300L), r.speedHistory.toList())
        // All 11 chunks done -> full size rebuilt, and the
        // average speed derives from the carried elapsed time.
        assertEquals(110000L, r.downloaded)
        assertEquals(2000L, r.avgBps)
    }

    // ---- v1.6 (+ mirrors / HLS state) ----

    private val v16Hls = """
        {"id":"v16-a","url":"https://live.example.com/show/index.m3u8",
         "fileName":"index.ts","totalSize":-1,"chunkSize":0,
         "chunkDone":"","singleThreadOnly":false,"threads":4,
         "status":"PAUSED","note":"","error":"",
         "createdAt":1700003000000,"savedUri":"","savedName":"",
         "targetUri":"content://media/external/downloads/105",
         "etag":"","lastModified":"",
         "scheduledAt":0,"startedAt":1700003001000,"finishedAt":0,
         "elapsedMs":4000,"retryCount":0,"autoRetriesUsed":0,
         "mobileConfirmed":false,
         "sources":["https://m1.example.com/show/index.m3u8","https://m2.example.com/show/index.m3u8"],
         "priority":1,"isHls":true,"hlsTotalSegments":4,
         "hlsDone":"03","hlsSizesCsv":"1000,2000,-1,-1",
         "peakBps":0,"speedHistory":[],
         "headers":{}}
    """.trimIndent()

    @Test
    fun v16HlsPrefixAndMirrorsSurvive() {
        val back = TaskStore.deserialize("[$v16Hls]")
        assertEquals(1, back.size)
        val r = back[0]
        assertTrue(r.isHls)
        assertEquals(1, r.priority)
        assertEquals(
            listOf(
                "https://m1.example.com/show/index.m3u8",
                "https://m2.example.com/show/index.m3u8",
            ),
            r.sources.toList(),
        )
        assertEquals(listOf(true, true, false, false), r.hlsDone.toList())
        assertEquals(listOf(1000L, 2000L, -1L, -1L), r.hlsSizes.toList())
        // Downloaded bytes are rebuilt from the written prefix.
        assertEquals(3000L, r.downloaded)
        assertEquals(0.5f, r.progress, 0.001f)
        // Written before v2.1: no fingerprint on disk. Blank is
        // the documented "adopt, don't judge" state — it must
        // NOT be mistaken for a playlist change.
        assertEquals("", r.hlsFingerprint)
    }

    // ---- v1.7 (+ checksum expectations) ----

    private val v17Matched = """
        {"id":"v17-a","url":"https://a.b/fw.zip","fileName":"fw.zip",
         "totalSize":4096,"chunkSize":0,"chunkDone":"",
         "singleThreadOnly":true,"threads":1,"status":"DONE",
         "note":"","error":"","createdAt":1700004000000,
         "savedUri":"content://media/external/downloads/106",
         "savedName":"fw.zip","targetUri":"content://media/external/downloads/106",
         "etag":"","lastModified":"","scheduledAt":0,"startedAt":0,
         "finishedAt":1700004001000,"elapsedMs":900,"retryCount":0,
         "autoRetriesUsed":0,"mobileConfirmed":false,"sources":[],
         "priority":0,"isHls":false,"hlsTotalSegments":0,
         "expectedSha256":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
         "expectedMd5":"","checksumState":2,
         "hlsDone":"","hlsSizesCsv":"","peakBps":0,"speedHistory":[],
         "headers":{}}
    """.trimIndent()

    private val v17Verifying = v17Matched
        .replace("\"v17-a\"", "\"v17-b\"")
        .replace("checksumState\":2", "checksumState\":1")

    @Test
    fun v17HashExpectationsSurviveVerifyingResets() {
        val back = TaskStore.deserialize("[$v17Matched,$v17Verifying]")
        assertEquals(2, back.size)
        val a = back.first { it.id == "v17-a" }
        // The expected hash is load-bearing: dropping it would
        // silently un-verify a file the user asked to check.
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            a.expectedSha256,
        )
        assertEquals(2, a.checksumState) // matched verdict kept
        val b = back.first { it.id == "v17-b" }
        // A verify interrupted by the upgrade never survives as
        // "verifying" — it resets to none, as designed in v1.7.
        assertEquals(0, b.checksumState)
    }

    // ---- v2.1 (+ playlist fingerprint / per-task cap / daily) ----

    private val v21Capped = """
        {"id":"v21-a","url":"https://a.b/nightly.tar","fileName":"nightly.tar",
         "totalSize":-1,"chunkSize":0,"chunkDone":"",
         "singleThreadOnly":false,"threads":8,"status":"QUEUED",
         "note":"","error":"","createdAt":1700005000000,
         "savedUri":"","savedName":"",
         "targetUri":"content://media/external/downloads/107",
         "etag":"","lastModified":"","scheduledAt":1700009000000,
         "startedAt":0,"finishedAt":0,"elapsedMs":0,"retryCount":0,
         "autoRetriesUsed":0,"mobileConfirmed":false,"sources":[],
         "priority":0,"isHls":true,"hlsTotalSegments":12,
         "hlsFingerprint":"deadbeefcafe",
         "expectedSha256":"","expectedMd5":"","checksumState":0,
         "scheduleDaily":true,"taskLimitKBps":512,
         "hlsDone":"","hlsSizesCsv":"","peakBps":0,"speedHistory":[],
         "headers":{}}
    """.trimIndent()

    @Test
    fun v21FieldsSurvive() {
        val back = TaskStore.deserialize("[$v21Capped]")
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals("deadbeefcafe", r.hlsFingerprint)
        assertEquals(512, r.taskLimitKBps)
        assertTrue(r.scheduleDaily)
        assertEquals(TaskStatus.QUEUED, r.status)
        // Written before v2.4: archiving defaults off, the
        // record stays visible in the normal tabs.
        assertFalse(r.archived)
    }

    // ---- all generations in one file ----

    @Test
    fun mixedGenerationFileLoadsWhole() {
        val all = listOf(
            v12Segmented, v12Done, v13Running, v14Done,
            v16Hls, v17Matched, v21Capped,
        )
        val back = TaskStore.deserialize(all.joinToString(",", "[", "]"))
        assertEquals(all.size, back.size)
        assertEquals(
            setOf("v12-a", "v12-b", "v13-a", "v14-a", "v16-a", "v17-a", "v21-a"),
            back.map { it.id }.toSet(),
        )
    }

    // ---- settings side (the JVM-testable seam) ----
    //
    // On-device settings live in SharedPreferences; their
    // audit (round 11) is recorded in CHECKPOINT: every key
    // kept its name, type and default across all generations,
    // and since v1.5 every read goes through a runCatching
    // typed helper, so even a wrong-type value degrades to
    // the default instead of crashing the first frame. What
    // CAN be pinned here is the backup seam: an older
    // backup's settings object carries only the keys of its
    // generation, and applySettingsJson only writes keys
    // that are present (has() guards) — absent keys provably
    // keep their current values.

    @Test
    fun oldBackupSettingsCarryOnlyTheirGenerationsKeys() {
        // A v1.8-shaped backup: settings as of v1.8 (no proxy,
        // no auto-install/open, no archive keys yet).
        val text = """
            {"format":"tuxundown-backup","version":1,"exportedAt":1700000000000,
             "settings":{"wifiOnly":true,"speedLimitKBps":256,
               "maxConcurrent":2,"autoRetryCount":1,
               "trafficThresholdMB":0,"dualChannel":false,
               "userAgent":"","amoledBlack":false,
               "completeVibrate":true,"completeSound":false,
               "detailsExpanded":true,"sniffHistory":[]},
             "tasks":[]}
        """.trimIndent()
        val parsed = Backup.parse(text)
        val s = parsed.settings
        assertTrue(s != null)
        assertTrue(s!!.has("wifiOnly"))
        assertEquals(256, s.getInt("speedLimitKBps"))
        // Keys invented later are absent, so restoring this
        // backup cannot reset them.
        assertFalse(s.has("proxyHost"))
        assertFalse(s.has("autoInstallApk"))
        assertFalse(s.has("archiveAfterDays"))
    }
}

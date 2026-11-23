package com.tuxundown.android

import com.tuxundown.android.core.Format
import com.tuxundown.android.core.ServerProfile
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.6 (audit round 31 step C): the server-profile aggregation.
 * The pinned contract is ABSENCE: a row whose input was never
 * recorded does not exist, and the resume row speaks per
 * protocol — FTP's singleThreadOnly flag means REST resume,
 * not "no resume".
 */
class ServerProfileTest {

    private fun labels(rows: List<ServerProfile.Row>): List<String> =
        rows.map { it.label }

    private fun valueOf(rows: List<ServerProfile.Row>, label: String): String? =
        rows.firstOrNull { it.label == label }?.value

    // ---------- resume row: three honest stories ----------

    @Test
    fun ftpResumeSaysRestNotNoResume() {
        val t = TaskRecord(url = "ftp://files.example.com/f.bin", totalSize = 1000)
        t.singleThreadOnly = true // the FTP entry sets exactly this
        val text = ServerProfile.resumeText(t)
        assertTrue(text!!.contains("REST"))
        assertTrue(text.contains("支持"))
    }

    @Test
    fun httpForcedSingleStreamSaysNoUsableRange() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        t.singleThreadOnly = true // probe 200 / unusable 206
        val text = ServerProfile.resumeText(t)!!
        assertTrue(text.contains("Range"))
        assertTrue(text.contains("单条连接"))
    }

    @Test
    fun segmentedHttpSaysRangeSupported() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        t.chunkSize = 1024
        val text = ServerProfile.resumeText(t)!!
        assertTrue(text.startsWith("支持"))
        assertTrue(text.contains("Range"))
    }

    @Test
    fun hlsHasNoResumeRow() {
        val t = TaskRecord(
            url = "https://a.example.com/live.m3u8",
            totalSize = 1000, isHls = true, startedAt = 5,
        )
        assertNull(ServerProfile.resumeText(t))
        assertFalse(labels(ServerProfile.rows(t, 0)).contains("断点续传："))
    }

    @Test
    fun neverEngagedTaskClaimsNothing() {
        // Fresh QUEUED record: singleThreadOnly still holds its
        // default false, which must NOT be read as "Range works"
        // before any probe happened.
        val t = TaskRecord(url = "https://a.example.com/f.bin")
        assertNull(ServerProfile.resumeText(t))
        assertTrue(ServerProfile.rows(t, 0).isEmpty())
    }

    @Test
    fun startedAtAloneCountsAsEngaged() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", startedAt = 42)
        t.singleThreadOnly = true
        assertTrue(ServerProfile.resumeText(t)!!.contains("单条连接"))
    }

    // ---------- declared size / validators ----------

    @Test
    fun sizeAndValidatorRowsFollowTheirFields() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 2048)
        t.etag = "\"abc123\""
        t.lastModified = "Wed, 21 Nov 2026 07:28:00 GMT"
        val rows = ServerProfile.rows(t, 0)
        assertEquals(Format.size(2048), valueOf(rows, "服务器声明大小："))
        assertEquals("\"abc123\"", valueOf(rows, "ETag："))
        assertEquals("Wed, 21 Nov 2026 07:28:00 GMT", valueOf(rows, "最后修改："))
        // Unknown size and blank validators: rows absent.
        val bare = TaskRecord(url = "https://a.example.com/f.bin", startedAt = 1)
        val bareRows = ServerProfile.rows(bare, 0)
        assertFalse(labels(bareRows).contains("服务器声明大小："))
        assertFalse(labels(bareRows).contains("ETag："))
        assertFalse(labels(bareRows).contains("最后修改："))
    }

    // ---------- peak connections + derived per-connection ----------

    @Test
    fun peakRowsNeedRecordedPeak() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        val rows = ServerProfile.rows(t, 0)
        assertFalse(labels(rows).contains("实际峰值连接："))
        assertFalse(labels(rows).contains("峰值时平均每条连接："))
        t.peakConns = 8
        t.peakBps = 8L * 1024 * 1024
        val rows2 = ServerProfile.rows(t, 0)
        assertEquals("8 条", valueOf(rows2, "实际峰值连接："))
        assertEquals(
            Format.speed(1L * 1024 * 1024), // 8 MiB/s over 8 conns
            valueOf(rows2, "峰值时平均每条连接："),
        )
    }

    @Test
    fun singleConnectionPeakShowsOneConnAndFullPeakPerConn() {
        // v8.4 (audit round 69): the single-connection paths
        // (HTTP single-stream, FTP single) now record
        // peakConns = 1 — the display consequence pinned here:
        // both peak rows appear, and the per-connection figure
        // IS the whole recorded peak speed (peakBps / 1).
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        t.peakConns = 1
        t.peakBps = 3L * 1024 * 1024
        val rows = ServerProfile.rows(t, 0)
        assertEquals("1 条", valueOf(rows, "实际峰值连接："))
        assertEquals(
            Format.speed(3L * 1024 * 1024),
            valueOf(rows, "峰值时平均每条连接："),
        )
        assertEquals(t.peakBps, ServerProfile.peakPerConnectionBps(t.peakBps, t.peakConns))
    }

    @Test
    fun derivedSpeedAbsentWhenEitherInputMissing() {
        // peakConns = 0 must not divide (and must not invent).
        assertNull(ServerProfile.peakPerConnectionBps(1_000_000, 0))
        assertNull(ServerProfile.peakPerConnectionBps(0, 8))
        assertNull(ServerProfile.peakPerConnectionBps(0, 0))
        assertEquals(250_000L, ServerProfile.peakPerConnectionBps(1_000_000, 4))
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        t.peakConns = 8 // peak speed never recorded
        assertFalse(labels(ServerProfile.rows(t, 0)).contains("峰值时平均每条连接："))
    }

    // ---------- rate-limit ceiling ----------

    @Test
    fun capRowOnlyWhenACeilingIsRemembered() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        assertFalse(labels(ServerProfile.rows(t, 0)).contains("已知的限流上限："))
        assertFalse(labels(ServerProfile.rows(t, -3)).contains("已知的限流上限："))
        val v = valueOf(ServerProfile.rows(t, 8), "已知的限流上限：")!!
        assertTrue(v.contains("8 条连接"))
    }

    // ---------- per-source contribution ----------

    private fun multiSourceTask(): TaskRecord {
        val t = TaskRecord(url = "https://main.example.com/f.bin", totalSize = 10_000)
        // Two mirrors on the SAME host (different paths, signed
        // queries) — the grouping tests below rely on it.
        t.sources.addAll(
            listOf(
                "https://cdn.example.com/a/f.bin?token=secret1",
                "https://cdn.example.com/b/f.bin?token=secret2",
            )
        )
        return t
    }

    @Test
    fun sharesAbsentForSingleSourceOrNoBytes() {
        val single = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        synchronized(single.sourceBytes) { single.sourceBytes[single.url] = 500 }
        assertTrue(ServerProfile.sourceShares(single).isEmpty())
        val multi = multiSourceTask()
        assertTrue(ServerProfile.sourceShares(multi).isEmpty())
        assertFalse(labels(ServerProfile.rows(multi, 0)).any { it.startsWith("来源贡献") })
    }

    @Test
    fun sharesGroupByHostAndNeverShowFullMirrorUrls() {
        val t = multiSourceTask()
        synchronized(t.sourceBytes) {
            t.sourceBytes[t.url] = 6000
            t.sourceBytes["https://cdn.example.com/a/f.bin?token=secret1"] = 3000
            t.sourceBytes["https://cdn.example.com/b/f.bin?token=secret2"] = 1000
        }
        val shares = ServerProfile.sourceShares(t)
        // Both mirrors share one host: ONE grouped row of 4000.
        assertEquals(2, shares.size)
        assertEquals("main.example.com", shares[0].first)
        assertEquals(6000L, shares[0].second)
        assertEquals("cdn.example.com", shares[1].first)
        assertEquals(4000L, shares[1].second)
        // The card text carries hosts only — no token, no query.
        val rows = ServerProfile.rows(t, 0)
        val contrib = rows.filter { it.label.startsWith("来源贡献") }
        assertEquals(2, contrib.size)
        assertTrue(contrib.none { it.label.contains("secret") || it.label.contains("token") })
        assertTrue(contrib.none { it.value.contains("secret") })
    }

    @Test
    fun sharePercentsAreRoundedSharesOfRecordedBytes() {
        val t = multiSourceTask()
        synchronized(t.sourceBytes) {
            t.sourceBytes[t.url] = 1
            t.sourceBytes["https://cdn.example.com/a/f.bin?token=secret1"] = 1
            t.sourceBytes["https://cdn.example.com/b/f.bin?token=secret2"] = 1
        }
        val shares = ServerProfile.sourceShares(t)
        // After host grouping: main 1 byte (33.3%), cdn 2 bytes
        // (66.7%) — rounded to 33 + 67 = 100 here, but the
        // contract allows ±1 (independent rounding per host);
        // the byte counts beside the percents stay exact.
        assertEquals(3L, shares.sumOf { it.second })
        val sum = shares.sumOf { it.third }
        assertTrue("percent sum $sum", sum in 99..101)
        for ((_, bytes, pct) in shares) {
            val exact = bytes * 100.0 / 3
            assertTrue(Math.abs(pct - exact) <= 1.0)
        }
    }

    @Test
    fun zeroByteSourcesAreAbsent() {
        val t = multiSourceTask()
        synchronized(t.sourceBytes) {
            t.sourceBytes[t.url] = 0
            t.sourceBytes["https://cdn.example.com/a/f.bin?token=secret1"] = 250
        }
        val shares = ServerProfile.sourceShares(t)
        assertEquals(1, shares.size)
        assertEquals(250L, shares[0].second)
        assertEquals(100, shares[0].third)
    }

    // ---------- tail-race record (v7.8 Step C) ----------

    @Test
    fun tailRaceRowAbsentWhenNoRaceTriggered() {
        // Default-off switch: a task that never raced shows no
        // row at all (the profile's absence rule), even if the
        // other counters somehow hold values.
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        t.duplicateBytes = 4096
        t.tailRaceLoserRemainingBytes = 2048
        assertNull(ServerProfile.tailRaceText(t))
        assertFalse(labels(ServerProfile.rows(t, 0)).contains("尾巴双下："))
    }

    @Test
    fun tailRaceTextReportsCountAndDuplicateBytes() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        t.tailRaceCount = 2
        t.duplicateBytes = 3L * 1024
        // No race reached a winner yet: the loser-remaining
        // clause stays absent rather than claiming 0 saved.
        assertEquals(
            "触发 2 次，重复下载 ${Format.size(3L * 1024)}",
            ServerProfile.tailRaceText(t),
        )
        val rows = ServerProfile.rows(t, 0)
        assertEquals(ServerProfile.tailRaceText(t), valueOf(rows, "尾巴双下："))
        // The race row is the LAST row (appended after shares).
        assertEquals("尾巴双下：", labels(rows).last())
    }

    @Test
    fun tailRaceTextAddsLoserRemainingOnlyWhenRecorded() {
        val t = TaskRecord(url = "https://a.example.com/f.bin", totalSize = 1000)
        t.tailRaceCount = 1
        t.duplicateBytes = 0 // winner led the whole way: cost can be 0, still shown
        t.tailRaceLoserRemainingBytes = 96L * 1024
        val text = ServerProfile.tailRaceText(t)!!
        assertTrue(text.contains("触发 1 次"))
        assertTrue(text.contains("重复下载 ${Format.size(0)}"))
        assertTrue(text.contains("赢家下完时输家还剩 ${Format.size(96L * 1024)} 没下"))
        // Facts only: no seconds / time-saving claim anywhere.
        assertFalse(text.contains("秒"))
        assertFalse(text.contains("省"))
    }

    // ---------- row order is stable ----------

    @Test
    fun rowOrderIsTheDocumentedOne() {
        val t = TaskRecord(
            url = "https://a.example.com/f.bin",
            totalSize = 4096, status = TaskStatus.DONE,
        )
        t.etag = "e1"
        t.peakConns = 4
        t.peakBps = 4096
        assertEquals(
            listOf("断点续传：", "服务器声明大小：", "ETag：", "实际峰值连接：", "峰值时平均每条连接：", "已知的限流上限："),
            labels(ServerProfile.rows(t, 4)),
        )
    }
}

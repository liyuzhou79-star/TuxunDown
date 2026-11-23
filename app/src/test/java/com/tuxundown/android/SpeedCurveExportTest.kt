package com.tuxundown.android

import com.tuxundown.android.core.ExportStamp
import com.tuxundown.android.core.SpeedCurveExport
import com.tuxundown.android.core.TaskRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.5: the speed-curve CSV export. The formatter is pure, so
 * everything about the file's honesty is pinned here:
 * cumulative bytes are RECONSTRUCTED by integrating the
 * 1-second bps samples and the header comments must SAY so;
 * an empty curve is not exportable; nothing from the task's
 * link or headers may leak into the text.
 */
class SpeedCurveExportTest {

    /** Data rows only (comments and the column header removed). */
    private fun dataRows(csv: String): List<String> =
        csv.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.drop(1)

    @Test
    fun cumulativeIsTheSampleIntegral() {
        // Hand-computed: samples 1000, 2000, 0, 500 at 1 s each.
        val csv = SpeedCurveExport.buildCsv("movie.mp4", listOf(1000L, 2000L, 0L, 500L))!!
        assertEquals(
            listOf(
                "0,1000,1000",
                "1,3000,2000",
                "2,3000,0",
                "3,3500,500",
            ),
            dataRows(csv),
        )
    }

    @Test
    fun headerDeclaresTheReconstruction() {
        val csv = SpeedCurveExport.buildCsv("movie.mp4", listOf(7L, 8L))!!
        val comments = csv.lines().filter { it.startsWith("#") }
        assertTrue(comments.isNotEmpty())
        // The reconstruction disclaimer, the 1-second interval,
        // the file name and the sample count / duration all ride
        // in the comment block.
        assertTrue(comments.any { it.contains("累计字节不是单独记录的") && it.contains("还原") })
        assertTrue(comments.any { it.contains("每 1 秒一个") })
        assertTrue(comments.any { it.contains("movie.mp4") })
        assertTrue(comments.any { it.contains("共 2 个") })
        assertTrue(comments.any { it.contains("共 2 秒") })
        // Column header is exactly the promised shape.
        assertTrue(csv.lines().contains("elapsed_sec,cumulative_bytes,speed_bps"))
    }

    @Test
    fun headerCarriesTheGenerationTime() {
        // v6.4 step B: the nowMs entry stamps the header with
        // ExportStamp's caliber (local time + explicit zone
        // offset). It is a '#' comment line, so the CSV's data
        // shape and the honesty comments are untouched.
        val nowMs = 1795417445000L
        val csv = SpeedCurveExport.buildCsv("movie.mp4", listOf(7L, 8L), nowMs)!!
        val comments = csv.lines().filter { it.startsWith("#") }
        assertTrue(comments.any { it == "# 生成时间：${ExportStamp.format(nowMs)}" })
        assertTrue(
            comments.any {
                it.removePrefix("# 生成时间：").matches(
                    Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \(UTC[+-]\d{2}:\d{2}\)"""),
                )
            },
        )
        // The reconstruction disclaimer still rides along.
        assertTrue(comments.any { it.contains("累计字节不是单独记录的") && it.contains("还原") })
        assertEquals(listOf("0,7,7", "1,15,8"), dataRows(csv))
        // The task overload stamps the same way.
        val task = TaskRecord(url = "https://a.b/movie.mp4", fileName = "movie.mp4")
        synchronized(task.speedHistory) { task.speedHistory.add(9L) }
        val taskCsv = SpeedCurveExport.buildCsv(task, nowMs)!!
        assertTrue(taskCsv.lines().any { it == "# 生成时间：${ExportStamp.format(nowMs)}" })
        // An empty curve stays unexportable on the new entry too.
        assertNull(SpeedCurveExport.buildCsv("movie.mp4", emptyList(), nowMs))
    }

    @Test
    fun emptyCurveIsNotExportable() {
        assertNull(SpeedCurveExport.buildCsv("movie.mp4", emptyList()))
        val task = TaskRecord(url = "https://a.b/movie.mp4", fileName = "movie.mp4")
        assertNull(SpeedCurveExport.buildCsv(task))
    }

    @Test
    fun singleSampleExportsOneRow() {
        val csv = SpeedCurveExport.buildCsv("a.bin", listOf(42L))!!
        assertEquals(listOf("0,42,42"), dataRows(csv))
    }

    @Test
    fun zeroBpsSecondsStillEmitRows() {
        val csv = SpeedCurveExport.buildCsv("a.bin", listOf(0L, 0L, 10L))!!
        assertEquals(
            listOf("0,0,0", "1,0,0", "2,10,10"),
            dataRows(csv),
        )
    }

    @Test
    fun hugeSamplesSaturateInsteadOfOverflowing() {
        val big = Long.MAX_VALUE / 2
        val csv = SpeedCurveExport.buildCsv("a.bin", listOf(big, big, big, big))!!
        val rows = dataRows(csv).map { it.split(",").map(String::toLong) }
        // Cumulative never wraps negative; it saturates at MAX.
        assertTrue(rows.all { it[1] >= 0 })
        assertEquals(Long.MAX_VALUE, rows.last()[1])
        assertEquals(big, rows.last()[2])
    }

    @Test
    fun taskLinkAndHeadersNeverLeakIntoTheCsv() {
        val task = TaskRecord(
            url = "https://example.com/file.bin?token=SECRET123&sig=ABC",
            fileName = "file.bin",
            headers = mutableMapOf(
                "Cookie" to "session=TOPSECRET",
                "Referer" to "https://example.com/private-page",
            ),
        )
        synchronized(task.speedHistory) { task.speedHistory.addAll(listOf(100L, 200L)) }
        val csv = SpeedCurveExport.buildCsv(task)!!
        assertFalse(csv.contains("SECRET123"))
        assertFalse(csv.contains("TOPSECRET"))
        assertFalse(csv.contains("example.com"))
        assertFalse(csv.contains("Cookie"))
        assertFalse(csv.contains("token"))
        // And the curve itself is still exported correctly.
        assertEquals(listOf("0,100,100", "1,300,200"), dataRows(csv))
    }

    @Test
    fun fileNameInCommentCannotBreakLineStructure() {
        val csv = SpeedCurveExport.buildCsv("evil\n9,9,9.mp4", listOf(5L))!!
        // Every line is a comment, the column header, or a
        // well-formed data row — the injected newline in the
        // name cannot forge a row.
        for (line in csv.lines()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            assertTrue(
                line == "elapsed_sec,cumulative_bytes,speed_bps" ||
                    line.matches(Regex("""\d+,\d+,\d+""")),
            )
        }
        assertEquals(listOf("0,5,5"), dataRows(csv))
    }

    // ---- v8.2: full-run series branch ----

    /** Data rows of a full-run CSV (two columns, no cumulative). */
    private fun fullDataRows(csv: String): List<String> =
        csv.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.drop(1)

    @Test
    fun fullRunBranchExportsRecordedPointsVerbatim() {
        // The window samples deliberately differ: if the old
        // branch ran, the rows (and a cumulative column) would
        // show them instead of the full-run points.
        val csv = SpeedCurveExport.buildCsv(
            "movie.mp4", listOf(9L, 9L, 9L),
            listOf(1000L, 2000L, 0L, 500L), 5, 1795417445000L,
        )!!
        // Speeds are the recorded points themselves, the axis
        // advances by the real 5 s interval, nothing integrated.
        assertEquals(
            listOf("0,1000", "5,2000", "10,0", "15,500"),
            fullDataRows(csv),
        )
        assertTrue(csv.lines().contains("elapsed_sec,speed_bps"))
        assertFalse(csv.contains("cumulative_bytes"))
        val comments = csv.lines().filter { it.startsWith("#") }
        assertTrue(comments.any { it.contains("共 4 个") && it.contains("每 5 秒一个") })
        assertTrue(comments.any { it.contains("全程采样") })
        assertTrue(comments.any { it.contains("共 20 秒") })
        assertTrue(comments.any { it.contains("实际记录下来的采样点本身") && it.contains("没有做积分还原") })
        // Interval still at the base: no decimation has happened.
        assertTrue(comments.any { it.contains("暂未发生抽稀合并") })
        assertFalse(comments.any { it.contains("已经过抽稀合并") })
    }

    @Test
    fun fullRunBranchDisclosesDecimationWhenIntervalGrew() {
        val csv = SpeedCurveExport.buildCsv(
            "movie.mp4", emptyList(),
            listOf(300L, 700L), 10, 1795417445000L,
        )!!
        assertEquals(listOf("0,300", "10,700"), fullDataRows(csv))
        val comments = csv.lines().filter { it.startsWith("#") }
        assertTrue(comments.any { it.contains("每 10 秒一个") })
        assertTrue(comments.any { it.contains("共 20 秒") })
        assertTrue(
            comments.any {
                it.contains("已经过抽稀合并") && it.contains("取平均") &&
                    it.contains("平均值") && it.contains("10 秒")
            },
        )
        assertFalse(comments.any { it.contains("暂未发生抽稀合并") })
    }

    @Test
    fun fullRunThresholdIsExactlyTwoPoints() {
        val nowMs = 1795417445000L
        // Exactly at the chart threshold: full-run branch, even
        // with no window samples at all (never null, never the
        // old "no curve" outcome).
        val atThreshold = SpeedCurveExport.buildCsv(
            "a.bin", emptyList(), listOf(11L, 22L), 5, nowMs,
        )!!
        assertEquals(listOf("0,11", "5,22"), fullDataRows(atThreshold))
        // One point is below the threshold: the window export
        // runs unchanged.
        val below = SpeedCurveExport.buildCsv(
            "a.bin", listOf(7L, 8L), listOf(11L), 5, nowMs,
        )!!
        assertEquals(
            SpeedCurveExport.buildCsv("a.bin", listOf(7L, 8L), nowMs),
            below,
        )
    }

    @Test
    fun noFullRunSamplesKeepsTheOldOutputByteForByte() {
        val nowMs = 1795417445000L
        val viaChooser = SpeedCurveExport.buildCsv(
            "movie.mp4", listOf(1000L, 2000L, 0L, 500L),
            emptyList(), 5, nowMs,
        )
        assertEquals(
            SpeedCurveExport.buildCsv("movie.mp4", listOf(1000L, 2000L, 0L, 500L), nowMs),
            viaChooser,
        )
        // Same through the task entry: an old task (no full-run
        // fields ever written) exports its window curve as-is.
        val task = TaskRecord(url = "https://a.b/movie.mp4", fileName = "movie.mp4")
        synchronized(task.speedHistory) { task.speedHistory.addAll(listOf(3L, 4L)) }
        assertEquals(
            SpeedCurveExport.buildCsv("movie.mp4", listOf(3L, 4L), nowMs),
            SpeedCurveExport.buildCsv(task, nowMs),
        )
        // And with neither series there is still nothing to export.
        val empty = TaskRecord(url = "https://a.b/x.bin", fileName = "x.bin")
        assertNull(SpeedCurveExport.buildCsv(empty, nowMs))
    }

    @Test
    fun fullRunBranchSanitisesTheIntervalAndClampsNegatives() {
        // 15 is not 5 x 2^k: the header and the axis must fall
        // back to the base interval, never print the bad value.
        val csv = SpeedCurveExport.buildCsv(
            "a.bin", emptyList(), listOf(-5L, 42L), 15, 1795417445000L,
        )!!
        assertEquals(listOf("0,0", "5,42"), fullDataRows(csv))
        assertTrue(csv.lines().any { it.startsWith("#") && it.contains("每 5 秒一个") })
        assertFalse(csv.contains("15 秒"))
    }

    @Test
    fun fullRunBranchNeverLeaksTaskSecretsEither() {
        val task = TaskRecord(
            url = "https://example.com/file.bin?token=SECRET123&sig=ABC",
            fileName = "file.bin",
            headers = mutableMapOf(
                "Cookie" to "session=TOPSECRET",
                "Referer" to "https://example.com/private-page",
            ),
        )
        synchronized(task.fullSpeedHistory) { task.fullSpeedHistory.addAll(listOf(100L, 200L)) }
        task.fullSpeedIntervalSec = 5
        val csv = SpeedCurveExport.buildCsv(task, 1795417445000L)!!
        assertFalse(csv.contains("SECRET123"))
        assertFalse(csv.contains("TOPSECRET"))
        assertFalse(csv.contains("example.com"))
        assertFalse(csv.contains("Cookie"))
        assertFalse(csv.contains("token"))
        assertEquals(listOf("0,100", "5,200"), fullDataRows(csv))
    }

    @Test
    fun exportFileNameShapeAndSafety() {
        assertEquals("movie.mp4-speed.csv", SpeedCurveExport.exportFileName("movie.mp4"))
        // Path separators never survive into the export name.
        assertEquals("evil.zip-speed.csv", SpeedCurveExport.exportFileName("../../evil.zip"))
        // A blank name falls back like download names do.
        assertEquals("download.bin-speed.csv", SpeedCurveExport.exportFileName(""))
    }
}

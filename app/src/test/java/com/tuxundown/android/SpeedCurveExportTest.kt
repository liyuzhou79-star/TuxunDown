package com.tuxundown.android

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

    @Test
    fun exportFileNameShapeAndSafety() {
        assertEquals("movie.mp4-speed.csv", SpeedCurveExport.exportFileName("movie.mp4"))
        // Path separators never survive into the export name.
        assertEquals("evil.zip-speed.csv", SpeedCurveExport.exportFileName("../../evil.zip"))
        // A blank name falls back like download names do.
        assertEquals("download.bin-speed.csv", SpeedCurveExport.exportFileName(""))
    }
}

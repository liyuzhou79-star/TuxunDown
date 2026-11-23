package com.tuxundown.android

import com.tuxundown.android.core.FullSpeedHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v8.1: the full-run curve's pure logic — accumulation,
 *  the 720-point cap, decimation at the boundary, and the
 *  interval/span caliber the detail page prints. */
class FullSpeedHistoryTest {

    @Test
    fun appendBelowCapKeepsIntervalAndOrder() {
        var pts: List<Long> = emptyList()
        var iv = FullSpeedHistory.BASE_INTERVAL_SEC
        for (v in listOf(100L, 200L, 300L)) {
            val r = FullSpeedHistory.append(pts, iv, v)
            pts = r.first
            iv = r.second
        }
        assertEquals(listOf(100L, 200L, 300L), pts)
        assertEquals(5, iv)
        // A negative sample is impossible from the sampler;
        // it clamps to 0 instead of poisoning the curve.
        assertEquals(0L, FullSpeedHistory.append(pts, iv, -7).first.last())
    }

    @Test
    fun exactly720PointsDoNotDecimateYet() {
        var pts: List<Long> = emptyList()
        var iv = FullSpeedHistory.BASE_INTERVAL_SEC
        repeat(FullSpeedHistory.MAX_POINTS) {
            val r = FullSpeedHistory.append(pts, iv, 1000L)
            pts = r.first
            iv = r.second
        }
        assertEquals(FullSpeedHistory.MAX_POINTS, pts.size)
        assertEquals(5, iv)
        assertTrue(pts.all { it == 1000L })
        // 720 points x 5 s = exactly one hour of span.
        assertEquals(3600L, FullSpeedHistory.spanSeconds(pts.size, iv))
    }

    @Test
    fun point721DecimatesHalvesCountAndDoublesIntervalAndSpan() {
        var pts: List<Long> = emptyList()
        var iv = FullSpeedHistory.BASE_INTERVAL_SEC
        // Distinct values so the pairwise averaging is visible:
        // point i holds i * 10.
        for (i in 0 until FullSpeedHistory.MAX_POINTS) {
            val r = FullSpeedHistory.append(pts, iv, i * 10L)
            pts = r.first
            iv = r.second
        }
        val r = FullSpeedHistory.append(pts, iv, 9999L)
        pts = r.first
        iv = r.second
        // 720 -> 360 merged + the new point.
        assertEquals(361, pts.size)
        assertEquals(10, iv)
        // Merged point k is the mean of old points 2k and 2k+1:
        // (20k + (20k + 10)) / 2 = 20k + 5.
        assertEquals(5L, pts[0])
        assertEquals(25L, pts[1])
        assertEquals(7185L, pts[359])
        assertEquals(9999L, pts[360])
        // The span keeps growing past the hour instead of the
        // window sliding: 361 points x 10 s.
        assertEquals(3610L, FullSpeedHistory.spanSeconds(pts.size, iv))
    }

    @Test
    fun lengthNeverExceedsCapAcrossManyDecimations() {
        var pts: List<Long> = emptyList()
        var iv = FullSpeedHistory.BASE_INTERVAL_SEC
        // 10 000 samples at the base cadence would be ~14 hours
        // of transfer — several decimations deep.
        repeat(10_000) {
            val r = FullSpeedHistory.append(pts, iv, 500L)
            pts = r.first
            iv = r.second
            assertTrue(pts.size <= FullSpeedHistory.MAX_POINTS)
        }
        // Intervals only ever double from the base.
        assertTrue(iv >= 20)
        assertEquals(iv, FullSpeedHistory.sanitizeInterval(iv))
        assertTrue(pts.all { it == 500L })
    }

    @Test
    fun decimateAveragesPairsAndCarriesOddTail() {
        assertEquals(
            listOf(15L, 35L),
            FullSpeedHistory.decimate(listOf(10L, 20L, 30L, 40L)),
        )
        assertEquals(
            listOf(15L, 50L),
            FullSpeedHistory.decimate(listOf(10L, 20L, 50L)),
        )
        assertEquals(listOf(7L), FullSpeedHistory.decimate(listOf(7L)))
        assertEquals(emptyList<Long>(), FullSpeedHistory.decimate(emptyList()))
    }

    @Test
    fun shouldSampleFiresExactlyAtTheInterval() {
        assertFalse(FullSpeedHistory.shouldSample(4, 5))
        assertTrue(FullSpeedHistory.shouldSample(5, 5))
        assertFalse(FullSpeedHistory.shouldSample(9, 10))
        assertTrue(FullSpeedHistory.shouldSample(10, 10))
        // A corrupt interval falls back to the base cadence.
        assertTrue(FullSpeedHistory.shouldSample(5, 7))
    }

    @Test
    fun summaryOfKnownSeriesGivesPeakMeanAndCount() {
        // Sum 100 + 300 + 200 = 600, mean 600 / 3 = 200 exactly.
        val s = FullSpeedHistory.summary(listOf(100L, 300L, 200L))!!
        assertEquals(300L, s.peakBps)
        assertEquals(200L, s.avgBps)
        assertEquals(3, s.count)
    }

    @Test
    fun summaryMeanUsesLongDivisionWhenNotEvenlyDivisible() {
        // Sum 10 + 20 + 31 = 61; 61 / 3 = 20 in Long division
        // (the fraction is dropped, never rounded up).
        val s = FullSpeedHistory.summary(listOf(10L, 20L, 31L))!!
        assertEquals(31L, s.peakBps)
        assertEquals(20L, s.avgBps)
        assertEquals(3, s.count)
    }

    @Test
    fun summaryOfSinglePointIsThatPoint() {
        val s = FullSpeedHistory.summary(listOf(42L))!!
        assertEquals(42L, s.peakBps)
        assertEquals(42L, s.avgBps)
        assertEquals(1, s.count)
    }

    @Test
    fun summaryOfEmptyListIsNull() {
        assertNull(FullSpeedHistory.summary(emptyList()))
    }

    @Test
    fun summaryCountsDecimatedPointsAsStoredNotOriginals() {
        // Decimation merges (10,20) -> 15 and (30,40) -> 35, so
        // the stored series is [15, 35]: the summary must be
        // computed over THOSE two points (peak 35, mean
        // (15 + 35) / 2 = 25, count 2) — not over the four
        // original samples (which would give count 4).
        val stored = FullSpeedHistory.decimate(listOf(10L, 20L, 30L, 40L))
        assertEquals(listOf(15L, 35L), stored)
        val s = FullSpeedHistory.summary(stored)!!
        assertEquals(35L, s.peakBps)
        assertEquals(25L, s.avgBps)
        assertEquals(2, s.count)
    }

    @Test
    fun sanitizeIntervalOnlyAcceptsBaseTimesPowerOfTwo() {
        assertEquals(5, FullSpeedHistory.sanitizeInterval(5))
        assertEquals(10, FullSpeedHistory.sanitizeInterval(10))
        assertEquals(20, FullSpeedHistory.sanitizeInterval(20))
        assertEquals(640, FullSpeedHistory.sanitizeInterval(640))
        assertEquals(5, FullSpeedHistory.sanitizeInterval(0))
        assertEquals(5, FullSpeedHistory.sanitizeInterval(-10))
        assertEquals(5, FullSpeedHistory.sanitizeInterval(7))
        assertEquals(5, FullSpeedHistory.sanitizeInterval(15))
        assertEquals(5, FullSpeedHistory.sanitizeInterval(25))
    }
}

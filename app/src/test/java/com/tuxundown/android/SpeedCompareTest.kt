package com.tuxundown.android

import com.tuxundown.android.core.SpeedCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.2: multi-point speed comparison math — consent figures
 *  and the shared chart scale. */
class SpeedCompareTest {

    @Test
    fun reachedMeansAnyPhaseMovedBytes() {
        assertTrue(SpeedCompare.PointResult("a", 1, 0).reached)
        assertTrue(SpeedCompare.PointResult("a", 0, 1).reached)
        assertFalse(SpeedCompare.PointResult("a", 0, 0).reached)
    }

    @Test
    fun durationAndDataBandScaleWithPointCount() {
        // 3 points x 2 phases x 5 s.
        assertEquals(30, SpeedCompare.totalSeconds(3))
        assertEquals(10, SpeedCompare.totalSeconds(1))
        assertEquals(30..300, SpeedCompare.dataBandMB(3))
        assertEquals(10..100, SpeedCompare.dataBandMB(1))
    }

    @Test
    fun barFractionsShareOneScale() {
        val results = listOf(
            SpeedCompare.PointResult("a", 100, 400),
            SpeedCompare.PointResult("b", 200, 0),
        )
        val bars = SpeedCompare.barFractions(results)
        assertEquals(2, bars.size)
        assertEquals(0.25f, bars[0].first, 0.001f)
        assertEquals(1.0f, bars[0].second, 0.001f)
        assertEquals(0.5f, bars[1].first, 0.001f)
        assertEquals(0.0f, bars[1].second, 0.001f)
    }

    @Test
    fun barFractionsSurviveEmptyAndAllZero() {
        assertTrue(SpeedCompare.barFractions(emptyList()).isEmpty())
        val bars = SpeedCompare.barFractions(
            listOf(SpeedCompare.PointResult("a", 0, 0))
        )
        assertEquals(0f, bars[0].first, 0.001f)
        assertEquals(0f, bars[0].second, 0.001f)
    }
}

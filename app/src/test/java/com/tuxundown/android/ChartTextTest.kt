package com.tuxundown.android

import com.tuxundown.android.core.ChartText
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartTextTest {

    @Test
    fun fontScaleOneMatchesTheOldDpCaliber() {
        // The old code was 10.dp.toPx() = 10 × density. At the
        // default system font size the sp caliber must land on
        // exactly the same pixels — default look unchanged.
        for (density in listOf(1.0f, 1.5f, 2.0f, 2.625f, 3.0f, 4.0f)) {
            assertEquals(10f * density, ChartText.labelPx(10f, density, 1.0f), 0.001f)
            assertEquals(10f * density, ChartText.labelPx(density, 1.0f), 0.001f)
        }
    }

    @Test
    fun largerSystemFontScalesProportionally() {
        // fontScale 1.3 (a standard larger-font step) grows the
        // label by exactly that factor — the A-F2 fix.
        val base = ChartText.labelPx(10f, 2.0f, 1.0f)
        assertEquals(base * 1.3f, ChartText.labelPx(10f, 2.0f, 1.3f), 0.001f)
        assertEquals(26.0f, ChartText.labelPx(10f, 2.0f, 1.3f), 0.001f)
        // A smaller system font shrinks it the same way.
        assertEquals(base * 0.85f, ChartText.labelPx(10f, 2.0f, 0.85f), 0.001f)
    }

    @Test
    fun densityScalesProportionally() {
        assertEquals(10.0f, ChartText.labelPx(10f, 1.0f, 1.0f), 0.001f)
        assertEquals(20.0f, ChartText.labelPx(10f, 2.0f, 1.0f), 0.001f)
        assertEquals(30.0f, ChartText.labelPx(10f, 3.0f, 1.0f), 0.001f)
        // Density and fontScale multiply, neither swallows the
        // other.
        assertEquals(39.0f, ChartText.labelPx(10f, 3.0f, 1.3f), 0.001f)
    }

    @Test
    fun standardLabelIsTenSp() {
        assertEquals(10f, ChartText.LABEL_SP, 0.0f)
        assertEquals(
            ChartText.labelPx(10f, 2.5f, 1.15f),
            ChartText.labelPx(2.5f, 1.15f),
            0.001f,
        )
    }
}

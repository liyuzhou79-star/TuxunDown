package com.tuxundown.android

import com.tuxundown.android.core.A11yContrast
import com.tuxundown.android.core.HealthCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round 47: the contrast arithmetic itself, plus the
 * health-dot palette pinned against the app's real theme
 * surfaces in BOTH themes (the pre-47 fixed trio failed dark:
 * the BAD red was 2.37:1 on the dark surface).
 */
class A11yContrastTest {

    @Test
    fun ratio_blackOnWhiteIs21_whiteOnWhiteIs1() {
        assertEquals(21.0, A11yContrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, A11yContrast.ratio(0xFFFFFFFF, 0xFFFFFFFF), 0.001)
    }

    @Test
    fun ratio_isSymmetric() {
        val a = A11yContrast.ratio(0xFF2E7D32, 0xFF242424)
        val b = A11yContrast.ratio(0xFF242424, 0xFF2E7D32)
        assertEquals(a, b, 0.0001)
    }

    @Test
    fun healthDots_clearTextBarOnEverySurfaceOfTheirTheme() {
        for (level in HealthCheck.Level.values()) {
            val light = A11yContrast.healthDotArgb(level, dark = false)
            assertTrue(
                "light $level on surface",
                A11yContrast.ratio(light, A11yContrast.LIGHT_SURFACE) >= 4.5,
            )
            assertTrue(
                "light $level on background",
                A11yContrast.ratio(light, A11yContrast.LIGHT_BACKGROUND) >= 4.5,
            )
            val dark = A11yContrast.healthDotArgb(level, dark = true)
            assertTrue(
                "dark $level on surface",
                A11yContrast.ratio(dark, A11yContrast.DARK_SURFACE) >= 4.5,
            )
            assertTrue(
                "dark $level on background",
                A11yContrast.ratio(dark, A11yContrast.DARK_BACKGROUND) >= 4.5,
            )
            assertTrue(
                "dark $level on AMOLED black",
                A11yContrast.ratio(dark, A11yContrast.AMOLED_SURFACE) >= 4.5,
            )
        }
    }

    @Test
    fun healthDots_pre47FixedTrioReallyFailedDark() {
        // The regression this round fixes, pinned so nobody
        // "simplifies" the palette back to one fixed trio.
        assertTrue(
            A11yContrast.ratio(0xFFB3261E, A11yContrast.DARK_SURFACE) < 3.0,
        )
        assertTrue(
            A11yContrast.ratio(0xFFB26A00, A11yContrast.LIGHT_SURFACE) < 4.5,
        )
    }

    @Test
    fun sniffFab_whiteTextFailsOnTheGreen_blackTextPasses() {
        // SniffActivity's floating button: white on the brand
        // green is 2.90:1 (fails even the 3:1 graphics bar);
        // black — the same fix the v2.9 swipe labels got — is
        // 7.24:1. The activity therefore draws the label black.
        assertTrue(
            A11yContrast.ratio(
                A11yContrast.WHITE, A11yContrast.SNIFF_FAB_GREEN,
            ) < 3.0,
        )
        assertTrue(
            A11yContrast.ratio(
                A11yContrast.BLACK, A11yContrast.SNIFF_FAB_GREEN,
            ) >= 4.5,
        )
    }

    @Test
    fun statusTextGreenPair_bothClearTextBarOnTheirSurface() {
        // The v2.9 pair the dot palette now follows: the darker
        // green on light, the chart green on dark.
        assertTrue(
            A11yContrast.ratio(0xFF146C1E, A11yContrast.LIGHT_SURFACE) >= 4.5,
        )
        assertTrue(
            A11yContrast.ratio(0xFF1F9D2C, A11yContrast.DARK_SURFACE) >= 3.0,
        )
    }
}

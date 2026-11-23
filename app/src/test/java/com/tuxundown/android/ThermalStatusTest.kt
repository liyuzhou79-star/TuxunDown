package com.tuxundown.android

import com.tuxundown.android.core.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v6.1 (audit round 46): the thermal-status -> band mapping,
 * pinned value by value. The platform constants are
 * THERMAL_STATUS_NONE=0, LIGHT=1, MODERATE=2, SEVERE=3,
 * CRITICAL=4, EMERGENCY=5, SHUTDOWN=6 — the tests use the raw
 * ints so the mapping is pinned against the platform's actual
 * numbering, not against a second copy of the constants.
 */
class ThermalStatusTest {

    @Test
    fun noneAndLightAreNormal() {
        // LIGHT is "minor throttling, no user impact" in the
        // platform's own wording — it belongs with normal.
        assertEquals(ThermalStatus.Band.NORMAL, ThermalStatus.bandOf(0))
        assertEquals(ThermalStatus.Band.NORMAL, ThermalStatus.bandOf(1))
    }

    @Test
    fun moderateIsWarm() {
        assertEquals(ThermalStatus.Band.WARM, ThermalStatus.bandOf(2))
    }

    @Test
    fun severeAndAboveAreThrottling() {
        assertEquals(ThermalStatus.Band.THROTTLING, ThermalStatus.bandOf(3))
        assertEquals(ThermalStatus.Band.THROTTLING, ThermalStatus.bandOf(4))
        assertEquals(ThermalStatus.Band.THROTTLING, ThermalStatus.bandOf(5))
        assertEquals(ThermalStatus.Band.THROTTLING, ThermalStatus.bandOf(6))
    }

    @Test
    fun outOfBandValuesAreUnjudgeable() {
        // A future platform status (or a broken OEM value) must
        // read as "cannot judge" — the surfaces then omit the
        // thermal item entirely instead of faking a band.
        assertNull(ThermalStatus.bandOf(-1))
        assertNull(ThermalStatus.bandOf(7))
        assertNull(ThermalStatus.bandOf(99))
        assertNull(ThermalStatus.bandOf(Int.MIN_VALUE))
        assertNull(ThermalStatus.bandOf(Int.MAX_VALUE))
    }

    @Test
    fun hintFiresOnlyInTheThrottlingBand() {
        assertEquals(
            ThermalStatus.HINT,
            ThermalStatus.hintFor(ThermalStatus.Band.THROTTLING),
        )
        assertNull(ThermalStatus.hintFor(ThermalStatus.Band.WARM))
        assertNull(ThermalStatus.hintFor(ThermalStatus.Band.NORMAL))
        // Unreadable stays silent — never a guessed hint.
        assertNull(ThermalStatus.hintFor(null))
    }

    @Test
    fun bandLabelsAreTheThreeUserFacingWords() {
        assertEquals("正常", ThermalStatus.Band.NORMAL.label)
        assertEquals("偏热", ThermalStatus.Band.WARM.label)
        assertEquals("发热降速中", ThermalStatus.Band.THROTTLING.label)
    }
}

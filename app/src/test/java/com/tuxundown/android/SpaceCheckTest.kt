package com.tuxundown.android

import com.tuxundown.android.core.SpaceCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.3: the free-space pre-check's accounting. */
class SpaceCheckTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun unknownSizeNeverBlocks() {
        assertEquals(
            SpaceCheck.Verdict.Unknown,
            SpaceCheck.assess(availableBytes = 0, neededBytes = -1, reservedBytes = 0),
        )
    }

    @Test
    fun unmeasurableVolumeNeverBlocks() {
        assertEquals(
            SpaceCheck.Verdict.Unknown,
            SpaceCheck.assess(availableBytes = -1, neededBytes = 8 * gb, reservedBytes = 0),
        )
    }

    @Test
    fun enoughRoomIsOk() {
        assertEquals(
            SpaceCheck.Verdict.Ok,
            SpaceCheck.assess(availableBytes = 10 * gb, neededBytes = 6 * gb, reservedBytes = 0),
        )
    }

    @Test
    fun exactFitIsOk() {
        assertEquals(
            SpaceCheck.Verdict.Ok,
            SpaceCheck.assess(availableBytes = 6 * gb, neededBytes = 6 * gb, reservedBytes = 0),
        )
    }

    @Test
    fun shortfallReportsTheMissingBytes() {
        val v = SpaceCheck.assess(
            availableBytes = 5 * gb, neededBytes = 8 * gb, reservedBytes = 0,
        )
        assertTrue(v is SpaceCheck.Verdict.Insufficient)
        assertEquals(3 * gb, (v as SpaceCheck.Verdict.Insufficient).missingBytes)
    }

    @Test
    fun siblingReservationsCount() {
        // This file alone would fit (5 <= 10); with a sibling
        // still owing 6, the pair does not.
        val v = SpaceCheck.assess(
            availableBytes = 10 * gb, neededBytes = 5 * gb, reservedBytes = 6 * gb,
        )
        assertTrue(v is SpaceCheck.Verdict.Insufficient)
        assertEquals(1 * gb, (v as SpaceCheck.Verdict.Insufficient).missingBytes)
    }

    @Test
    fun remainingMath() {
        // Segmented resume: only the unwritten remainder is new.
        assertEquals(3 * gb, SpaceCheck.remainingOf(8 * gb, 5 * gb, restartFromScratch = false))
        // Single-stream restarts: the whole file is needed again.
        assertEquals(8 * gb, SpaceCheck.remainingOf(8 * gb, 5 * gb, restartFromScratch = true))
        // Over-downloaded state (should not happen) clamps to 0.
        assertEquals(0L, SpaceCheck.remainingOf(8 * gb, 9 * gb, restartFromScratch = false))
        // Unknown size stays unknown.
        assertEquals(-1L, SpaceCheck.remainingOf(-1, 0, restartFromScratch = false))
        // Empty file needs nothing.
        assertEquals(0L, SpaceCheck.remainingOf(0, 0, restartFromScratch = false))
    }

    @Test
    fun reservedSumsOnlyKnownPositives() {
        assertEquals(9L, SpaceCheck.reservedOf(listOf(4, -1, 5, 0)))
        assertEquals(0L, SpaceCheck.reservedOf(emptyList()))
    }

    @Test
    fun blockMessageNamesAllThreeNumbers() {
        val msg = SpaceCheck.blockMessage(8 * gb, 5 * gb, 3 * gb)
        assertTrue(msg.contains("空间不够"))
        assertTrue(msg.contains("还差"))
    }
}

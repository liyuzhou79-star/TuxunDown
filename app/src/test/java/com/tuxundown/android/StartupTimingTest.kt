package com.tuxundown.android

import com.tuxundown.android.core.StartupTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.1 (Step C): cold-start measurement decisions — the
 *  elapsed-time arithmetic, the cold/warm/recreation record
 *  gate, and the keep-vs-replace rule for the stored value. */
class StartupTimingTest {

    // ---------- elapsed ----------

    @Test
    fun elapsedIsEndMinusStart() {
        assertEquals(850L, StartupTiming.elapsedMs(1_000, 1_850))
        assertEquals(1L, StartupTiming.elapsedMs(5_000, 5_001))
    }

    @Test
    fun endBeforeStartRecordsNothing() {
        // Clock mix-up between the two sources must never
        // produce a negative (or fake) measurement.
        assertNull(StartupTiming.elapsedMs(2_000, 1_999))
        assertNull(StartupTiming.elapsedMs(2_000, 0))
    }

    @Test
    fun unreadableStartRecordsNothing() {
        assertNull(StartupTiming.elapsedMs(0, 1_000))
        assertNull(StartupTiming.elapsedMs(-5, 1_000))
    }

    // ---------- shouldRecord ----------

    @Test
    fun normalColdStartRecords() {
        assertTrue(
            StartupTiming.shouldRecord(
                isFirstActivityInProcess = true,
                safeMode = false,
                hasPendingCrash = false,
            )
        )
    }

    @Test
    fun warmStartAndRecreationDoNotRecord() {
        // Not the first MainActivity in the process (rotation,
        // config-change recreation, warm relaunch): the gate
        // is already spent, so nothing may be recorded.
        assertFalse(
            StartupTiming.shouldRecord(
                isFirstActivityInProcess = false,
                safeMode = false,
                hasPendingCrash = false,
            )
        )
    }

    @Test
    fun crashReportLaunchDoesNotRecord() {
        // The first frame would be the crash-report page, and
        // the user's time on it is reading time, not startup.
        assertFalse(
            StartupTiming.shouldRecord(
                isFirstActivityInProcess = true,
                safeMode = false,
                hasPendingCrash = true,
            )
        )
        assertFalse(
            StartupTiming.shouldRecord(
                isFirstActivityInProcess = true,
                safeMode = true,
                hasPendingCrash = false,
            )
        )
        assertFalse(
            StartupTiming.shouldRecord(
                isFirstActivityInProcess = true,
                safeMode = true,
                hasPendingCrash = true,
            )
        )
    }

    // ---------- the process gate ----------

    @Test
    fun gateGrantsExactlyOneClaimPerProcess() {
        val gate = StartupTiming.ColdStartGate()
        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
        // A NEW process (a new gate) claims again — that is
        // the next genuine cold start.
        assertTrue(StartupTiming.ColdStartGate().claim())
    }

    // ---------- nextStoredValue ----------

    @Test
    fun recordableColdStartReplacesTheOldValue() {
        assertEquals(900L, StartupTiming.nextStoredValue(1_200, 900, recordable = true))
        assertEquals(700L, StartupTiming.nextStoredValue(0, 700, recordable = true))
    }

    @Test
    fun nonRecordableLaunchKeepsTheExistingValue() {
        // Warm start / recreation / crash launch: whatever
        // candidate was (not) produced, the stored last cold
        // start survives untouched.
        assertEquals(1_200L, StartupTiming.nextStoredValue(1_200, 300, recordable = false))
        assertEquals(1_200L, StartupTiming.nextStoredValue(1_200, null, recordable = false))
        assertEquals(0L, StartupTiming.nextStoredValue(0, 300, recordable = false))
    }

    @Test
    fun invalidCandidateKeepsTheExistingValue() {
        assertEquals(1_200L, StartupTiming.nextStoredValue(1_200, null, recordable = true))
        assertEquals(1_200L, StartupTiming.nextStoredValue(1_200, 0, recordable = true))
        assertEquals(1_200L, StartupTiming.nextStoredValue(1_200, -50, recordable = true))
    }

    // ---------- display ----------

    @Test
    fun displayTextHidesWhenThereIsNoValue() {
        // First install / never measured: the diagnostics row
        // (and the copied report line) must be absent — never
        // "约 0 毫秒".
        assertNull(StartupTiming.displayText(0))
        assertNull(StartupTiming.displayText(-1))
        assertEquals("约 850 毫秒", StartupTiming.displayText(850))
    }
}

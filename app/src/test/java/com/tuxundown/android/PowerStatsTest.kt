package com.tuxundown.android

import com.tuxundown.android.core.PowerStats
import com.tuxundown.android.core.WakeLockCounter
import com.tuxundown.android.core.WakeLockHandle
import com.tuxundown.android.core.WakeLockMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.6 (audit round 61, Step C): the 功耗 bookkeeping,
 * pinned pure — the wake-lock meter's accumulation (with
 * an injected clock), the counter's transition taps, the
 * sampler tick counter, the poll-source assembly caliber
 * and the section's absence rules (see PowerStats).
 */
class PowerStatsTest {

    // ---------- WakeLockMeter: accumulation ----------

    @Test
    fun meterAccumulatesOneHold() {
        val meter = WakeLockMeter()
        assertFalse(meter.isHeld())
        assertEquals(0, meter.heldTotalMs(1_000))
        meter.noteAcquired(1_000)
        assertTrue(meter.isHeld())
        // In-progress hold counts up to "now".
        assertEquals(2_500, meter.heldTotalMs(3_500))
        meter.noteReleased(4_000)
        assertFalse(meter.isHeld())
        assertEquals(3_000, meter.heldTotalMs(9_999))
    }

    @Test
    fun meterSecondAcquireDoesNotRestartTheStamp() {
        // Overlapping runs share the ONE underlying hold:
        // a second acquire must not restart the clock, and
        // the span runs to the single release.
        val meter = WakeLockMeter()
        meter.noteAcquired(1_000)
        meter.noteAcquired(2_000)
        meter.noteReleased(5_000)
        assertEquals(4_000, meter.heldTotalMs(5_000))
    }

    @Test
    fun meterReleaseWithoutAcquireIsNoOp() {
        val meter = WakeLockMeter()
        meter.noteReleased(5_000)
        assertFalse(meter.isHeld())
        assertEquals(0, meter.heldTotalMs(6_000))
    }

    @Test
    fun meterSumsSeparateHolds() {
        val meter = WakeLockMeter()
        meter.noteAcquired(0)
        meter.noteReleased(1_000)
        meter.noteAcquired(2_000)
        meter.noteReleased(4_500)
        assertEquals(3_500, meter.heldTotalMs(10_000))
        // A third hold in progress adds on top.
        meter.noteAcquired(10_000)
        assertEquals(4_000, meter.heldTotalMs(10_500))
    }

    @Test
    fun meterBackwardsClockContributesZero() {
        val meter = WakeLockMeter()
        meter.noteAcquired(5_000)
        meter.noteReleased(4_000)
        assertEquals(0, meter.heldTotalMs(4_500))
    }

    // ---------- WakeLockCounter: transition taps ----------

    private class FakeHandle : WakeLockHandle {
        var acquires = 0
        var releases = 0
        override fun acquire() { acquires++ }
        override fun release() { releases++ }
    }

    @Test
    fun counterTapsFireOncePerUnderlyingTransition() {
        val fake = FakeHandle()
        var firsts = 0
        var lasts = 0
        val counter = WakeLockCounter(
            fake,
            onFirstAcquire = { firsts++ },
            onLastRelease = { lasts++ },
        )
        // Two overlapping holds: ONE underlying transition pair.
        assertTrue(counter.acquireIf(enabled = true))
        assertTrue(counter.acquireIf(enabled = true))
        assertEquals(1, firsts)
        assertEquals(0, lasts)
        counter.release()
        assertEquals(0, lasts) // still held by the second run
        counter.release()
        assertEquals(1, lasts)
        // A stray release fires nothing.
        counter.release()
        assertEquals(1, lasts)
        assertEquals(1, fake.acquires)
        assertEquals(1, fake.releases)
    }

    @Test
    fun counterTapsStaySilentWhenSwitchOff() {
        var firsts = 0
        var lasts = 0
        val counter = WakeLockCounter(
            FakeHandle(),
            onFirstAcquire = { firsts++ },
            onLastRelease = { lasts++ },
        )
        assertFalse(counter.acquireIf(enabled = false))
        counter.release()
        assertEquals(0, firsts)
        assertEquals(0, lasts)
    }

    @Test
    fun counterTapsFollowReconcileTransitions() {
        // The mid-run reconcile rides acquireIf / release,
        // so its transitions are the same taps: off drops
        // the hold (last-release tap), on re-takes it
        // (first-acquire tap).
        var firsts = 0
        var lasts = 0
        val counter = WakeLockCounter(
            FakeHandle(),
            onFirstAcquire = { firsts++ },
            onLastRelease = { lasts++ },
        )
        var holding = counter.acquireIf(enabled = true)
        assertEquals(1, firsts)
        holding = counter.reconcile(enabled = false, holding = holding)
        assertFalse(holding)
        assertEquals(1, lasts)
        holding = counter.reconcile(enabled = true, holding = holding)
        assertTrue(holding)
        assertEquals(2, firsts)
        assertEquals(1, lasts)
    }

    @Test
    fun counterTapStaysSilentWhenAcquireThrows() {
        // A failed platform acquire leaves no phantom hold
        // (pinned since round 46) — and no phantom tap: the
        // meter must never start a span for a hold that
        // does not exist.
        val flaky = object : WakeLockHandle {
            override fun acquire() { throw RuntimeException("pm dead") }
            override fun release() {}
        }
        var firsts = 0
        val counter = WakeLockCounter(flaky, onFirstAcquire = { firsts++ })
        try {
            counter.acquireIf(enabled = true)
        } catch (e: RuntimeException) {
            // expected: propagates to WakeGuard's wrapper
        }
        assertEquals(0, firsts)
        assertEquals(0, counter.activeCount)
    }

    // ---------- sampler tick counter ----------

    @Test
    fun samplerTickCounterIncrementsByOnePerNote() {
        val before = PowerStats.samplerTicks()
        PowerStats.noteSamplerTick()
        PowerStats.noteSamplerTick()
        PowerStats.noteSamplerTick()
        assertEquals(before + 3, PowerStats.samplerTicks())
    }

    // ---------- active poll-source assembly ----------

    @Test
    fun pollSourceCountSumsTheThreeFacts() {
        assertEquals(
            5,
            PowerStats.activePollSourceCount(
                runningSamplers = 3, precheckAlive = true, drainAlive = true,
            ),
        )
        assertEquals(
            2,
            PowerStats.activePollSourceCount(
                runningSamplers = 2, precheckAlive = false, drainAlive = false,
            ),
        )
        assertEquals(
            0,
            PowerStats.activePollSourceCount(
                runningSamplers = 0, precheckAlive = false, drainAlive = false,
            ),
        )
    }

    @Test
    fun pollSourceCountAbsentWhenAnyFactUnreadable() {
        assertNull(
            PowerStats.activePollSourceCount(
                runningSamplers = null, precheckAlive = true, drainAlive = true,
            ),
        )
        assertNull(
            PowerStats.activePollSourceCount(
                runningSamplers = 1, precheckAlive = null, drainAlive = true,
            ),
        )
        assertNull(
            PowerStats.activePollSourceCount(
                runningSamplers = 1, precheckAlive = true, drainAlive = null,
            ),
        )
        assertNull(
            PowerStats.activePollSourceCount(
                runningSamplers = -1, precheckAlive = true, drainAlive = true,
            ),
        )
    }

    // ---------- section lines / absence rules ----------

    @Test
    fun linesAssembleAllThreeRows() {
        val rows = PowerStats.lines(
            PowerStats.Snapshot(
                wakeLockHeldTotalMs = 65_000,
                wakeLockHeldNow = true,
                samplerTicks = 42,
                activePollSources = 3,
            ),
        )
        assertEquals(3, rows.size)
        assertEquals("唤醒锁持有", rows[0].first)
        assertEquals("本进程累计持有 1 分 5 秒；当前正持有", rows[0].second)
        assertEquals("采样 tick", rows[1].first)
        assertEquals("本进程累计 42 次", rows[1].second)
        assertEquals("活跃轮询源", rows[2].first)
        assertTrue(rows[2].second.startsWith("当前共 3 个"))
    }

    @Test
    fun linesShowRealZerosAndNotHeldFragment() {
        val rows = PowerStats.lines(
            PowerStats.Snapshot(
                wakeLockHeldTotalMs = 0,
                wakeLockHeldNow = false,
                samplerTicks = 0,
                activePollSources = 0,
            ),
        )
        assertEquals(3, rows.size)
        assertEquals("本进程累计持有 0 秒；当前未持有", rows[0].second)
        assertEquals("本进程累计 0 次", rows[1].second)
        assertTrue(rows[2].second.startsWith("当前共 0 个"))
    }

    @Test
    fun linesDropEachUnreadableRowAlone() {
        val rows = PowerStats.lines(
            PowerStats.Snapshot(
                wakeLockHeldTotalMs = null,
                wakeLockHeldNow = null,
                samplerTicks = 7,
                activePollSources = null,
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("采样 tick", rows[0].first)
    }

    @Test
    fun linesWakeLockRowSurvivesUnreadableHeldNow() {
        // Only the held-now FRAGMENT drops; the total the
        // page did read still shows.
        val rows = PowerStats.lines(
            PowerStats.Snapshot(
                wakeLockHeldTotalMs = 2_000,
                wakeLockHeldNow = null,
                samplerTicks = null,
                activePollSources = null,
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("本进程累计持有 2 秒", rows[0].second)
    }

    @Test
    fun linesDropNegativeFigures() {
        val rows = PowerStats.lines(
            PowerStats.Snapshot(
                wakeLockHeldTotalMs = -1,
                wakeLockHeldNow = false,
                samplerTicks = -5,
                activePollSources = -2,
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun linesAllUnreadableMeansSectionAbsent() {
        val rows = PowerStats.lines(
            PowerStats.Snapshot(
                wakeLockHeldTotalMs = null,
                wakeLockHeldNow = null,
                samplerTicks = null,
                activePollSources = null,
            ),
        )
        assertTrue(rows.isEmpty())
    }
}

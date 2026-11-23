package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.HlsByteBudget
import com.tuxundown.android.core.MemoryTier
import com.tuxundown.android.core.MemoryTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** v5.1 (Step B): memory-tier decision boundaries, the budget
 *  table's invariants, and the shared HLS byte ledger. */
class MemoryTierTest {

    private val mb = 1024L * 1024L

    // ---------- tier decision ----------

    @Test
    fun lowRamDeviceForcesLowWhateverTheClass() {
        assertEquals(MemoryTier.LOW, MemoryTiers.tierFor(512, isLowRamDevice = true))
        assertEquals(MemoryTier.LOW, MemoryTiers.tierFor(256, isLowRamDevice = true))
        assertEquals(MemoryTier.LOW, MemoryTiers.tierFor(128, isLowRamDevice = true))
    }

    @Test
    fun memoryClassBoundaries() {
        // The 128 MB class (Step A's ledger device) is LOW.
        assertEquals(MemoryTier.LOW, MemoryTiers.tierFor(128, false))
        assertEquals(MemoryTier.LOW, MemoryTiers.tierFor(160, false))
        assertEquals(MemoryTier.STANDARD, MemoryTiers.tierFor(161, false))
        assertEquals(MemoryTier.STANDARD, MemoryTiers.tierFor(192, false))
        assertEquals(MemoryTier.STANDARD, MemoryTiers.tierFor(256, false))
        assertEquals(MemoryTier.STANDARD, MemoryTiers.tierFor(383, false))
        assertEquals(MemoryTier.HIGH, MemoryTiers.tierFor(384, false))
        assertEquals(MemoryTier.HIGH, MemoryTiers.tierFor(512, false))
    }

    @Test
    fun unreadableClassFallsBackToStandard() {
        assertEquals(MemoryTier.STANDARD, MemoryTiers.tierFor(0, false))
        assertEquals(MemoryTier.STANDARD, MemoryTiers.tierFor(-1, false))
    }

    // ---------- tier label ----------

    @Test
    fun tierLabelNamesEveryTier() {
        // v5.6 (R5 closure): the diagnostics page wording, pinned
        // verbatim like the other label tests in this repo.
        assertEquals(
            "低（这台设备内存较小，缓冲已自动收紧）",
            MemoryTiers.tierLabel(MemoryTier.LOW),
        )
        assertEquals(
            "标准（按这台设备的内存用的默认缓冲）",
            MemoryTiers.tierLabel(MemoryTier.STANDARD),
        )
        assertEquals(
            "高（这台设备内存充足，缓冲已适当放宽）",
            MemoryTiers.tierLabel(MemoryTier.HIGH),
        )
    }

    // ---------- budget table ----------

    @Test
    fun standardPerTaskBudgetIsExactlyThePre51Account() {
        // Mid-range behaviour must not change: STANDARD keeps
        // the 4 MB/task account bufferSizeFor always had.
        val b = MemoryTiers.budgetFor(MemoryTier.STANDARD)
        assertEquals(4 * mb, b.perTaskBufferBytes)
        for (t in intArrayOf(1, 8, 32, 64)) {
            assertEquals(
                DownloadEngine.bufferSizeFor(t),
                DownloadEngine.bufferSizeFor(t, b.perTaskBufferBytes),
            )
        }
    }

    @Test
    fun tierBudgetsOrderAndHlsGlobalNeverExceedsOldPerTaskAtStandard() {
        val low = MemoryTiers.budgetFor(MemoryTier.LOW)
        val std = MemoryTiers.budgetFor(MemoryTier.STANDARD)
        val high = MemoryTiers.budgetFor(MemoryTier.HIGH)
        assertEquals(2 * mb, low.perTaskBufferBytes)
        assertEquals(6 * mb, high.perTaskBufferBytes)
        assertTrue(low.perTaskBufferBytes < std.perTaskBufferBytes)
        assertTrue(std.perTaskBufferBytes < high.perTaskBufferBytes)
        // HLS global budgets: LOW tightens hard, STANDARD's
        // GLOBAL total equals the old single-task 48 MB (never
        // above it), HIGH may widen modestly.
        assertEquals(16 * mb, low.hlsGlobalBudgetBytes)
        assertEquals(48 * mb, std.hlsGlobalBudgetBytes)
        assertEquals(DownloadEngine.HLS_BUFFER_BUDGET_BYTES, std.hlsGlobalBudgetBytes)
        assertEquals(64 * mb, high.hlsGlobalBudgetBytes)
        // Segment caps scale with the tier; HIGH keeps the old
        // 64 MB ceiling, and normal segments (2-10 MB) fit
        // every tier with room to spare.
        assertEquals(16 * mb, low.hlsMaxSegmentBytes.toLong())
        assertEquals(32 * mb, std.hlsMaxSegmentBytes.toLong())
        assertEquals(DownloadEngine.MAX_SEGMENT_BYTES, high.hlsMaxSegmentBytes)
        assertTrue(low.hlsMaxSegmentBytes > 10 * mb)
        // Cursor exemption covers exactly one max-size segment.
        for (b in listOf(low, std, high)) {
            assertEquals(b.hlsMaxSegmentBytes.toLong(), b.hlsCursorExemptCapBytes)
        }
    }

    @Test
    fun bufferSizesAt128ConnectionsPerTier() {
        // v7.7: at the raised 128 top each tier's per-connection
        // buffer is exactly its budget share — LOW 16 KB,
        // STANDARD 32 KB, HIGH 48 KB — and each task total lands
        // exactly on the tier budget (the 16 KB floor is what
        // keeps LOW's share from being rounded up to 32 KB,
        // which would have doubled its total to 4 MB).
        val low = MemoryTiers.budgetFor(MemoryTier.LOW)
        val std = MemoryTiers.budgetFor(MemoryTier.STANDARD)
        val high = MemoryTiers.budgetFor(MemoryTier.HIGH)
        assertEquals(16 * 1024, DownloadEngine.bufferSizeFor(128, low.perTaskBufferBytes))
        assertEquals(32 * 1024, DownloadEngine.bufferSizeFor(128, std.perTaskBufferBytes))
        assertEquals(48 * 1024, DownloadEngine.bufferSizeFor(128, high.perTaskBufferBytes))
        // At 64 and below nothing moved in any tier.
        assertEquals(32 * 1024, DownloadEngine.bufferSizeFor(64, low.perTaskBufferBytes))
        assertEquals(64 * 1024, DownloadEngine.bufferSizeFor(64, std.perTaskBufferBytes))
    }

    @Test
    fun tieredBufferTotalsNeverExceedThePerTaskBudget() {
        // The v4.2 invariant, swept per tier: n buffers sized by
        // bufferSizeFor(n, budget) total at most the budget.
        // v7.7: the sweep now runs to the raised 128 top.
        for (tier in MemoryTier.values()) {
            val budget = MemoryTiers.budgetFor(tier).perTaskBufferBytes
            for (n in 1..128) {
                val total = DownloadEngine.bufferSizeFor(n, budget).toLong() * n
                assertTrue(
                    "$tier x$n buffers total $total exceeds budget $budget",
                    total <= budget,
                )
            }
        }
    }

    // ---------- HLS ledger ----------

    private fun ledger(tier: MemoryTier = MemoryTier.STANDARD): HlsByteBudget {
        val b = MemoryTiers.budgetFor(tier)
        return HlsByteBudget(b.hlsGlobalBudgetBytes, b.hlsMaxSegmentBytes, b.hlsCursorExemptCapBytes)
    }

    @Test
    fun tryAcquireNeverOvershootsTheGlobalBudget() {
        val l = ledger(MemoryTier.LOW) // 16 MB global
        assertTrue(l.tryAcquire(10 * mb))
        assertFalse(l.tryAcquire(7 * mb))
        assertTrue(l.tryAcquire(6 * mb))
        assertEquals(16 * mb, l.bufferedBytes())
        assertFalse(l.canSubmitPrefetch())
        l.release(6 * mb)
        assertTrue(l.canSubmitPrefetch())
    }

    @Test
    fun releaseNeverDrivesTheLedgerNegative() {
        val l = ledger()
        l.onFetched(3 * mb)
        l.release(10 * mb) // over-release (e.g. double cleanup)
        assertEquals(0L, l.bufferedBytes())
        l.release(1)
        assertEquals(0L, l.bufferedBytes())
    }

    @Test
    fun cancelCleanupReturnsEveryByte() {
        // Simulates a run ending with unwritten fetches: the
        // engine releases its whole local remainder in finally.
        val l = ledger()
        l.onFetched(5 * mb)
        l.onFetched(7 * mb)
        l.release(5 * mb) // one segment written
        assertEquals(7 * mb, l.bufferedBytes())
        l.release(7 * mb) // cancel cleanup of the remainder
        assertEquals(0L, l.bufferedBytes())
    }

    @Test
    fun cursorExemptionIsBoundedToOneMaxSegment() {
        val l = ledger(MemoryTier.LOW)
        assertEquals(1, l.maxCursorClaims)
        assertTrue(l.tryClaimCursor())
        assertFalse("second cursor claim must wait — the exemption is capped", l.tryClaimCursor())
        l.releaseCursorClaim()
        assertTrue(l.tryClaimCursor())
        // Release floors at zero claims too.
        l.releaseCursorClaim()
        l.releaseCursorClaim()
        assertEquals(0, l.cursorClaims())
    }

    @Test
    fun forceClaimCursorPassesTheCapButStillCountsAndReleases() {
        // v5.6 re-audit find: forceClaimCursor (the engine's
        // last-resort so a writer never starves behind another
        // run's stuck cursor) had no test at all, unlike every
        // other ledger operation above.
        val l = ledger(MemoryTier.LOW) // exactly 1 claim under the cap
        assertTrue(l.tryClaimCursor())
        assertFalse(l.tryClaimCursor())
        l.forceClaimCursor()
        assertEquals(2, l.cursorClaims())
        // Still counted, still must be released — and once back
        // at the cap, ordinary claims wait again.
        l.releaseCursorClaim()
        assertEquals(1, l.cursorClaims())
        assertFalse(l.tryClaimCursor())
        l.releaseCursorClaim()
        assertTrue(l.tryClaimCursor())
    }

    @Test
    fun concurrentAcquiresNeverOvershoot() {
        val l = ledger(MemoryTier.LOW) // 16 MB
        val overshot = AtomicBoolean(false)
        val threads = (1..16).map {
            thread {
                repeat(200) {
                    if (l.tryAcquire(1024)) {
                        if (l.bufferedBytes() > 16 * mb) overshot.set(true)
                        Thread.yield()
                        l.release(1024)
                    }
                }
            }
        }
        threads.forEach { it.join() }
        assertFalse(overshot.get())
        assertEquals(0L, l.bufferedBytes())
    }

    @Test
    fun concurrentCursorClaimsStayWithinTheCap() {
        val l = ledger(MemoryTier.STANDARD) // exactly 1 claim
        val held = java.util.concurrent.atomic.AtomicInteger(0)
        val exceeded = AtomicBoolean(false)
        val threads = (1..8).map {
            thread {
                repeat(100) {
                    if (l.tryClaimCursor()) {
                        if (held.incrementAndGet() > 1) exceeded.set(true)
                        Thread.yield()
                        held.decrementAndGet()
                        l.releaseCursorClaim()
                    }
                }
            }
        }
        threads.forEach { it.join() }
        assertFalse(exceeded.get())
        assertEquals(0, l.cursorClaims())
    }
}

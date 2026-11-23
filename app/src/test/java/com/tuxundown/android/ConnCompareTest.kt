package com.tuxundown.android

import com.tuxundown.android.core.ConnCompare
import com.tuxundown.android.core.DownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnCompareTest {

    private fun tier(connections: Int, bps: Long) =
        ConnCompare.TierResult(connections, bps, windowMs = 2_500, bytes = bps * 5 / 2)

    @Test
    fun bestTierPicksTheClearWinner() {
        val results = listOf(
            tier(4, 1_000_000),
            tier(16, 4_000_000),
            tier(32, 3_000_000),
            tier(64, 2_000_000),
        )
        assertEquals(16, ConnCompare.bestTier(results)?.connections)
    }

    @Test
    fun bestTierTieGoesToFewerConnections() {
        // 32 and 64 tie on speed — the cheaper 32 wins, in either
        // list order (the verdict must not depend on run order).
        val forward = listOf(tier(4, 1_000_000), tier(32, 5_000_000), tier(64, 5_000_000))
        val backward = listOf(tier(64, 5_000_000), tier(32, 5_000_000), tier(4, 1_000_000))
        assertEquals(32, ConnCompare.bestTier(forward)?.connections)
        assertEquals(32, ConnCompare.bestTier(backward)?.connections)
    }

    @Test
    fun bestTierAllZeroIsNull() {
        val results = listOf(tier(4, 0), tier(16, 0), tier(32, 0), tier(64, 0))
        assertNull(ConnCompare.bestTier(results))
    }

    @Test
    fun bestTierEmptyIsNull() {
        assertNull(ConnCompare.bestTier(emptyList()))
    }

    @Test
    fun bestTierIgnoresZeroTiersWhenOthersMoved() {
        val results = listOf(tier(4, 0), tier(16, 900_000), tier(32, 0))
        assertEquals(16, ConnCompare.bestTier(results)?.connections)
    }

    @Test
    fun tierBudgetSubtractsWhatWasSpent() {
        assertEquals(ConnCompare.TOTAL_CAP_BYTES, ConnCompare.tierBudget(0))
        assertEquals(
            ConnCompare.TOTAL_CAP_BYTES - 1_000_000,
            ConnCompare.tierBudget(1_000_000),
        )
    }

    @Test
    fun tierBudgetOverspentIsZero() {
        assertEquals(0L, ConnCompare.tierBudget(ConnCompare.TOTAL_CAP_BYTES + 1))
        assertEquals(0L, ConnCompare.tierBudget(Long.MAX_VALUE / 2))
    }

    @Test
    fun tierBudgetExactlySpentIsZero() {
        assertEquals(0L, ConnCompare.tierBudget(ConnCompare.TOTAL_CAP_BYTES))
    }

    @Test
    fun tiersIncludeThe128TopAndConsentNamesIt() {
        // v7.7: the comparison gains the raised per-task top as
        // a fifth tier, so the user can measure whether 128
        // actually helps on their link. The traffic honesty is
        // unchanged: all tiers still share the ONE hard cap, so
        // the extra tier can only spend what earlier tiers left.
        assertEquals(listOf(4, 16, 32, 64, 128), ConnCompare.TIERS)
        assertEquals(DownloadEngine.MAX_THREADS, ConnCompare.TIERS.last())
        assertTrue(ConnCompare.consentText().contains("128"))
        assertEquals(
            ConnCompare.TOTAL_CAP_BYTES,
            ConnCompare.tierBudget(0),
        )
    }

    @Test
    fun constantsAreSelfConsistent() {
        assertEquals(ConnCompare.TIERS.sorted(), ConnCompare.TIERS)
        assertEquals(ConnCompare.TIERS.distinct(), ConnCompare.TIERS)
        assertTrue(64 in ConnCompare.TIERS)
        assertTrue(ConnCompare.PHASE_MS > 0)
        assertTrue(ConnCompare.TOTAL_CAP_BYTES > 0)
    }

    @Test
    fun runningTasksNoteIsEmptyWhenNothingRuns() {
        // Audit round 27: no contention at the start moment ->
        // no note (the static consent line already covers the
        // general advice).
        assertEquals("", ConnCompare.runningTasksNote(0))
        assertEquals("", ConnCompare.runningTasksNote(-1))
    }

    @Test
    fun runningTasksNoteNamesTheStartMomentCount() {
        val note = ConnCompare.runningTasksNote(3)
        assertTrue(note.contains("3"))
        assertTrue(note.contains("开始实测时"))
        assertTrue(note.contains("偏低"))
    }

    @Test
    fun runningTasksNotePromisesContentionNotPause() {
        // v5.9 audit round 44: the note is the copy the speed
        // test page now shows too, and it must stay aligned
        // with the implementation — the tests do NOT pause
        // running downloads and do NOT share the bandwidth
        // budget with them; both sides contend for the same
        // network, which is exactly what the note says （抢带宽，
        // 结果偏低） and nothing stronger.
        val note = ConnCompare.runningTasksNote(2)
        assertTrue(note.contains("抢带宽"))
        assertTrue(!note.contains("暂停"))
        assertTrue(!note.contains("让路"))
    }

    @Test
    fun consentTextMatchesTheConstants() {
        val text = ConnCompare.consentText()
        // The numbers the user consents to are the numbers the
        // code enforces — both come from the same constants.
        for (t in ConnCompare.TIERS) assertTrue(text.contains(t.toString()))
        assertTrue(text.contains((ConnCompare.PHASE_MS / 1000).toString()))
        assertTrue(text.contains((ConnCompare.TOTAL_CAP_BYTES / 1024 / 1024).toString()))
    }
}

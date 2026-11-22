package com.tuxundown.android

import com.tuxundown.android.core.DownloadedFiles
import com.tuxundown.android.core.DuplicateFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.1: duplicate detection — size gate first, hash decides. */
class DuplicateFinderTest {

    private fun e(name: String, size: Long, uri: String = "content://$name") =
        DownloadedFiles.Entry(name, uri, size, 0, "")

    @Test
    fun sizeCandidatesNeedASharedKnownSize() {
        val a = e("a.bin", 100)
        val b = e("b.bin", 100)
        val lonely = e("c.bin", 200)
        val unknown1 = e("d.bin", -1)
        val unknown2 = e("e.bin", -1)
        val zero = e("f.bin", 0)
        val out = DuplicateFinder.sizeCandidates(
            listOf(a, b, lonely, unknown1, unknown2, zero)
        )
        // Only the two 100-byte files qualify: a unique size can
        // never be a duplicate, and unknown/zero sizes prove
        // nothing (two -1 files are NOT a pair).
        assertEquals(setOf(a, b), out.toSet())
    }

    @Test
    fun hashDecidesWithinASizeGroup() {
        val a = e("a.bin", 100, "u1")
        val b = e("b.bin", 100, "u2")
        val c = e("c.bin", 100, "u3")
        val groups = DuplicateFinder.confirmedGroups(
            listOf(a, b, c),
            mapOf("u1" to "hashX", "u2" to "hashX", "u3" to "hashY"),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf(a, b), groups[0].entries.toSet())
        assertEquals(100L, groups[0].reclaimableBytes)
    }

    @Test
    fun unhashedCandidatesTakeNoPart() {
        val a = e("a.bin", 100, "u1")
        val b = e("b.bin", 100, "u2")
        // b's hash is missing (unreadable / scan cancelled):
        // no group may be claimed from one file alone.
        val groups = DuplicateFinder.confirmedGroups(listOf(a, b), mapOf("u1" to "h"))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun groupsSortByReclaimableAndPlanCountsWipes() {
        val big1 = e("big1.bin", 1000, "b1")
        val big2 = e("big2.bin", 1000, "b2")
        val big3 = e("big3.bin", 1000, "b3")
        val small1 = e("s1.bin", 10, "s1")
        val small2 = e("s2.bin", 10, "s2")
        val groups = DuplicateFinder.confirmedGroups(
            listOf(small1, small2, big1, big2, big3),
            mapOf("b1" to "hb", "b2" to "hb", "b3" to "hb", "s1" to "hs", "s2" to "hs"),
        )
        assertEquals(2, groups.size)
        assertEquals(2000L, groups[0].reclaimableBytes) // 3 copies of 1000
        assertEquals(2010L, DuplicateFinder.totalReclaimable(groups))
        // Delete 2 of the big group + BOTH small files: frees
        // 2000 + 20 and wipes exactly one group entirely.
        val plan = DuplicateFinder.deletionPlan(groups, setOf("b1", "b2", "s1", "s2"))
        assertEquals(2020L, plan.freedBytes)
        assertEquals(1, plan.wipedGroups)
    }
}

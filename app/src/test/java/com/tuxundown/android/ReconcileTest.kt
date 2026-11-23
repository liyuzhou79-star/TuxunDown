package com.tuxundown.android

import com.tuxundown.android.core.Reconcile
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v6.7.0 Step B: reconciliation verdict logic over fake directory data. */
class ReconcileTest {

    private fun rec(id: String, uri: String, name: String, size: Long) =
        Reconcile.RecordSnap(id, uri, name, size)

    private fun scan(uri: String, name: String, size: Long, mod: Long = 1000) =
        Reconcile.ScanEntry(uri, name, size, mod)

    @Test
    fun threeStateSplitGhostExistsUnknown() {
        val records = listOf(
            rec("g", "u-gone", "gone.zip", 10),
            rec("e", "u-live", "live.zip", 20),
            rec("u", "u-unknown", "maybe.zip", 30),
            rec("n", "u-noprobe", "never.zip", 40),
        )
        val probes = mapOf(
            "u-gone" to Reconcile.Probe(Reconcile.Verdict.MISSING),
            "u-live" to Reconcile.Probe(Reconcile.Verdict.EXISTS, "live.zip", 20),
            "u-unknown" to Reconcile.Probe(Reconcile.Verdict.UNKNOWN),
        )
        val r = Reconcile.reconcile(records, probes, emptyList())
        assertEquals(listOf("g"), r.ghosts.map { it.id })
        // UNKNOWN verdict AND a record nobody probed both count
        // as unknown — neither is judged dead.
        assertEquals(2, r.unknownCount)
        assertTrue(r.renames.isEmpty())
    }

    @Test
    fun unknownNeverBecomesGhost() {
        val records = listOf(rec("a", "u1", "a.bin", 5))
        val r = Reconcile.reconcile(
            records,
            mapOf("u1" to Reconcile.Probe(Reconcile.Verdict.UNKNOWN)),
            emptyList(),
        )
        assertTrue(r.ghosts.isEmpty())
        assertEquals(1, r.unknownCount)
    }

    @Test
    fun unclaimedExcludesClaimedScanEntries() {
        val records = listOf(rec("t1", "u1", "a.zip", 10))
        val probes = mapOf("u1" to Reconcile.Probe(Reconcile.Verdict.EXISTS, "a.zip", 10))
        val scans = listOf(
            scan("u1", "a.zip", 10),   // claimed by the record
            scan("u2", "b.zip", 20),   // unclaimed
        )
        val r = Reconcile.reconcile(records, probes, scans)
        assertEquals(listOf("u2"), r.unclaimed.map { it.uri })
    }

    @Test
    fun pairNeedsExactPositiveSizeAndSameExtension() {
        val ghost = rec("g", "u-gone", "movie.mp4", 100)
        val probes = mapOf("u-gone" to Reconcile.Probe(Reconcile.Verdict.MISSING))
        // exact size + same extension (case-insensitive): pairs
        var r = Reconcile.reconcile(
            listOf(ghost), probes, listOf(scan("s1", "renamed.MP4", 100)),
        )
        assertEquals(1, r.pairs.size)
        // different extension: no pair
        r = Reconcile.reconcile(
            listOf(ghost), probes, listOf(scan("s1", "renamed.mkv", 100)),
        )
        assertTrue(r.pairs.isEmpty())
        // different size: no pair
        r = Reconcile.reconcile(
            listOf(ghost), probes, listOf(scan("s1", "renamed.mp4", 101)),
        )
        assertTrue(r.pairs.isEmpty())
    }

    @Test
    fun pairRejectsZeroAndUnknownSizes() {
        val probes = mapOf("u-gone" to Reconcile.Probe(Reconcile.Verdict.MISSING))
        for ((gs, ss) in listOf(0L to 0L, -1L to -1L, 0L to 100L, 100L to -1L)) {
            val ghost = rec("g", "u-gone", "a.zip", gs)
            val r = Reconcile.reconcile(
                listOf(ghost), probes, listOf(scan("s1", "b.zip", ss)),
            )
            assertTrue("size $gs/$ss must not pair", r.pairs.isEmpty())
        }
    }

    @Test
    fun pairListsEveryCandidateWhenAmbiguous() {
        val ghost = rec("g", "u-gone", "a.zip", 50)
        val probes = mapOf("u-gone" to Reconcile.Probe(Reconcile.Verdict.MISSING))
        val r = Reconcile.reconcile(
            listOf(ghost), probes,
            listOf(scan("s1", "x.zip", 50), scan("s2", "y.zip", 50)),
        )
        // Both candidates are leads for the user to decide —
        // nothing is silently picked or dropped.
        assertEquals(setOf("s1", "s2"), r.pairs.map { it.scan.uri }.toSet())
    }

    @Test
    fun renameDetectedOnlyWhenExistsWithDifferentLiveName() {
        val records = listOf(
            rec("r1", "u1", "old.mp4", 10),
            rec("r2", "u2", "same.mp4", 10),
        )
        val probes = mapOf(
            "u1" to Reconcile.Probe(Reconcile.Verdict.EXISTS, "new.mp4", 10),
            "u2" to Reconcile.Probe(Reconcile.Verdict.EXISTS, "same.mp4", 10),
        )
        val r = Reconcile.reconcile(records, probes, emptyList())
        assertEquals(1, r.renames.size)
        assertEquals("r1", r.renames[0].record.id)
        assertEquals("new.mp4", r.renames[0].liveName)
    }

    @Test
    fun adoptedRecordFollowsStepACaliber() {
        val t = Reconcile.buildAdopted(scan("u9", "found.mkv", 777, mod = 4242))
        assertEquals("", t.url)
        assertEquals(TaskStatus.DONE, t.status)
        assertEquals("u9", t.savedUri)
        assertEquals("found.mkv", t.savedName)
        assertEquals("found.mkv", t.fileName)
        assertEquals(777L, t.totalSize)
        assertEquals(777L, t.downloaded)
        assertEquals(4242L, t.finishedAt)
    }
}

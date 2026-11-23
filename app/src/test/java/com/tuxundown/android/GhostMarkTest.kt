package com.tuxundown.android

import com.tuxundown.android.core.DownloadedFiles
import com.tuxundown.android.core.GhostMark
import com.tuxundown.android.core.Reconcile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v6.7.0 Step C: ghost marking — three-state split + totals. */
class GhostMarkTest {

    private fun rec(id: String, uri: String, size: Long = 10) =
        Reconcile.RecordSnap(id, uri, "$id.bin", size)

    private fun probe(v: Reconcile.Verdict) = Reconcile.Probe(v)

    @Test
    fun onlyMissingBecomesGhost() {
        val records = listOf(
            rec("gone", "u-gone"),
            rec("live", "u-live"),
            rec("unknown", "u-unknown"),
            rec("noprobe", "u-noprobe"),
        )
        val probes = mapOf(
            "u-gone" to probe(Reconcile.Verdict.MISSING),
            "u-live" to probe(Reconcile.Verdict.EXISTS),
            "u-unknown" to probe(Reconcile.Verdict.UNKNOWN),
        )
        val ghosts = GhostMark.ghostIds(records, probes)
        // UNKNOWN and no-verdict records are NOT ghosts:
        // 查不到不等于不在 — never marked, never disabled.
        assertEquals(setOf("gone"), ghosts)
        assertTrue(GhostMark.isGhost(records[0], probes))
        assertFalse(GhostMark.isGhost(records[2], probes))
        assertFalse(GhostMark.isGhost(records[3], probes))
    }

    @Test
    fun verdictIsKeyedByUriNotId() {
        // Two records pointing at the same Uri share its
        // verdict (the probe map is keyed by savedUri, exactly
        // as PublicStorage.probeRecords returns it).
        val records = listOf(rec("a", "u-same"), rec("b", "u-same"))
        val probes = mapOf("u-same" to probe(Reconcile.Verdict.MISSING))
        assertEquals(setOf("a", "b"), GhostMark.ghostIds(records, probes))
    }

    @Test
    fun emptyProbeMapMarksNothing() {
        val records = listOf(rec("a", "u1"), rec("b", "u2"))
        assertTrue(GhostMark.ghostIds(records, emptyMap()).isEmpty())
    }

    private fun entry(name: String, uri: String, size: Long, taskId: String) =
        DownloadedFiles.Entry(name, uri, size, 0, taskId)

    @Test
    fun totalsSplitPresentAndGhostBytes() {
        val entries = listOf(
            entry("live.bin", "u-live", 100, "live"),
            entry("gone.bin", "u-gone", 250, "gone"),
            entry("unclaimed.bin", "u-scan", 40, ""),
        )
        val t = GhostMark.splitTotals(entries, setOf("gone"))
        // Headline total counts only files that are present;
        // ghost bytes are stated separately.
        assertEquals(140L, t.presentBytes)
        assertEquals(2, t.presentCount)
        assertEquals(250L, t.ghostBytes)
        assertEquals(1, t.ghostCount)
    }

    @Test
    fun totalsUnclaimedNeverGhostAndUnknownSizeCountsZero() {
        val entries = listOf(
            // taskId "" is scan-only: even if "" somehow landed
            // in the ghost set it must stay in the present side.
            entry("scan.bin", "u-scan", 70, ""),
            entry("mystery.bin", "u-mystery", -1, "mystery"),
            entry("gone.bin", "u-gone", -1, "gone"),
        )
        val t = GhostMark.splitTotals(entries, setOf("", "gone"))
        assertEquals(70L, t.presentBytes)
        assertEquals(2, t.presentCount)
        assertEquals(0L, t.ghostBytes)
        assertEquals(1, t.ghostCount)
    }

    @Test
    fun totalsWithNoGhostsMatchPlainTotal() {
        val entries = listOf(
            entry("a.bin", "u-a", 10, "a"),
            entry("b.bin", "u-b", 20, "b"),
        )
        val t = GhostMark.splitTotals(entries, emptySet())
        assertEquals(DownloadedFiles.totalSize(entries), t.presentBytes)
        assertEquals(0L, t.ghostBytes)
        assertEquals(0, t.ghostCount)
    }
}

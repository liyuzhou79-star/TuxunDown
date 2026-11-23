package com.tuxundown.android.core

/**
 * v6.7.0 Step C: ghost marking on surfaces — pure logic.
 *
 * Input caliber (audit round 52, Step A — do not re-litigate):
 * - Records are [Reconcile.RecordSnap] (the same DONE-record
 *   snapshot Step B's reconciliation already builds).
 * - Probe verdicts are [Reconcile.Probe], keyed by the record's
 *   savedUri — exactly the map [PublicStorage.probeRecords]
 *   returns, so Step C reuses Step B's probe verbatim and never
 *   builds a second one.
 * - ONLY a MISSING verdict makes a ghost. EXISTS is alive;
 *   UNKNOWN (probe threw, SAF grant dead, tree unreadable) and
 *   a record with NO verdict at all are never ghosts: 查不到
 *   不等于不在. They are neither marked nor disabled anywhere.
 *
 * Output: the set of ghost TASK IDS (surfaces key on ids:
 * task cards, files-page entries and search file hits all
 * carry the owning task id).
 *
 * The files-page total split lives here too: the header total
 * counts only files that are present; ghost bytes are stated
 * separately, never silently folded into the headline number.
 */
object GhostMark {

    /** Ghost task ids: records whose probe verdict is MISSING. */
    fun ghostIds(
        records: List<Reconcile.RecordSnap>,
        probesByUri: Map<String, Reconcile.Probe>,
    ): Set<String> {
        val out = HashSet<String>()
        for (r in records) {
            if (probesByUri[r.savedUri]?.verdict == Reconcile.Verdict.MISSING) {
                out.add(r.id)
            }
        }
        return out
    }

    /** Is this one record a ghost under [probesByUri]? */
    fun isGhost(
        record: Reconcile.RecordSnap,
        probesByUri: Map<String, Reconcile.Probe>,
    ): Boolean =
        probesByUri[record.savedUri]?.verdict == Reconcile.Verdict.MISSING

    /**
     * Files-page header totals, split by ghost-ness.
     * [presentBytes] counts every entry that is NOT a ghost
     * (including unclaimed scan-only files, taskId ""); ghost
     * bytes are reported on their own line by the UI. Unknown
     * sizes (-1) count 0, same caliber as DownloadedFiles.
     */
    data class Totals(
        val presentBytes: Long,
        val presentCount: Int,
        val ghostBytes: Long,
        val ghostCount: Int,
    )

    fun splitTotals(
        entries: List<DownloadedFiles.Entry>,
        ghostIds: Set<String>,
    ): Totals {
        var presentBytes = 0L
        var presentCount = 0
        var ghostBytes = 0L
        var ghostCount = 0
        for (e in entries) {
            // An unclaimed file (taskId "") can never be a
            // ghost: there is no record claiming it is gone —
            // the scan just proved it is there.
            val ghost = e.taskId.isNotBlank() && e.taskId in ghostIds
            val bytes = e.size.coerceAtLeast(0)
            if (ghost) {
                ghostBytes += bytes
                ghostCount++
            } else {
                presentBytes += bytes
                presentCount++
            }
        }
        return Totals(presentBytes, presentCount, ghostBytes, ghostCount)
    }
}

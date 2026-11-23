package com.tuxundown.android.core

/**
 * v6.7.0 Step B: files-page reconciliation (一键对账) — pure logic.
 *
 * Why a separate file instead of folding into DownloadedFiles:
 * DownloadedFiles is the files page's LIST model (merge / sort /
 * total for display). Reconciliation is a different job — it
 * judges records against probe verdicts and a directory scan and
 * produces a REPORT the user acts on item by item. Keeping the
 * verdict logic here leaves DownloadedFiles' tested merge rules
 * untouched and lets every reconciliation caliber be unit-tested
 * with fake directory data, no Android types involved.
 *
 * Calibers (audit round 52, Step A — do not re-litigate):
 * - Existence is THREE-state. Only MISSING makes a ghost;
 *   UNKNOWN (probe threw, SAF grant dead, tree unreadable) is
 *   never a ghost — it is counted and reported separately.
 * - Unclaimed = a scan entry whose Uri NO record claims (any
 *   DONE record's savedUri; records are the claimants here
 *   because reconciliation runs over DONE records).
 * - A ghost x unclaimed pair is only a LEAD ("可能是同一个
 *   文件"): size exactly equal AND size > 0 AND extension
 *   equal. The ghost cannot be hashed (it is unreadable by
 *   definition), so nothing here ever proves identity and
 *   nothing is auto-merged — the user decides per pair.
 * - A MediaStore record that still EXISTS under a different
 *   live DISPLAY_NAME is a rename: offer a savedName refresh.
 * - NOTHING in this file disposes of anything. It only reports.
 */
object Reconcile {

    enum class Verdict { EXISTS, MISSING, UNKNOWN }

    /** One DONE record, as reconciliation sees it. */
    data class RecordSnap(
        val id: String,
        val savedUri: String,
        val savedName: String,
        /** Record's size (totalSize, or downloaded as fallback); <=0 = unknown. */
        val size: Long,
    )

    /** The probe's answer for one record Uri. [liveName] /
     *  [liveSize] are what the provider says NOW (MediaStore
     *  DISPLAY_NAME / SIZE); null when the probe cannot know. */
    data class Probe(
        val verdict: Verdict,
        val liveName: String? = null,
        val liveSize: Long? = null,
    )

    /** One finished file found by the directory scan. */
    data class ScanEntry(
        val uri: String,
        val name: String,
        val size: Long,
        val modifiedAt: Long,
    )

    /** A ghost record paired with an unclaimed file that MIGHT
     *  be the same file (see the header: a lead, never proof). */
    data class PossiblePair(val ghost: RecordSnap, val scan: ScanEntry)

    /** An existing record whose file now has a different name. */
    data class Rename(val record: RecordSnap, val liveName: String, val liveSize: Long?)

    data class Report(
        val ghosts: List<RecordSnap>,
        val unknownCount: Int,
        val unclaimed: List<ScanEntry>,
        val pairs: List<PossiblePair>,
        val renames: List<Rename>,
    ) {
        val isClean: Boolean
            get() = ghosts.isEmpty() && unclaimed.isEmpty() &&
                pairs.isEmpty() && renames.isEmpty()
    }

    /** File extension, lowercased, "" when the name has none. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * The one reconciliation pass. [probes] is keyed by record
     * savedUri; a record with NO probe entry counts as UNKNOWN
     * (nobody looked — that is not evidence the file is gone).
     */
    fun reconcile(
        records: List<RecordSnap>,
        probes: Map<String, Probe>,
        scan: List<ScanEntry>,
    ): Report {
        val ghosts = ArrayList<RecordSnap>()
        var unknown = 0
        val renames = ArrayList<Rename>()
        for (r in records) {
            val p = probes[r.savedUri]
            when (p?.verdict) {
                Verdict.MISSING -> ghosts.add(r)
                Verdict.EXISTS -> {
                    val live = p.liveName
                    if (!live.isNullOrBlank() && live != r.savedName) {
                        renames.add(Rename(r, live, p.liveSize))
                    }
                }
                else -> unknown++ // UNKNOWN verdict, or no probe at all
            }
        }
        val claimedUris = records.mapTo(HashSet()) { it.savedUri }
        val unclaimed = scan.filter { it.uri !in claimedUris }
        val pairs = ArrayList<PossiblePair>()
        for (g in ghosts) {
            for (s in unclaimed) {
                if (isPossiblePair(g, s)) pairs.add(PossiblePair(g, s))
            }
        }
        return Report(ghosts, unknown, unclaimed, pairs, renames)
    }

    /** The pair lead, exactly as Step A ruled it: sizes exactly
     *  equal, size > 0 (0 / -1 / unknown never pairs), and the
     *  extensions match. */
    fun isPossiblePair(ghost: RecordSnap, scan: ScanEntry): Boolean {
        if (ghost.size <= 0 || scan.size <= 0) return false
        if (ghost.size != scan.size) return false
        return extensionOf(ghost.savedName) == extensionOf(scan.name)
    }

    /**
     * The DONE record an adopted (认领) unclaimed file gets,
     * per Step A's caliber: no URL (there is none — the file
     * was found on disk, not downloaded through a link here),
     * sizes and finish time from the scan entry.
     */
    fun buildAdopted(scan: ScanEntry): TaskRecord {
        val t = TaskRecord(
            url = "",
            fileName = scan.name,
            totalSize = scan.size,
            status = TaskStatus.DONE,
            savedUri = scan.uri,
            savedName = scan.name,
            finishedAt = scan.modifiedAt,
            createdAt = if (scan.modifiedAt > 0) scan.modifiedAt else System.currentTimeMillis(),
        )
        t.downloaded = scan.size
        return t
    }
}

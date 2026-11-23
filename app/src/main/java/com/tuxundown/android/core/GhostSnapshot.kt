package com.tuxundown.android.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v6.7.0 Step C: the in-memory ghost-verdict snapshot.
 *
 * Why a standalone object (not a field on DownloadCenter):
 * DownloadCenter is the download registry (store, engines,
 * prefs) and carries no Compose state; this snapshot is a
 * pure UI-surface cache — the latest batched probe verdicts,
 * held as Compose snapshot state so task cards, the files
 * page and global search recompose the moment a refresh
 * lands. Keeping it separate also keeps DownloadCenter free
 * of Compose imports.
 *
 * Refresh timing — ONLY these three, all on background
 * threads, all batched over every DONE record at once:
 *  1. the task-list screen is entered (DownApp LaunchedEffect
 *     on the home screen);
 *  2. the files page loads (its existing background load
 *     probes alongside the scan and back-fills this snapshot);
 *  3. a reconciliation run finishes (it already probed —
 *     its verdicts are fed in here instead of probing twice).
 * The probe NEVER runs from the 1 s UI tick.
 *
 * The snapshot is memory-only and never persisted: after a
 * process restart it starts EMPTY (= nothing is marked)
 * until the first refresh completes. That is deliberate —
 * a stale on-disk verdict could mark a file that came back,
 * and an unmarked ghost is only a missed hint, while a
 * wrongly marked live file would disable working actions.
 * UNKNOWN verdicts are stored in [verdictsByUri] for honesty
 * but [ghostIds] only ever contains MISSING ids (see
 * GhostMark), so UNKNOWN is never marked and never disables
 * anything.
 */
object GhostSnapshot {

    /** Latest probe verdicts, keyed by savedUri. */
    @Volatile
    var verdictsByUri: Map<String, Reconcile.Probe> = emptyMap()
        private set

    /** Ghost task ids from the latest refresh (MISSING only). */
    var ghostIds: Set<String> by mutableStateOf(emptySet())
        private set

    fun isGhost(taskId: String): Boolean = taskId in ghostIds

    /** Feed one batched probe result in (any refresh source). */
    fun update(
        records: List<Reconcile.RecordSnap>,
        probesByUri: Map<String, Reconcile.Probe>,
    ) {
        verdictsByUri = probesByUri
        ghostIds = GhostMark.ghostIds(records, probesByUri)
    }

    // One batched probe at a time: the three refresh sources
    // can overlap (e.g. entering the files page right after
    // the list probe started); a second concurrent probe is
    // wasted work, and the first one's verdicts are just as
    // fresh. Callers that lose the race simply skip.
    @Volatile
    private var probing = false

    @Synchronized
    fun tryStartProbe(): Boolean {
        if (probing) return false
        probing = true
        return true
    }

    @Synchronized
    fun endProbe() {
        probing = false
    }
}

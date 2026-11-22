package com.tuxundown.android.core

/**
 * Orphan-.part scan grace window (audit round 28) — pure,
 * unit-tested.
 *
 * The orphan scan trusts the in-memory store's targetUri set,
 * but obtainTarget inserts the storage entry BEFORE the task's
 * targetUri is assigned (same call, a millisecond-scale window).
 * A scan landing inside that window would list a just-born .part
 * as an orphan, and a user deleting it would pull the file out
 * from under a live download. A .part touched within [GRACE_MS]
 * is therefore never reported: it is either that newborn, or a
 * file something is actively writing.
 *
 * An unknown age (modifiedMs <= 0 — a provider that reports no
 * timestamp) is NOT fresh: the scan keeps its pre-grace
 * behaviour there instead of hiding every orphan on such a
 * provider forever. A timestamp in the future (clock skew)
 * counts as fresh — erring towards never deleting a live file.
 */
object OrphanGrace {

    const val GRACE_MS = 60_000L

    fun isFresh(modifiedMs: Long, nowMs: Long): Boolean {
        if (modifiedMs <= 0) return false
        return nowMs - modifiedMs < GRACE_MS
    }
}

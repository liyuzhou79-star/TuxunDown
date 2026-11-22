package com.tuxundown.android.core

/**
 * Duplicate-file detection for the 已下载文件 page (v3.1).
 *
 * Two phases, because hashing gigabytes is real work:
 *  1. [sizeCandidates] — free: only files that share an EXACT
 *     known size with at least one other file can possibly be
 *     duplicates, so only those get read at all.
 *  2. [confirmedGroups] — the caller streams each candidate
 *     through SHA-256 (background, cancellable) and hands the
 *     uri -> hash map back here; files are duplicates only when
 *     size AND hash both match.
 *
 * This object never deletes anything and never guesses: an
 * unknown size (-1) proves nothing and is excluded, exactly
 * like the sniffer's duplicate rule. Pure — unit-tested.
 */
object DuplicateFinder {

    /** One confirmed duplicate set: same size, same SHA-256. */
    data class Group(
        val hash: String,
        /** Size of EACH copy in bytes (> 0). */
        val size: Long,
        val entries: List<DownloadedFiles.Entry>,
    ) {
        /** Bytes freed by deleting every copy but one. */
        val reclaimableBytes: Long
            get() = if (entries.size > 1) size * (entries.size - 1) else 0
    }

    /** Phase 1: the entries worth hashing (shared exact size). */
    fun sizeCandidates(entries: List<DownloadedFiles.Entry>): List<DownloadedFiles.Entry> =
        entries.filter { it.size > 0 }
            .groupBy { it.size }
            .values
            .filter { group -> group.size >= 2 }
            .flatten()

    /**
     * Phase 2: confirmed groups from the caller's hash results.
     * Candidates whose hash is missing (unreadable, cancelled)
     * simply take no part — no verdict is better than a wrong
     * one. Groups come back biggest-win first.
     */
    fun confirmedGroups(
        candidates: List<DownloadedFiles.Entry>,
        hashes: Map<String, String>,
    ): List<Group> =
        candidates
            .filter { it.size > 0 && hashes[it.uri] != null }
            .groupBy { it.size to hashes.getValue(it.uri) }
            .values
            .filter { members -> members.size >= 2 }
            .map { members ->
                Group(
                    hash = hashes.getValue(members.first().uri),
                    size = members.first().size,
                    entries = members.sortedBy { it.name.lowercase() },
                )
            }
            .sortedWith(
                compareByDescending<Group> { it.reclaimableBytes }
                    .thenBy { it.entries.first().name.lowercase() }
            )

    /** Total bytes recoverable by trimming every group to one copy. */
    fun totalReclaimable(groups: List<Group>): Long =
        groups.sumOf { it.reclaimableBytes }

    /**
     * Bytes the given selection would actually free, and how
     * many groups it would wipe out COMPLETELY (every copy
     * ticked — the confirm dialog names those, because after
     * the delete that file is simply gone).
     */
    data class DeletionPlan(val freedBytes: Long, val wipedGroups: Int)

    fun deletionPlan(groups: List<Group>, checkedUris: Set<String>): DeletionPlan {
        var freed = 0L
        var wiped = 0
        for (g in groups) {
            val ticked = g.entries.count { it.uri in checkedUris }
            if (ticked == 0) continue
            freed += g.size * ticked
            if (ticked == g.entries.size) wiped++
        }
        return DeletionPlan(freed, wiped)
    }
}

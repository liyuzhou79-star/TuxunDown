package com.tuxundown.android.core

/**
 * v3.2 audit round 18 (fault-injection matrix): does the ".part"
 * file on disk still hold the bytes the persisted progress says
 * it holds?
 *
 * The two seekable paths lay their bytes down differently, and
 * each layout gives one cheap, reliable consistency check:
 *
 * - Segmented: runSegmented pre-sizes the target with
 *   truncate(total) before the first worker writes, so a genuine
 *   part ALWAYS measures exactly totalSize once any chunk is
 *   marked done. A part that measures SHORTER has lost bytes —
 *   the file manager deleted its content, a storage cleaner
 *   truncated it in place, a same-name replacement slipped past
 *   the (already handled) vanished-Uri check. Trusting the chunk
 *   bitmap then would "complete" a file whose done regions are
 *   zeros: silent corruption reported as DONE.
 * - HLS: segments are appended strictly in playlist order, so a
 *   genuine part is AT LEAST as long as the written prefix
 *   (longer is fine — a segment write interrupted mid-way leaves
 *   its partial tail beyond the cursor, and the resume rewrites
 *   that segment at the cursor). A part SHORTER than the prefix
 *   has lost bytes the same way.
 *
 * An UNMEASURABLE part (size() threw — some SAF providers are
 * odd) never condemns: a measurement failure is not evidence of
 * lost bytes, and nuking a healthy multi-GB resume over it would
 * be its own bug. Same for "no progress recorded": there is
 * nothing to protect yet.
 *
 * Pure — unit-tested (PartIntegrityTest).
 */
object PartIntegrity {

    /**
     * True when the chunk bitmap may be trusted against a part
     * measuring [partSize] (-1 = unknown) for a file of
     * [totalSize] bytes.
     */
    fun segmentedTrustworthy(
        hasProgress: Boolean,
        partSize: Long,
        totalSize: Long,
    ): Boolean {
        if (!hasProgress) return true
        if (totalSize <= 0) return true
        if (partSize < 0) return true
        return partSize >= totalSize
    }

    /**
     * True when the merged-prefix state ([prefixBytes] = sum of
     * the sizes of the segments marked written) may be trusted
     * against a part measuring [partSize] (-1 = unknown).
     */
    fun hlsTrustworthy(prefixBytes: Long, partSize: Long): Boolean {
        if (prefixBytes <= 0) return true
        if (partSize < 0) return true
        return partSize >= prefixBytes
    }
}

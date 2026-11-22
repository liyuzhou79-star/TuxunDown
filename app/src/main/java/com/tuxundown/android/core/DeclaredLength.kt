package com.tuxundown.android.core

/**
 * v4.1 audit round 26 (dishonest servers): the verdicts that bind
 * a response BODY to the length the server DECLARED for it.
 *
 * The engine's paths used to trust different subsets of the
 * declarations: the segmented path bounded every write by the
 * requested range and judged completion by position (safe), but
 * the HLS segment fetch compared nothing at all — a segment body
 * that ended early was merged and marked written — and the
 * single-stream path let a chunked response run past the probed
 * total, then called the oversized file DONE on a ">=" check.
 *
 * The discipline matches PartIntegrity's: an UNDECLARED length
 * (Content-Length -1, chunked with no probed total) never
 * condemns — there is nothing to compare against, and inventing
 * a verdict would break honest servers that simply do not say.
 * A DECLARED length is a promise: a body that breaks it in
 * either direction is not the bytes that were promised.
 *
 * Pure — unit-tested (DeclaredLengthTest).
 */
object DeclaredLength {

    /**
     * True when a body read to EOF may be trusted against the
     * Content-Length declared for THIS response ([declared] < 0
     * = the server declared nothing, e.g. a chunked reply).
     * Both directions condemn: a short body is truncated data,
     * a long one is bytes that were never promised.
     */
    fun eofMatches(declared: Long, actual: Long): Boolean =
        declared < 0 || declared == actual

    /**
     * True when [actual] accumulated bytes have run past a
     * [knownTotal] learned earlier (probe / Content-Range /
     * SIZE). [knownTotal] <= 0 means the total was never known,
     * so nothing can exceed it.
     */
    fun exceedsKnownTotal(knownTotal: Long, actual: Long): Boolean =
        knownTotal > 0 && actual > knownTotal
}

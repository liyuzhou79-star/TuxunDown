package com.tuxundown.android.core

/**
 * Multi-source safety check (v1.6): an extra mirror URL may only
 * feed chunks into a download when it provably serves the SAME
 * file as the primary address. Two signals, both taken from the
 * servers themselves:
 *  - the total size must match exactly, and
 *  - a mid-file byte sample must match byte for byte (when both
 *    samples could be fetched — if a sample fetch fails, the exact
 *    size match alone decides; a sample MISMATCH always rejects).
 * Pure decision — unit-tested. The network probes live in the
 * engine, which refuses rejected sources and says so on the card.
 *
 * v5.0 (audit round 35): "the probe could not judge" is NOT the
 * same verdict as "the mirror provably serves a different file".
 * Since v4.7 a task's Cookie rides only same-host requests
 * (SessionScope), so a mirror on another host that needs the
 * session answers the size probe with a login page / 401 / no
 * Content-Range at all: probeTotal comes back -1 and the old
 * boolean read that as a size MISMATCH — the card then told the
 * user the mirror's file "对不上", when in truth nothing was
 * ever compared. [verdict] therefore separates the three
 * outcomes; only DIFFERENT is a proven fake, UNKNOWN is
 * "undecidable with the requests we are allowed to make" —
 * still never USED (unproven bytes must never mix in), but
 * reported honestly as unconfirmed, not as a mismatch.
 */
object SourceCheck {

    /** The three-way validation verdict (v5.0). */
    enum class Verdict {
        /** Provably the same file: use the mirror. */
        SAME,

        /** Provably a different file (size differs, or both
         *  samples were fetched and differ): refuse it as fake. */
        DIFFERENT,

        /** The probes could not decide (a total is unknown —
         *  e.g. the mirror would not answer a request that may
         *  not carry the task's session): never use it, but do
         *  not call it a fake either. */
        UNKNOWN,
    }

    fun verdict(
        totalPrimary: Long,
        totalCandidate: Long,
        samplePrimary: ByteArray?,
        sampleCandidate: ByteArray?,
    ): Verdict {
        // An unknown total on either side means no comparison
        // ever happened — undecidable, not a mismatch.
        if (totalPrimary <= 0 || totalCandidate <= 0) return Verdict.UNKNOWN
        if (totalCandidate != totalPrimary) return Verdict.DIFFERENT
        if (samplePrimary != null && sampleCandidate != null) {
            return if (samplePrimary.contentEquals(sampleCandidate)) {
                Verdict.SAME
            } else {
                Verdict.DIFFERENT
            }
        }
        // Exact size match, at least one sample unfetchable:
        // the size alone decides (standing v1.6 rule).
        return Verdict.SAME
    }

    fun isSameFile(
        totalPrimary: Long,
        totalCandidate: Long,
        samplePrimary: ByteArray?,
        sampleCandidate: ByteArray?,
    ): Boolean = verdict(
        totalPrimary, totalCandidate, samplePrimary, sampleCandidate,
    ) == Verdict.SAME
}

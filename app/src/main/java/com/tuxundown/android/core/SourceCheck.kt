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
 */
object SourceCheck {

    fun isSameFile(
        totalPrimary: Long,
        totalCandidate: Long,
        samplePrimary: ByteArray?,
        sampleCandidate: ByteArray?,
    ): Boolean {
        if (totalPrimary <= 0 || totalCandidate != totalPrimary) return false
        if (samplePrimary != null && sampleCandidate != null) {
            return samplePrimary.contentEquals(sampleCandidate)
        }
        return true
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.DeclaredLength
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1 audit round 26 (dishonest servers): a body is bound to
 * the length its server declared — in both directions — while
 * an undeclared length never condemns (the PartIntegrity
 * discipline: what cannot be measured is not evidence).
 */
class DeclaredLengthTest {

    @Test
    fun eofExactLengthMatches() {
        assertTrue(DeclaredLength.eofMatches(1_000, 1_000))
        assertTrue(DeclaredLength.eofMatches(0, 0))
    }

    @Test
    fun eofShortBodyCondemns() {
        // Segment / playlist body that ended before its
        // declared Content-Length: truncated data.
        assertFalse(DeclaredLength.eofMatches(1_000, 999))
        assertFalse(DeclaredLength.eofMatches(1_000, 0))
    }

    @Test
    fun eofLongBodyCondemns() {
        // More bytes than this response promised.
        assertFalse(DeclaredLength.eofMatches(1_000, 1_001))
    }

    @Test
    fun eofUndeclaredNeverCondemns() {
        // Chunked / no Content-Length: nothing to compare.
        assertTrue(DeclaredLength.eofMatches(-1, 0))
        assertTrue(DeclaredLength.eofMatches(-1, 123_456))
    }

    @Test
    fun exceedsKnownTotalOnlyPastTheTotal() {
        assertFalse(DeclaredLength.exceedsKnownTotal(1_000, 999))
        assertFalse(DeclaredLength.exceedsKnownTotal(1_000, 1_000))
        assertTrue(DeclaredLength.exceedsKnownTotal(1_000, 1_001))
    }

    @Test
    fun exceedsUnknownTotalNeverCondemns() {
        assertFalse(DeclaredLength.exceedsKnownTotal(0, 1_000_000))
        assertFalse(DeclaredLength.exceedsKnownTotal(-1, 1_000_000))
    }
}

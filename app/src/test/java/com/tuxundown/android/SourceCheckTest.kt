package com.tuxundown.android

import com.tuxundown.android.core.SourceCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.6: a mirror may only be used when it provably serves the
 *  same file — exact total size plus a matching byte sample. */
class SourceCheckTest {

    @Test
    fun sameSizeAndSameSamplePasses() {
        val s = byteArrayOf(1, 2, 3, 4)
        assertTrue(SourceCheck.isSameFile(1000, 1000, s, s.copyOf()))
    }

    @Test
    fun differentSizeIsRefused() {
        val s = byteArrayOf(1, 2, 3)
        assertFalse(SourceCheck.isSameFile(1000, 999, s, s))
        assertFalse(SourceCheck.isSameFile(1000, -1, s, s))
        assertFalse(SourceCheck.isSameFile(-1, -1, s, s))
    }

    @Test
    fun sameSizeButDifferentContentIsRefused() {
        assertFalse(
            SourceCheck.isSameFile(
                1000, 1000,
                byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 9),
            )
        )
    }

    // ---- v5.0 (round 35): the three-way verdict ----
    // "Could not judge" (a total never came back — e.g. the
    // mirror would not answer a request that may not carry the
    // task's session) must not be reported as a proven fake.

    @Test
    fun unknownTotalIsUndecidableNotFake() {
        val s = byteArrayOf(1, 2, 3)
        assertEquals(
            SourceCheck.Verdict.UNKNOWN,
            SourceCheck.verdict(1000, -1, s, s),
        )
        assertEquals(
            SourceCheck.Verdict.UNKNOWN,
            SourceCheck.verdict(1000, 0, s, null),
        )
        assertEquals(
            SourceCheck.Verdict.UNKNOWN,
            SourceCheck.verdict(-1, -1, s, s),
        )
        // Still never USED — undecidable only changes the
        // report, never the safety decision.
        assertFalse(SourceCheck.isSameFile(1000, -1, s, s))
    }

    @Test
    fun provenDifferencesAreDifferent() {
        val s = byteArrayOf(1, 2, 3)
        assertEquals(
            SourceCheck.Verdict.DIFFERENT,
            SourceCheck.verdict(1000, 999, s, s),
        )
        assertEquals(
            SourceCheck.Verdict.DIFFERENT,
            SourceCheck.verdict(
                1000, 1000, byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 9),
            ),
        )
    }

    @Test
    fun provenSameIsSame() {
        val s = byteArrayOf(1, 2, 3, 4)
        assertEquals(
            SourceCheck.Verdict.SAME,
            SourceCheck.verdict(1000, 1000, s, s.copyOf()),
        )
        // Size-only fallback stays SAME (standing v1.6 rule).
        assertEquals(
            SourceCheck.Verdict.SAME,
            SourceCheck.verdict(1000, 1000, null, null),
        )
    }

    @Test
    fun unfetchableSampleFallsBackToSizeOnly() {
        // One side's sample could not be fetched: the exact size
        // match alone decides (a fetched MISMATCH still refuses).
        assertTrue(SourceCheck.isSameFile(1000, 1000, null, byteArrayOf(1)))
        assertTrue(SourceCheck.isSameFile(1000, 1000, null, null))
    }
}

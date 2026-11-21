package com.tuxundown.android

import com.tuxundown.android.core.SourceCheck
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

    @Test
    fun unfetchableSampleFallsBackToSizeOnly() {
        // One side's sample could not be fetched: the exact size
        // match alone decides (a fetched MISMATCH still refuses).
        assertTrue(SourceCheck.isSameFile(1000, 1000, null, byteArrayOf(1)))
        assertTrue(SourceCheck.isSameFile(1000, 1000, null, null))
    }
}

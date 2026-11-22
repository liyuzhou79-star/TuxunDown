package com.tuxundown.android

import com.tuxundown.android.core.PartIntegrity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2 audit round 18: the ".part lost bytes behind our back"
 * verdicts. The segmented path pre-sizes its part to the full
 * total, the HLS path appends strictly in order — each layout
 * gets exactly one trustworthy length comparison, and an
 * unmeasurable part must never condemn a healthy resume.
 */
class PartIntegrityTest {

    @Test
    fun segmentedNoProgressAlwaysTrusts() {
        // Nothing marked done yet: there is nothing to protect,
        // whatever the part currently measures.
        assertTrue(PartIntegrity.segmentedTrustworthy(false, 0, 1_000_000))
        assertTrue(PartIntegrity.segmentedTrustworthy(false, -1, 1_000_000))
    }

    @Test
    fun segmentedFullLengthPartTrusts() {
        // The genuine article: pre-sized to the total by the
        // previous run's truncate.
        assertTrue(PartIntegrity.segmentedTrustworthy(true, 1_000_000, 1_000_000))
        // Longer cannot happen in practice; never condemn on it.
        assertTrue(PartIntegrity.segmentedTrustworthy(true, 1_000_001, 1_000_000))
    }

    @Test
    fun segmentedShortPartCondemns() {
        // Content deleted and recreated empty under the same Uri.
        assertFalse(PartIntegrity.segmentedTrustworthy(true, 0, 1_000_000))
        // Truncated in place by a storage cleaner.
        assertFalse(PartIntegrity.segmentedTrustworthy(true, 400_000, 1_000_000))
    }

    @Test
    fun segmentedUnknownsNeverCondemn() {
        // size() threw (odd SAF provider): not evidence of loss.
        assertTrue(PartIntegrity.segmentedTrustworthy(true, -1, 1_000_000))
        // No known total: nothing to compare against.
        assertTrue(PartIntegrity.segmentedTrustworthy(true, 0, -1))
        assertTrue(PartIntegrity.segmentedTrustworthy(true, 0, 0))
    }

    @Test
    fun hlsPrefixCoveredTrusts() {
        assertTrue(PartIntegrity.hlsTrustworthy(5_000, 5_000))
        // A segment write interrupted mid-way leaves a partial
        // tail beyond the cursor: longer is the normal shape of
        // an honest interrupted merge.
        assertTrue(PartIntegrity.hlsTrustworthy(5_000, 7_500))
    }

    @Test
    fun hlsShortPartCondemns() {
        assertFalse(PartIntegrity.hlsTrustworthy(5_000, 4_999))
        assertFalse(PartIntegrity.hlsTrustworthy(5_000, 0))
    }

    @Test
    fun hlsUnknownsNeverCondemn() {
        assertTrue(PartIntegrity.hlsTrustworthy(0, 0))
        assertTrue(PartIntegrity.hlsTrustworthy(5_000, -1))
    }
}

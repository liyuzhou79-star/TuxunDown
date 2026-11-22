package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.FtpSegmented
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v4.8: FTP segmentation's pure decisions — the entry gate
 * (decide) and the worker-count arithmetic (workerCount). The
 * socket paths themselves need a real server and stay
 * device-verified, like FtpClient.retrieve.
 */
class FtpSegmentedTest {

    // ---------- decide ----------

    @Test
    fun decideRejectsUnknownSize() {
        assertEquals(
            FtpSegmented.Block.SIZE_UNKNOWN,
            FtpSegmented.decide(totalKnown = false, restSupported = true, chunkCount = 10),
        )
        // SIZE is judged first: an unknown total blocks even
        // when REST also failed.
        assertEquals(
            FtpSegmented.Block.SIZE_UNKNOWN,
            FtpSegmented.decide(totalKnown = false, restSupported = false, chunkCount = 10),
        )
    }

    @Test
    fun decideRejectsUnsupportedRest() {
        assertEquals(
            FtpSegmented.Block.REST_UNSUPPORTED,
            FtpSegmented.decide(totalKnown = true, restSupported = false, chunkCount = 10),
        )
    }

    @Test
    fun decideRejectsTooFewChunks() {
        assertEquals(
            FtpSegmented.Block.TOO_FEW_CHUNKS,
            FtpSegmented.decide(totalKnown = true, restSupported = true, chunkCount = 1),
        )
        assertEquals(
            FtpSegmented.Block.TOO_FEW_CHUNKS,
            FtpSegmented.decide(totalKnown = true, restSupported = true, chunkCount = 0),
        )
    }

    @Test
    fun decideAllowsSegmentation() {
        assertNull(
            FtpSegmented.decide(totalKnown = true, restSupported = true, chunkCount = 2)
        )
        assertNull(
            FtpSegmented.decide(totalKnown = true, restSupported = true, chunkCount = 4000)
        )
    }

    // ---------- workerCount ----------

    @Test
    fun workerCountCeilingIsEight() {
        assertEquals(8, FtpSegmented.MAX_CONNECTIONS)
        // The engine's public alias is the same number (single
        // source: FtpSegmented).
        assertEquals(
            FtpSegmented.MAX_CONNECTIONS, DownloadEngine.FTP_MAX_CONNECTIONS
        )
        assertEquals(
            8,
            FtpSegmented.workerCount(
                allowance = Int.MAX_VALUE, taskThreads = 64, chunkCount = 10_000
            ),
        )
    }

    @Test
    fun workerCountBoundByAllowance() {
        assertEquals(
            3,
            FtpSegmented.workerCount(allowance = 3, taskThreads = 8, chunkCount = 100),
        )
        assertEquals(
            1,
            FtpSegmented.workerCount(allowance = 1, taskThreads = 8, chunkCount = 100),
        )
    }

    @Test
    fun workerCountNeverBelowOne() {
        // A zero/negative allowance is a ceiling on parallelism,
        // not a veto — worker 0 must still be able to run.
        assertEquals(
            1,
            FtpSegmented.workerCount(allowance = 0, taskThreads = 8, chunkCount = 100),
        )
        assertEquals(
            1,
            FtpSegmented.workerCount(allowance = -5, taskThreads = 8, chunkCount = 100),
        )
    }

    @Test
    fun workerCountBoundByTaskThreads() {
        assertEquals(
            2,
            FtpSegmented.workerCount(
                allowance = Int.MAX_VALUE, taskThreads = 2, chunkCount = 100
            ),
        )
        // Thread settings outside 1..8 clamp into the band.
        assertEquals(
            1,
            FtpSegmented.workerCount(
                allowance = Int.MAX_VALUE, taskThreads = 0, chunkCount = 100
            ),
        )
        assertEquals(
            8,
            FtpSegmented.workerCount(
                allowance = Int.MAX_VALUE, taskThreads = 100, chunkCount = 100
            ),
        )
    }

    @Test
    fun workerCountBoundByChunkCount() {
        assertEquals(
            2,
            FtpSegmented.workerCount(
                allowance = Int.MAX_VALUE, taskThreads = 8, chunkCount = 2
            ),
        )
        assertEquals(
            5,
            FtpSegmented.workerCount(
                allowance = Int.MAX_VALUE, taskThreads = 8, chunkCount = 5
            ),
        )
    }

    @Test
    fun workerCountNarrowestWins() {
        // allowance 4 < threads 6 < chunks 9 -> 4
        assertEquals(
            4,
            FtpSegmented.workerCount(allowance = 4, taskThreads = 6, chunkCount = 9),
        )
        // threads 3 < allowance 7 < chunks 9 -> 3
        assertEquals(
            3,
            FtpSegmented.workerCount(allowance = 7, taskThreads = 3, chunkCount = 9),
        )
    }
}

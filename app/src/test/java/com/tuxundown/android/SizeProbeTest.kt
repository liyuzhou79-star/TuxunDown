package com.tuxundown.android

import com.tuxundown.android.sniff.SizeProbe
import com.tuxundown.android.sniff.SniffSort
import com.tuxundown.android.sniff.SniffedResource
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.6: size-probe pure helpers + the by-size sort order. */
class SizeProbeTest {

    @Test
    fun contentRangeTotalIsParsed() {
        assertEquals(271_440_852L, SizeProbe.totalFromContentRange("bytes 0-0/271440852"))
        assertEquals(-1L, SizeProbe.totalFromContentRange("bytes 0-0/*"))
        assertEquals(-1L, SizeProbe.totalFromContentRange(null))
        assertEquals(-1L, SizeProbe.totalFromContentRange(""))
        assertEquals(-1L, SizeProbe.totalFromContentRange("bytes 0-0/abc"))
    }

    @Test
    fun pickSizePrefersRangeTotal() {
        assertEquals(500L, SizeProbe.pickSize(headLength = 300, rangeTotal = 500))
        assertEquals(300L, SizeProbe.pickSize(headLength = 300, rangeTotal = -1))
        assertEquals(-1L, SizeProbe.pickSize(headLength = -1, rangeTotal = -1))
        assertEquals(-1L, SizeProbe.pickSize(headLength = 0, rangeTotal = 0))
    }

    @Test
    fun bySizeDescPutsKnownBigFirstAndUnknownLast() {
        fun res(name: String, size: Long) =
            SniffedResource(url = "https://x/$name", name = name, kind = "other", size = size)
        val sorted = SniffSort.bySizeDesc(
            listOf(
                res("unknown.bin", -1),
                res("small.bin", 10),
                res("big.bin", 9_999),
                res("mid.bin", 500),
            )
        )
        assertEquals(
            listOf("big.bin", "mid.bin", "small.bin", "unknown.bin"),
            sorted.map { it.name },
        )
    }
}

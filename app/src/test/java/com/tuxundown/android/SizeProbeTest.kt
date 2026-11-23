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
    fun remainingMsNeverGoesNegative() {
        // v4.3: the overall probeAll budget arithmetic — time
        // left shrinks to zero at the deadline and stays there.
        assertEquals(5_000L, SizeProbe.remainingMs(deadlineMs = 10_000, nowMs = 5_000))
        assertEquals(0L, SizeProbe.remainingMs(deadlineMs = 10_000, nowMs = 10_000))
        assertEquals(0L, SizeProbe.remainingMs(deadlineMs = 10_000, nowMs = 99_000))
    }

    @Test
    fun hostSlotsCapPerHostAndReleaseFrees() {
        // v5.7 audit 42 (item 4): at most MAX_PER_HOST probes in
        // flight per host; a release hands the slot back.
        val slots = SizeProbe.HostSlots()
        repeat(SizeProbe.MAX_PER_HOST) {
            assertEquals(true, slots.tryAcquire("example.com"))
        }
        assertEquals(false, slots.tryAcquire("example.com"))
        assertEquals(SizeProbe.MAX_PER_HOST, slots.inFlight("example.com"))
        slots.release("example.com")
        assertEquals(true, slots.tryAcquire("example.com"))
    }

    @Test
    fun hostSlotsAreIndependentPerHost() {
        val slots = SizeProbe.HostSlots(maxPerHost = 1)
        assertEquals(true, slots.tryAcquire("a.example.com"))
        assertEquals(false, slots.tryAcquire("a.example.com"))
        // A different host has its own slot row.
        assertEquals(true, slots.tryAcquire("b.example.com"))
        assertEquals(1, slots.inFlight("a.example.com"))
        assertEquals(1, slots.inFlight("b.example.com"))
    }

    @Test
    fun hostSlotsReleaseNeverGoesNegative() {
        val slots = SizeProbe.HostSlots(maxPerHost = 1)
        slots.release("never-acquired.example.com")
        assertEquals(0, slots.inFlight("never-acquired.example.com"))
        assertEquals(true, slots.tryAcquire("never-acquired.example.com"))
        slots.release("never-acquired.example.com")
        slots.release("never-acquired.example.com")
        assertEquals(0, slots.inFlight("never-acquired.example.com"))
        assertEquals(true, slots.tryAcquire("never-acquired.example.com"))
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

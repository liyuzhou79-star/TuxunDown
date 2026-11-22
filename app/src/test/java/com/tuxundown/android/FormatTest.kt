package com.tuxundown.android

import com.tuxundown.android.core.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun speedUnits() {
        assertEquals("0 B/秒", Format.speed(0))
        assertEquals("512 B/秒", Format.speed(512))
        assertEquals("1.5 KB/秒", Format.speed(1536))
        assertEquals("5.0 MB/秒", Format.speed(5L * 1024 * 1024))
        assertEquals("1.50 GB/秒", Format.speed(1536L * 1024 * 1024))
        assertEquals("—", Format.speed(-1))
    }

    @Test
    fun sizeUnits() {
        assertEquals("未知大小", Format.size(-1))
        assertEquals("0 B", Format.size(0))
        assertEquals("2.0 KB", Format.size(2048))
        assertEquals("1.5 MB", Format.size(1536L * 1024))
        assertEquals("1.50 GB", Format.size(1536L * 1024 * 1024))
    }

    @Test
    fun etaWording() {
        assertEquals("", Format.eta(-1))
        assertEquals("大约 20 秒", Format.eta(20))
        assertEquals("大约 3 分钟", Format.eta(150))
        assertEquals("大约 1 小时", Format.eta(3600))
        assertEquals("大约 1 小时 5 分钟", Format.eta(3600 + 270))
        assertEquals("大约 2 小时", Format.eta(3600 + 3599))
    }

    @Test
    fun chartCeilingIsNiceNumber() {
        assertEquals(1024L, Format.chartCeiling(0))
        assertEquals(1024L, Format.chartCeiling(500))
        // 3 MB/s * 1.1 = 3.3 MB/s -> next power-of-two step is 4 MB/s.
        assertEquals(4L * 1024 * 1024, Format.chartCeiling(3L * 1024 * 1024))
        // 9 MB/s * 1.1 = 9.9 MB/s -> 16 MB/s.
        assertEquals(16L * 1024 * 1024, Format.chartCeiling(9L * 1024 * 1024))
        // 12 MB/s * 1.1 = 13.2 MB/s -> 16 MB/s.
        assertEquals(16L * 1024 * 1024, Format.chartCeiling(12L * 1024 * 1024))
        // The labelled ceiling always formats back to a clean value.
        assertEquals("16.0 MB/秒", Format.speed(Format.chartCeiling(12L * 1024 * 1024)))
    }

    @Test
    fun percentClamps() {
        assertEquals(0, Format.percent(-0.5f))
        assertEquals(47, Format.percent(0.47f))
        assertEquals(100, Format.percent(1.5f))
    }

    @Test
    fun ratioOneDecimal() {
        assertEquals("1.0", Format.ratio(1.0))
        assertEquals("3.2", Format.ratio(3.24))
        assertEquals("12.8", Format.ratio(12.75))
    }
}

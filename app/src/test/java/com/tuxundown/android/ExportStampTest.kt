package com.tuxundown.android

import com.tuxundown.android.core.ExportStamp
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v6.4 (audit round 49 step B): the ONE export timestamp
 * caliber — local wall-clock time plus the zone's ACTUAL
 * offset at that instant, in exactly one shape. Every epoch
 * below was computed independently of the implementation
 * (from the zone rules), so a wrong offset or a wrong field
 * fails here instead of shipping in an export.
 */
class ExportStampTest {

    @Test
    fun plusEightZone() {
        // 2026-11-23 15:04:05 in Asia/Taipei (no DST, UTC+08:00).
        assertEquals(
            "2026-11-23 15:04:05 (UTC+08:00)",
            ExportStamp.format(1795417445000L, TimeZone.getTimeZone("Asia/Taipei")),
        )
    }

    @Test
    fun newYorkWinterIsMinusFive() {
        // 2026-01-15 12:00:00 EST.
        assertEquals(
            "2026-01-15 12:00:00 (UTC-05:00)",
            ExportStamp.format(1768496400000L, TimeZone.getTimeZone("America/New_York")),
        )
    }

    @Test
    fun newYorkSummerIsMinusFour() {
        // Same wall clock as the winter case, 2026-07-15, but
        // daylight saving moves the actual offset to UTC-04:00.
        assertEquals(
            "2026-07-15 12:00:00 (UTC-04:00)",
            ExportStamp.format(1784131200000L, TimeZone.getTimeZone("America/New_York")),
        )
    }

    @Test
    fun halfHourOffsetKeepsItsMinutes() {
        // 2026-11-23 15:04:05 in Asia/Kolkata (UTC+05:30).
        assertEquals(
            "2026-11-23 15:04:05 (UTC+05:30)",
            ExportStamp.format(1795426445000L, TimeZone.getTimeZone("Asia/Kolkata")),
        )
    }

    @Test
    fun threeQuarterHourOffsetKeepsItsMinutes() {
        // 2026-11-23 15:04:05 in Asia/Kathmandu (UTC+05:45).
        assertEquals(
            "2026-11-23 15:04:05 (UTC+05:45)",
            ExportStamp.format(1795425545000L, TimeZone.getTimeZone("Asia/Kathmandu")),
        )
    }

    @Test
    fun negativeHalfHourOffsetKeepsSignAndMinutes() {
        // 2026-01-15 12:00:00 in America/St_Johns (UTC-03:30).
        assertEquals(
            "2026-01-15 12:00:00 (UTC-03:30)",
            ExportStamp.format(1768491000000L, TimeZone.getTimeZone("America/St_Johns")),
        )
    }

    @Test
    fun zeroOffsetIsAlwaysWrittenAsPlusZero() {
        // The pinned zero caliber: "UTC+00:00", never bare "UTC".
        assertEquals(
            "1970-01-01 00:00:00 (UTC+00:00)",
            ExportStamp.format(0L, TimeZone.getTimeZone("UTC")),
        )
        assertEquals(
            "2026-11-23 15:04:05 (UTC+00:00)",
            ExportStamp.format(1795446245000L, TimeZone.getTimeZone("UTC")),
        )
    }

    @Test
    fun sameInstantReadsDifferentlyInDifferentZones() {
        // One instant, two wall clocks: the stamp must say
        // which zone its wall clock belongs to.
        val instant = 1795417445000L // 2026-11-23 07:04:05 UTC
        assertEquals(
            "2026-11-23 15:04:05 (UTC+08:00)",
            ExportStamp.format(instant, TimeZone.getTimeZone("Asia/Taipei")),
        )
        assertEquals(
            "2026-11-23 07:04:05 (UTC+00:00)",
            ExportStamp.format(instant, TimeZone.getTimeZone("UTC")),
        )
    }
}

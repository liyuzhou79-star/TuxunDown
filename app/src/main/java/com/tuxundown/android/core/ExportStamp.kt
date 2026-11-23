package com.tuxundown.android.core

import java.util.Calendar
import java.util.TimeZone

/**
 * Export timestamp caliber (v6.4, audit round 49 step B) — the
 * ONE formatter every exported / copyable text uses for an
 * absolute generation time.
 *
 * Shape (fixed, pinned by ExportStampTest):
 *   "2026-11-23 15:04:05 (UTC+08:00)"
 *
 * - The wall-clock fields are read in [zone] (default: the
 *   device zone at call time).
 * - The offset label is the zone's ACTUAL offset at that
 *   instant — [TimeZone.getOffset] already includes daylight
 *   saving, so America/New_York reads UTC-05:00 in winter and
 *   UTC-04:00 in summer for the same wall-clock time.
 * - Half-hour and three-quarter-hour offsets keep their
 *   minutes (UTC+05:30, UTC+05:45); negative offsets carry
 *   the minus sign (UTC-03:30).
 * - A zero offset is ALWAYS written "UTC+00:00" — never the
 *   bare word "UTC" — so every stamp has exactly one shape.
 *
 * Pure: the instant and the zone are both inputs, so tests
 * pin DST and cross-zone cases with fixed zones.
 */
object ExportStamp {

    fun format(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        val offsetMs = zone.getOffset(epochMs)
        val sign = if (offsetMs < 0) '-' else '+'
        val absMs = if (offsetMs < 0) -offsetMs else offsetMs
        val offsetHours = absMs / 3_600_000
        val offsetMinutes = (absMs % 3_600_000) / 60_000
        return "%04d-%02d-%02d %02d:%02d:%02d (UTC%c%02d:%02d)".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            sign,
            offsetHours,
            offsetMinutes,
        )
    }
}

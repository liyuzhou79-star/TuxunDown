package com.tuxundown.android.core

import java.util.Calendar
import java.util.TimeZone

/**
 * Notification quiet hours (v2.5): a daily window during
 * which download completions still POST their notification
 * (with its 打开 / 分享 actions) but the phone neither
 * vibrates nor rings for them. The window is expressed in
 * minutes of day and may wrap midnight (23:00 -> 07:00 is
 * the default shape). Pure — unit-tested.
 */
object QuietHours {

    const val DEFAULT_START_MIN = 23 * 60
    const val DEFAULT_END_MIN = 7 * 60

    /**
     * Minutes since local midnight for [epochMs], read in
     * [zone] (v6.4 audit round 49: injectable, default = the
     * device zone at call time — the window is judged LIVE in
     * the zone in force at each tick, so a zone change or a
     * DST shift re-reads the window against the new wall clock
     * on the very next judgement; nothing is cached).
     */
    fun minuteOfDay(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** "HH:MM" for a minute-of-day value (clamped). */
    fun formatMin(min: Int): String {
        val m = min.coerceIn(0, 1439)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * Is [nowMin] inside the window [startMin, endMin)?
     * start < end is a plain daytime window; start > end
     * wraps midnight. start == end is an EMPTY window
     * (never quiet) — a zero-length setting must not silence
     * the whole day.
     */
    fun isQuiet(startMin: Int, endMin: Int, nowMin: Int): Boolean {
        val s = startMin.coerceIn(0, 1439)
        val e = endMin.coerceIn(0, 1439)
        val n = nowMin.coerceIn(0, 1439)
        if (s == e) return false
        return if (s < e) {
            n in s until e
        } else {
            n >= s || n < e
        }
    }

    fun isQuietAt(
        startMin: Int, endMin: Int, epochMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Boolean =
        isQuiet(startMin, endMin, minuteOfDay(epochMs, zone))
}

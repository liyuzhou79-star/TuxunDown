package com.tuxundown.android.core

import java.util.Calendar

/**
 * Wall-clock math for scheduled downloads (v1.4 one-shot delays,
 * v1.8 daily recurrence). Every function takes "now" explicitly,
 * so the whole object is unit-testable without the real clock.
 * Calendar arithmetic (never +24 h in milliseconds) keeps the
 * wall-clock time stable across daylight-saving changes.
 */
object ScheduleTime {

    /** Parse an "HH:MM" wall-clock string; null when malformed. */
    fun parseHhMm(text: String): Pair<Int, Int>? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /** Format an epoch ms as "HH:MM" in local time. */
    fun formatHhMm(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%02d:%02d".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    /**
     * The next moment the wall clock reads hour:minute strictly
     * after [nowMs]: today when that time is still ahead, else
     * tomorrow.
     */
    fun nextOccurrence(hour: Int, minute: Int, nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /**
     * Advance a daily slot to the same wall-clock time on the next
     * day(s) until it is strictly after [nowMs] — a phone that was
     * off for two days lands on the next FUTURE slot, never a past
     * one (which would fire immediately in a burst).
     */
    fun advanceDaily(previousMs: Long, nowMs: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = previousMs }
        while (cal.timeInMillis <= nowMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}

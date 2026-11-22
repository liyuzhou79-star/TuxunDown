package com.tuxundown.android.core

/**
 * v3.0: the scheduled global speed limit — ONE daily window
 * in which a different global limit applies. The headline
 * shape is "unlimited at night, capped in the day" (so
 * downloads never eat the whole household line at dinner
 * time), but both numbers are the user's: the window档 can
 * be any limit including 0 = unlimited, and outside the
 * window the standing 全局限速 rules untouched.
 *
 * The window math is [QuietHours]': minutes of day, may
 * wrap midnight, start == end is an EMPTY window (a
 * zero-length plan must not silently become an all-day
 * one). This is a separate setting from notification
 * quiet hours — the two windows neither share state nor
 * imply each other.
 *
 * Pure — unit-tested. The live composition point is
 * DownloadCenter.effectiveSpeedLimitKBps, which the token
 * bucket reads on every tick, so crossing the window edge
 * takes effect on running downloads without a restart.
 */
object SpeedSchedule {

    const val DEFAULT_START_MIN = 23 * 60
    const val DEFAULT_END_MIN = 7 * 60

    fun inWindow(startMin: Int, endMin: Int, nowMin: Int): Boolean =
        QuietHours.isQuiet(startMin, endMin, nowMin)

    /**
     * The global limit (KB/s, 0 = unlimited) in force at
     * [nowMin]: the schedule's window limit when the plan
     * is enabled and [nowMin] is inside the window, else
     * the standing default limit.
     */
    fun effectiveLimitKBps(
        enabled: Boolean,
        startMin: Int,
        endMin: Int,
        windowLimitKBps: Int,
        defaultLimitKBps: Int,
        nowMin: Int,
    ): Int =
        if (enabled && inWindow(startMin, endMin, nowMin)) {
            windowLimitKBps.coerceAtLeast(0)
        } else {
            defaultLimitKBps.coerceAtLeast(0)
        }

    /** [effectiveLimitKBps] against a wall-clock timestamp. */
    fun effectiveLimitKBpsAt(
        enabled: Boolean,
        startMin: Int,
        endMin: Int,
        windowLimitKBps: Int,
        defaultLimitKBps: Int,
        epochMs: Long,
    ): Int = effectiveLimitKBps(
        enabled, startMin, endMin, windowLimitKBps, defaultLimitKBps,
        QuietHours.minuteOfDay(epochMs),
    )
}

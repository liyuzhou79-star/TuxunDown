package com.tuxundown.android.core

import java.util.Calendar
import java.util.TimeZone

/**
 * Wall-clock math for scheduled downloads (v1.4 one-shot delays,
 * v1.8 daily recurrence). Every function takes "now" explicitly,
 * so the whole object is unit-testable without the real clock.
 * Calendar arithmetic (never +24 h in milliseconds) keeps the
 * wall-clock time stable across daylight-saving changes.
 *
 * v6.4 (audit round 49): every function takes an injectable
 * [TimeZone] (default = the device zone at call time, so all
 * existing call sites behave exactly as before). The zone is a
 * parameter — never cached — because a zone CHANGE is exactly
 * the event this round audits: a daily slot must be re-anchored
 * from its stored HH:MM in the zone in force NOW (see
 * [nextDailyAt]), not carried over from the zone in force when
 * the slot's epoch was computed.
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

    /** Format an epoch ms as "HH:MM" in [zone]. */
    fun formatHhMm(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return "%02d:%02d".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    /**
     * The next moment the wall clock reads hour:minute strictly
     * after [nowMs]: today when that time is still ahead, else
     * tomorrow. [zone] decides which wall clock — the fields are
     * set on a Calendar in that zone.
     *
     * DST caliber (java.util.Calendar's lenient field
     * resolution, pinned by ScheduleTimeTest from CI evidence):
     * a wall time that does not exist (spring-forward gap, e.g.
     * 02:30 on the day clocks jump 02:00 -> 03:00) resolves
     * BACKWARD by the gap (02:30 lands at 01:30 local standard
     * time — the slot fires an hour early that one day only);
     * a wall time that occurs twice (fall-back) resolves to
     * its FIRST occurrence (the daylight-saving one).
     */
    fun nextOccurrence(
        hour: Int, minute: Int, nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val cal = Calendar.getInstance(zone).apply {
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
     *
     * The wall-clock time is read from [previousMs] IN [zone]:
     * after a zone change this keeps the old epoch's HH:MM as
     * read in the NEW zone, which is NOT the time the user
     * picked (Taipei 23:30 carried to a UTC zone keeps firing
     * at 15:30 local). That is why records that carry a stored
     * HH:MM (v6.4+) reschedule through [nextDailyAt] instead;
     * this function remains the path for legacy records whose
     * picked time was never stored and cannot be recovered.
     */
    fun advanceDaily(
        previousMs: Long, nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = previousMs }
        while (cal.timeInMillis <= nowMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * v6.4: re-anchor a daily slot from its stored wall-clock
     * [hour]:[minute] in the CURRENT [zone] — the next moment
     * strictly after [nowMs] at which the wall clock in [zone]
     * reads that time. A task created for 23:30 in Taipei keeps
     * firing at 23:30 local after the phone moves to a UTC zone,
     * instead of drifting to whatever the old epoch reads as
     * there. Same computation as [nextOccurrence]; the separate
     * name marks the reschedule call site's intent (re-anchor
     * from the stored pick, never from a stale epoch).
     */
    fun nextDailyAt(
        hour: Int, minute: Int, nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long = nextOccurrence(hour, minute, nowMs, zone)

    /**
     * v6.4 step C: re-anchor, IN PLACE, every QUEUED daily task
     * that carries a stored HH:MM pick to that same wall-clock
     * time in [zone] — the fix for the gap step A left open:
     * step A re-anchors at the next RESCHEDULE, but a slot that
     * was already computed in the old zone keeps its drifted
     * epoch until then, so the first fire after a zone change
     * would land at the wrong local time. Called by
     * BootReceiver the moment the system reports a zone or
     * wall-clock change. Returns how many tasks changed, so
     * the caller persists only when something actually moved.
     *
     * The caliber is deliberately narrow — everything else is
     * left exactly as stored:
     * - only QUEUED tasks: a task that already started, is
     *   paused, or is finished has its own lifecycle, and a
     *   paused daily task re-anchors when it is resumed and
     *   rescheduled through the step-A path;
     * - only daily tasks WITH a valid stored pick
     *   (scheduleHour in 0..23, scheduleMinute in 0..59):
     *   one-shot tasks are pure epoch semantics — they fire
     *   exactly once at the stored instant whatever the zone
     *   says — and a legacy daily task without a pick has no
     *   recoverable intent to re-anchor FROM (its epoch's
     *   reading in the new zone is all that survives; see
     *   [advanceDaily]);
     * - a task whose stored slot already equals the re-anchored
     *   value is not counted and not touched.
     *
     * Because [nextDailyAt] is strictly after [nowMs], a clock
     * set FORWARD past the old slot lands on the next future
     * slot (the task is not dragged into the past to misfire
     * at once), and a clock set BACKWARD likewise yields a
     * future slot — never a past one.
     */
    fun reanchorQueuedDaily(
        tasks: List<TaskRecord>, nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Int {
        var changed = 0
        for (t in tasks) {
            if (t.status != TaskStatus.QUEUED || !t.scheduleDaily) continue
            if (t.scheduleHour !in 0..23 || t.scheduleMinute !in 0..59) continue
            val next = nextDailyAt(t.scheduleHour, t.scheduleMinute, nowMs, zone)
            if (next != t.scheduledAt) {
                t.scheduledAt = next
                changed++
            }
        }
        return changed
    }
}

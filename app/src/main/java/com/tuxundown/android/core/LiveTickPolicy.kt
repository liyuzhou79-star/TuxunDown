package com.tuxundown.android.core

/**
 * v7.6 (audit round 61, Step B-1/B-5): the UI live tick's
 * activity grade and per-card tick policy — pure decisions,
 * unit-tested; MainActivity only executes them.
 *
 * Before this round the foreground tick incremented every
 * 1 s with NO activity condition: zero tasks, all DONE /
 * PAUSED, a settings page — all ticked, and because DownApp
 * reads the tick at its top level, every tick re-ran the
 * whole screen scope. The engine / service samplers never
 * read this tick (they run on their own threads), so grading
 * it is UI-only and cannot slow a download.
 *
 * The grade (judged on the task list each beat):
 * - FAST (1 s) while any card is LIVE — a RUNNING task, a
 *   task in the finalize window ([Finalizing.isFinalizing]),
 *   or a task whose checksum is being verified right now
 *   ([Checksum.STATE_VERIFYING]). These are the fields that
 *   truly change per second: sampler speed / ETA / bytes,
 *   the finalizing card's flip back to 已完成 the moment
 *   savedUri lands, and the 正在校验…N% progress note the
 *   verifier publishes in place on the record. Keeping them
 *   at 1 s is deliberate — see the field audit in Step B's
 *   report; nothing here is an accepted degradation.
 * - SLOW (5 s) while no card is live but a QUEUED task
 *   exists: queue positions, the 预约 HH:MM 开始 wording and
 *   its flip when the slot arrives, and the pre-check
 *   warning flag all refresh on this beat — none of them
 *   carries per-second information (the schedule wording is
 *   an absolute clock time, not a countdown).
 * - IDLE (no increment at all) when nothing is active:
 *   every remaining card field is a pure function of the
 *   record, and a record in this scene can only change via
 *   a user action (which recomposes on its own) or via the
 *   live states above (which re-grade the tick FAST first).
 *   PAUSED cards carry NO countdown text at all — their
 *   lines are downloaded bytes / average / peak, all frozen
 *   while paused — so nothing is lost by not ticking.
 *
 * Per-card tick (B-5): only LIVE cards receive the changing
 * tick value; every other card receives a constant, so a
 * 1 s beat no longer recomposes PAUSED / DONE / QUEUED
 * cards whose fields cannot have changed. A status flip on
 * a constant-tick card still redraws: DownApp's top level
 * reads the tick itself and re-runs on every graded beat
 * (and on the ON_START immediate tick), re-deriving the
 * displayed list and each card's parameters — the flip is
 * picked up there, on the grade's beat at the latest.
 */
object LiveTickPolicy {

    enum class Grade { FAST, SLOW, IDLE }

    const val FAST_MS = 1_000L
    const val SLOW_MS = 5_000L

    /** A card whose fields genuinely move while the user
     *  watches: transferring, finalizing, or being verified. */
    fun isLiveCard(task: TaskRecord): Boolean =
        task.status == TaskStatus.RUNNING ||
            Finalizing.isFinalizing(task) ||
            task.checksumState == Checksum.STATE_VERIFYING

    /** The tick grade for the whole task list right now. */
    fun grade(tasks: List<TaskRecord>): Grade {
        var anyQueued = false
        for (t in tasks) {
            if (isLiveCard(t)) return Grade.FAST
            if (t.status == TaskStatus.QUEUED) anyQueued = true
        }
        return if (anyQueued) Grade.SLOW else Grade.IDLE
    }

    /** Beat length for [grade]; IDLE has no beat (0). */
    fun delayMs(grade: Grade): Long = when (grade) {
        Grade.FAST -> FAST_MS
        Grade.SLOW -> SLOW_MS
        Grade.IDLE -> 0L
    }

    /** The liveTick value a card for [task] should receive:
     *  the changing [tick] for live cards, a constant for
     *  every other card (see the class doc). */
    fun cardTick(task: TaskRecord, tick: Int): Int =
        if (isLiveCard(task)) tick else 0
}

package com.tuxundown.android.core

/**
 * Whole-task automatic retry policy (v1.4): after a task FAILS, the
 * service may re-run it a limited number of times with an
 * exponential backoff, so a flaky server or a brief network drop
 * does not leave the user with a dead task. Pure — unit-tested.
 */
object RetryPolicy {

    /** [used] = retries already spent on this failure streak. */
    fun shouldRetry(used: Int, limit: Int): Boolean = limit > 0 && used < limit

    /** Backoff before retry #[used] (0-based): 10s, 30s, 90s, then 3 min cap. */
    fun backoffMs(used: Int): Long = when (used.coerceAtLeast(0)) {
        0 -> 10_000
        1 -> 30_000
        2 -> 90_000
        else -> 180_000
    }

    /**
     * v5.8: the whole-task backoff actually slept on — [backoffMs]
     * spread by [BackoffJitter] so tasks that failed in the same
     * instant (a server hiccup, a brief network drop) do not all
     * retry in the same instant too. The result is always inside
     * [backoffMs(used) / 2, backoffMs(used)], so the 3 min cap and
     * the worst-case total wait (10+30+90+180 = 310 s for the
     * first four retries) are unchanged — jitter only shortens.
     */
    fun backoffMsJittered(used: Int, random: () -> Double): Long =
        BackoffJitter.apply(backoffMs(used), random)

    /** Production form: a real random source. */
    fun backoffMsJittered(used: Int): Long =
        BackoffJitter.apply(backoffMs(used))

    /**
     * Audit round 28: failures only the USER can fix — the
     * custom (SAF) folder's grant expired or became unwritable
     * (re-pick the folder in settings), or the storage device
     * went away / turned read-only mid-run. Auto-retrying them
     * cannot succeed: every attempt fails identically and the
     * retry notes bury the real reason.
     *
     * v4.5 (audit round 30): the judgement reads the
     * STRUCTURED verdict recorded at the failure site (see
     * FailureKind), not the error text — the round-28 form
     * matched contains() needles over Errors.human's
     * sentences, so any wording edit could silently turn
     * this gate off (auto-retry grinding a hopeless task)
     * or on (a retryable task never retrying). The text
     * form below survives only as the fallback for
     * verdict-less records written before v4.5, via
     * FailureClassifier.
     */
    fun needsUserAction(kind: FailureKind): Boolean =
        kind == FailureKind.FOLDER_GRANT || kind == FailureKind.STORAGE_GONE

    /** The gate's entry point: verdict first, text fallback
     *  only for verdict-less (pre-v4.5) records. */
    fun needsUserAction(task: TaskRecord): Boolean =
        needsUserAction(FailureClassifier.verdictFor(task))

    /** Legacy text form — fallback use only (see above). */
    fun needsUserAction(error: String): Boolean =
        needsUserAction(FailureClassifier.fromErrorText(error))
}

/**
 * v5.8: retry-backoff jitter ("equal jitter"). A FIXED backoff
 * synchronises everyone who failed together: when a server
 * hiccups or the network blips, every task / chunk / segment /
 * FTP worker that was in flight fails at roughly the same
 * moment, and a fixed wait makes them all retry at roughly the
 * same moment too — a self-made thundering herd against a
 * server that just showed it is struggling.
 *
 * Shape: the result is uniform in [base / 2, base]. Why this
 * shape and not ±x% around the base:
 * - It NEVER exceeds the base, so every existing cap (the
 *   whole-task 180 s cap above all) holds after jitter by
 *   construction, and every worst-case total wait is exactly
 *   the pre-jitter bound.
 * - It never drops below half the base, so a retry still waits
 *   a meaningful while (a recovering server is not re-hit
 *   instantly) — and a sub-second chunk base (400/500 ms) can
 *   never jitter down to 0; [MIN_WAIT_MS] floors tiny bases
 *   explicitly as a second guard.
 * - The spread is half the base wide (5 s on a 10 s backoff,
 *   250 ms on a 500 ms one) — wide enough to decorrelate a
 *   herd that failed within the same second or two.
 *
 * Pure: the random source is injected ([random] returns a
 * value in [0, 1]; out-of-range and NaN are clamped), so tests
 * pin it with a fixed-seed [java.util.Random] while production
 * uses ThreadLocalRandom. A base of 0 or less waits 0.
 */
object BackoffJitter {

    /** Floor for tiny bases: a positive base never waits less. */
    const val MIN_WAIT_MS = 50L

    fun apply(baseMs: Long, random: () -> Double): Long {
        if (baseMs <= 0) return 0
        val r = random().let {
            if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1.0)
        }
        val half = baseMs / 2
        val jittered = half + (r * (baseMs - half)).toLong()
        return jittered
            .coerceAtLeast(minOf(MIN_WAIT_MS, baseMs))
            .coerceAtMost(baseMs)
    }

    /** Seeded form (tests / reproducible callers). */
    fun apply(baseMs: Long, random: java.util.Random): Long =
        apply(baseMs) { random.nextDouble() }

    /** Production form: a real random source. */
    fun apply(baseMs: Long): Long =
        apply(baseMs) {
            java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        }
}

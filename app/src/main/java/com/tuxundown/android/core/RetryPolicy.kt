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

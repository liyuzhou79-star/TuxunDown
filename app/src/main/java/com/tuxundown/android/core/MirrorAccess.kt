package com.tuxundown.android.core

/**
 * v5.4: can THIS mirror be used by THIS task right now — the one
 * ruling the engine and the mirror-management dialog both read.
 *
 * Inputs are only what is already on the task record: the
 * [SourceCheck.Verdict] the engine's last validation pass recorded
 * for the mirror (absent = never checked), and the task's manual
 * override set (the mirrors the user explicitly chose to use
 * anyway). This object never probes anything — it judges records,
 * it does not create them.
 *
 * The boundary that has no exceptions: a DIFFERENT verdict means
 * the mirror was PROVEN to serve another file. An override can
 * never lift that — mixing proven-different bytes would corrupt
 * the download, so there is nothing to negotiate. An override
 * only covers UNKNOWN (the probes could not judge, e.g. the
 * mirror may need a login the check is not allowed to carry):
 * the user may accept that risk for their own task, revocably,
 * and the finished file's hash check still backstops it.
 */
object MirrorAccess {

    /** What the record says about one mirror. */
    enum class State {
        /** No validation result recorded yet (never ran, or the
         *  mirror was added after the last run). */
        UNCHECKED,

        /** Proven the same file (SourceCheck SAME). */
        CONFIRMED,

        /** Proven a different file (SourceCheck DIFFERENT). */
        REJECTED,

        /** The probes could not judge (SourceCheck UNKNOWN). */
        UNCONFIRMED,
    }

    /** The combined ruling, with the reason kept distinct so the
     *  UI and the engine's note can say WHY, not just yes/no. */
    enum class Decision {
        /** Usable: proven the same file. */
        USABLE_CONFIRMED,

        /** Usable: unconfirmed, but the user overrode it for
         *  this task. */
        USABLE_OVERRIDDEN,

        /** Not usable: proven a different file. An override
         *  does NOT change this. */
        BLOCKED_DIFFERENT,

        /** Not usable: could not be confirmed and the user has
         *  not overridden it. */
        BLOCKED_UNCONFIRMED,

        /** Not usable: never checked yet. */
        BLOCKED_UNCHECKED,
    }

    fun stateOf(verdict: SourceCheck.Verdict?): State = when (verdict) {
        SourceCheck.Verdict.SAME -> State.CONFIRMED
        SourceCheck.Verdict.DIFFERENT -> State.REJECTED
        SourceCheck.Verdict.UNKNOWN -> State.UNCONFIRMED
        null -> State.UNCHECKED
    }

    fun decision(state: State, overridden: Boolean): Decision = when (state) {
        State.CONFIRMED -> Decision.USABLE_CONFIRMED
        // Proven different: the override set is not even read.
        State.REJECTED -> Decision.BLOCKED_DIFFERENT
        State.UNCONFIRMED ->
            if (overridden) Decision.USABLE_OVERRIDDEN
            else Decision.BLOCKED_UNCONFIRMED
        State.UNCHECKED -> Decision.BLOCKED_UNCHECKED
    }

    fun decision(verdict: SourceCheck.Verdict?, overridden: Boolean): Decision =
        decision(stateOf(verdict), overridden)

    fun usable(decision: Decision): Boolean =
        decision == Decision.USABLE_CONFIRMED ||
            decision == Decision.USABLE_OVERRIDDEN

    /** The ruling for one mirror of a live task record. */
    fun decisionFor(task: TaskRecord, mirror: String): Decision =
        decision(task.sourceVerdicts[mirror], mirror in task.sourceOverrides)

    fun usableFor(task: TaskRecord, mirror: String): Boolean =
        usable(decisionFor(task, mirror))

    /**
     * After the mirror list is edited, verdicts and overrides for
     * mirrors that are no longer on the list are orphans — drop
     * them so a re-added mirror starts from an honest UNCHECKED
     * instead of resurrecting a stale ruling or a stale override.
     * Pure: returns the pruned copies, mutates nothing.
     */
    fun prunedVerdicts(
        verdicts: Map<String, SourceCheck.Verdict>,
        keep: Collection<String>,
    ): Map<String, SourceCheck.Verdict> {
        val keepSet = keep.toSet()
        return verdicts.filterKeys { it in keepSet }
    }

    fun prunedOverrides(
        overrides: Set<String>,
        keep: Collection<String>,
    ): Set<String> {
        val keepSet = keep.toSet()
        return overrides.filter { it in keepSet }.toSet()
    }
}

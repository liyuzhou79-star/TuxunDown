package com.tuxundown.android.core

/**
 * Failure notification verdicts (v6.8) — pure, unit-tested.
 *
 * The mirror of [CompletionMerge] for the other terminal
 * outcome: when a task fails for good (auto-retry exhausted
 * or never applicable), ONE failure notification posts; several
 * final failures landing inside the same window merge into ONE
 * summary under the failure notification's own id, so a batch
 * failing together cannot stack up a wall of cards either.
 *
 * Timing caliber is CompletionMerge's, not a second one: the
 * window length IS [CompletionMerge.WINDOW_MS] (15 s, sliding —
 * every entry expires by its own timestamp, exactly like
 * completions), and the summary text follows
 * [CompletionMerge.summaryText]'s shape (one name per line).
 *
 * Wording split: an ordinary failure says the file name plus a
 * short human reason (task.error is already a plain sentence —
 * see Errors.human — this file only shortens it to a length a
 * notification can carry). A failure only the user can fix
 * ([RetryPolicy.needsUserAction] — the structured v4.5 verdict)
 * instead carries the FailureAdvice text for its kind: what the
 * user must DO first (re-pick the download folder, check the
 * storage device). Those failures earn NO retry action: retrying
 * before the user-side fix cannot succeed (that is exactly why
 * RetryPolicy never auto-retries them), so a 重试 button would
 * only promise another identical failure.
 *
 * Silence caliber: this file decides WORDS only. The service
 * posts on the (LOW) completion channel with no vibration, no
 * sound and no feedback call of any kind, at any hour — the
 * completion vibrate/sound switches govern completion feedback
 * only, per their settings wording, and are not reused here.
 */
object FailureNotify {

    /** Same window as completions — one timing source. */
    const val WINDOW_MS = CompletionMerge.WINDOW_MS

    /** Longest reason sentence a notification body carries. */
    const val MAX_REASON_CHARS = 80

    data class Failed(
        val name: String,
        val taskId: String,
        /** Short human reason (already passed through
         *  [shortReason] by the caller). */
        val reason: String,
        val needsUserAction: Boolean,
        /** FailureAdvice text for needsUserAction failures;
         *  null for ordinary ones. */
        val advice: String?,
        val atMs: Long,
    )

    /** Append [item], dropping entries that fell out of the
     *  window (each by its own timestamp). Order is failure
     *  order — the same rule as CompletionMerge.add. */
    fun add(list: List<Failed>, item: Failed, nowMs: Long): List<Failed> =
        list.filter { nowMs - it.atMs <= WINDOW_MS } + item

    /**
     * v7.5 (audit round 60, Step C): drop the entries whose
     * task record was just deleted. The failure notification
     * built from this list (single or merged) must not keep
     * pointing at a record that no longer exists — its 重试
     * action resolves the task by id and would land on
     * nothing. The caller cancels the failure notification
     * when this leaves the list empty.
     */
    fun prune(list: List<Failed>, taskIds: Set<String>): List<Failed> =
        list.filter { it.taskId !in taskIds }

    fun isMerged(list: List<Failed>): Boolean = list.size >= 2

    fun title(list: List<Failed>): String = "${list.size} 个文件下载失败"

    /** One file name per line, for the expanded summary. */
    fun lines(list: List<Failed>): String = list.joinToString("\n") { it.name }

    /** Expanded summary body — CompletionMerge.summaryText's
     *  shape (names only; a failure batch has no honest total
     *  to add). */
    fun summaryText(list: List<Failed>): String = lines(list)

    /**
     * Shorten a human failure sentence to notification length:
     * whitespace (incl. newlines) collapses to single spaces,
     * and anything past [MAX_REASON_CHARS] is cut with an
     * ellipsis, so the posted body never trails off mid-word
     * into a wall of text. Blank stays blank — the caller
     * renders its plain fallback rather than a fake reason.
     */
    fun shortReason(error: String): String {
        val flat = error.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= MAX_REASON_CHARS) return flat
        return flat.take(MAX_REASON_CHARS - 1) + "…"
    }

    fun singleTitle(item: Failed): String =
        if (item.needsUserAction) "下载失败，需要先处理" else "下载失败"

    fun singleBody(item: Failed): String {
        if (item.needsUserAction) {
            val advice = item.advice?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (advice.isNotEmpty()) return "${item.name}：$advice"
            // No advice on record for the kind: fall through to
            // the ordinary reason wording rather than inventing
            // instructions.
        }
        return if (item.reason.isNotEmpty()) {
            "${item.name}：${item.reason}"
        } else {
            "${item.name} 下载失败，点开任务可以查看原因。"
        }
    }

    /**
     * Whether the single-failure notification may carry a
     * 重试 action. Ordinary failures: yes (the tap re-queues
     * through the card's own plain retry path). Failures only
     * the user can fix: no — see the header.
     */
    fun retryAllowed(item: Failed): Boolean = !item.needsUserAction
}

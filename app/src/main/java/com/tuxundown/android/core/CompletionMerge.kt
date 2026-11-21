package com.tuxundown.android.core

/**
 * Multi-completion notification merging (v2.5).
 *
 * When a batch of tasks finishes together, one completion
 * notification per file would buzz and stack up a wall of
 * near-identical cards. Instead, completions landing inside
 * a short window merge into ONE summary notification: the
 * first completion posts immediately (with its own 打开 /
 * 分享 actions); if more land inside the window, the same
 * notification is REPLACED by the summary. Once the window
 * has been quiet for [WINDOW_MS], the next completion
 * starts a fresh single notification. Pure — unit-tested;
 * the service only keeps the list and renders the verdict.
 */
object CompletionMerge {

    const val WINDOW_MS = 15_000L

    data class Done(
        val name: String,
        val uri: String,
        val atMs: Long,
    )

    /** Append [item], dropping entries that fell out of the
     *  window. Order is completion order. */
    fun add(list: List<Done>, item: Done, nowMs: Long): List<Done> =
        list.filter { nowMs - it.atMs <= WINDOW_MS } + item

    fun isMerged(list: List<Done>): Boolean = list.size >= 2

    fun title(list: List<Done>): String = "${list.size} 个文件下载完成"

    /** One file name per line, for the expanded summary. */
    fun lines(list: List<Done>): String = list.joinToString("\n") { it.name }
}

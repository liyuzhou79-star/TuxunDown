package com.tuxundown.android.core

/**
 * Sniff-history rules (v1.4 storage, v5.7 re-sniff; pure,
 * unit-tested).
 *
 * The history itself is just the page URLs that produced a
 * resource list, newest first, capped at [MAX_ENTRIES] — it
 * carries NO timestamp, NO sniff count and NO page title, and
 * this round adds none: the stored shape stays exactly what
 * v1.4 shipped, so backups and migrations see no new field.
 *
 * Re-sniff semantics (v5.7), decided against the existing
 * models and held consistently in store + UI:
 * - TARGET: a re-sniff fetches the entry's stored page URL
 *   VERBATIM ([resniffTarget] is the identity) — no trimming,
 *   no normalising, no re-routing. The entry was saved from a
 *   page that produced a list; fetching any other string
 *   would sniff a different page than the one in history.
 * - SESSION: there is exactly ONE retained sniff session
 *   (SniffSession, v1.9 — a single process-memory slot for
 *   "the last result"), so a successful re-sniff REPLACES
 *   the current session through the very same result handler
 *   a fresh sniff uses — the same replacement a fresh sniff
 *   of any other page already performs today. There is no
 *   second slot to coexist in, and inventing one would fork
 *   the 上次嗅探结果 entry into "which session?" ambiguity.
 *   The honest cost, same as any fresh sniff: resources of
 *   the replaced session that were never turned into tasks
 *   are gone; tasks already created are untouched (they live
 *   in the task store, not the session).
 * - HISTORY ENTRY: follows the model's one existing rule —
 *   a page is remembered when a sniff of it produces a
 *   resource list, and remembering moves it to the front
 *   ([afterSuccessfulSniff]). A re-sniff that succeeds is a
 *   sniff, so the entry moves to the front, deduplicated,
 *   cap unchanged. A re-sniff whose page now resolves to a
 *   direct file creates the task like a fresh sniff does
 *   and does NOT touch the history (a fresh sniff's
 *   DirectFile arm never remembered the page either).
 * - FAILURE: a failed re-sniff changes NOTHING
 *   ([afterFailedSniff] is the identity) — the entry stays
 *   where it was and the retained session keeps the old
 *   result, because the result handler only writes the
 *   session on a resource list and only writes the history
 *   on that same arm. A dead page must not cost the user
 *   the still-useful old result.
 */
object SniffHistory {

    const val MAX_ENTRIES = 10

    /** The address a re-sniff fetches: the stored entry, verbatim. */
    fun resniffTarget(entry: String): String = entry

    /**
     * The history after [url]'s page produced a resource list
     * (fresh sniff or re-sniff alike): [url] first, every older
     * copy of it gone, the rest in their previous order, capped
     * at [MAX_ENTRIES]. A blank URL remembers nothing.
     */
    fun afterSuccessfulSniff(history: List<String>, url: String): List<String> {
        if (url.isBlank()) return history
        return (listOf(url) + history.filter { it != url })
            .distinct()
            .take(MAX_ENTRIES)
    }

    /**
     * The history after a FAILED (re-)sniff: unchanged, entry
     * and order alike — failure is not evidence the page is
     * gone for good (network down, login wall), and the old
     * entry is the user's way back in.
     */
    fun afterFailedSniff(history: List<String>, url: String): List<String> = history
}

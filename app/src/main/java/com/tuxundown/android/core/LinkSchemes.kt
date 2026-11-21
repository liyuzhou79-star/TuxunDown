package com.tuxundown.android.core

/**
 * Link intake rules (v2.8, audit round 14): WHICH strings may
 * become download tasks at all, and how much text an outside app
 * may hand us through the share sheet.
 *
 * The audit found the intake wider than the engine: any string a
 * share, a sniffed page or the add dialog produced could become
 * a task record. The engine only ever fetches http / https (via
 * HttpURLConnection) and ftp (via the hand-written client), and
 * it was the `as HttpURLConnection` cast — not a rule — that
 * stopped a file:// or content:// task from doing anything: it
 * failed with a confusing generic error after a record, a queue
 * slot and a target file had already been created for it. Now
 * the rule lives HERE, at the door, and every task-creation
 * funnel checks it first with a plain explanation.
 *
 * The share-text cap is the other half: EXTRA_TEXT is whatever
 * the sending app says it is, and an unbounded paste landed
 * whole in a Compose text field and in the link extractor's
 * regex scan. [MAX_SHARED_CHARS] is far beyond any real share
 * (a chat message full of links is a few KB) and far below a
 * memory/jank problem.
 *
 * Pure — unit-tested.
 */
object LinkSchemes {

    /** Longest shared text the share intake keeps (chars). */
    const val MAX_SHARED_CHARS = 32_768

    /** The user-note length cap (see TaskRecord.userNote). */
    const val MAX_USER_NOTE_CHARS = 200

    /** Scheme of [url] (lowercased), or "" when it has none. */
    fun schemeOf(url: String): String =
        url.trim().substringBefore(':').lowercase()

    /**
     * True only for the schemes the download engine can fetch:
     * http, https and ftp, in absolute "scheme://" form. A bare
     * host name, a relative path, file:, content:, intent:,
     * javascript:, data: and everything else are refused — the
     * caller says so in plain words instead of creating a task
     * that can only fail.
     */
    fun isSupported(url: String): Boolean {
        val u = url.trim()
        val scheme = schemeOf(u)
        if (scheme != "http" && scheme != "https" && scheme != "ftp") return false
        // "http:example.com" style strings have a scheme but no
        // authority the stack could connect to.
        return u.regionMatches(scheme.length, "://", 0, 3, ignoreCase = true)
    }

    /**
     * Clean text arriving from another app's share intent:
     * null-safe, trimmed, and capped at [MAX_SHARED_CHARS] so a
     * hostile or broken sender cannot park megabytes in the add
     * dialog's text field or the extractor's input.
     */
    fun sanitizeSharedText(raw: String?): String =
        (raw ?: "").trim().take(MAX_SHARED_CHARS)

    /** Clean a user note: single record, trimmed, capped. */
    fun sanitizeUserNote(raw: String): String =
        raw.trim().take(MAX_USER_NOTE_CHARS)
}

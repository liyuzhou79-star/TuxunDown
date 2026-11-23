package com.tuxundown.android.core

/**
 * Long-press link decision for the built-in sniff browser
 * (v6.5, audit round 50, item A-F1).
 *
 * The browser has TWO ways a link under the user's finger can
 * turn out not to be downloadable: the WebView download
 * listener (a real download start the page initiated) and the
 * long-press handler (the user deliberately pressed a link).
 * The listener path has always explained itself with a toast;
 * the long-press path used to just return false — the press
 * visibly did NOTHING, and the user could not tell whether the
 * press missed, the browser was stuck, or the link was junk.
 *
 * The decision of what a long-press hit MEANS now lives here,
 * pure, so both paths share one rule and one wording:
 *
 * - [Action.CONFIRM_DOWNLOAD]: a real link hit whose scheme
 *   the engine can fetch — ask, then create the task.
 * - [Action.EXPLAIN_NOT_DOWNLOADABLE]: a real link hit the
 *   engine cannot fetch (blob:, data:, file:, …) — show
 *   [notDownloadableMessage], the SAME sentence the download
 *   listener shows for the same situation.
 * - [Action.IGNORE]: not a link hit at all, or no link under
 *   the finger — stay silent, exactly as before.
 *
 * Pure — unit-tested (LongPressLinkTest).
 */
object LongPressLink {

    enum class Action { CONFIRM_DOWNLOAD, EXPLAIN_NOT_DOWNLOADABLE, IGNORE }

    /** Only schemes the download engine can actually fetch. */
    fun isDownloadable(url: String): Boolean {
        val scheme = url.substringBefore(':').lowercase()
        return scheme == "http" || scheme == "https" || scheme == "ftp"
    }

    /**
     * What a long-press should do: [isLinkHit] is the hit-test
     * verdict (anchor / image-anchor), [link] the URL under the
     * finger (may be null or blank).
     */
    fun actionFor(isLinkHit: Boolean, link: String?): Action = when {
        !isLinkHit -> Action.IGNORE
        link.isNullOrBlank() -> Action.IGNORE
        isDownloadable(link) -> Action.CONFIRM_DOWNLOAD
        else -> Action.EXPLAIN_NOT_DOWNLOADABLE
    }

    /**
     * The one explanation sentence for a captured link that is
     * not downloadable — shared verbatim by the download
     * listener and the long-press path, so the user reads the
     * same words whichever way the link arrived.
     */
    fun notDownloadableMessage(url: String): String =
        "抓到的不是能下载的链接（${url.substringBefore(':').lowercase()}），已忽略"
}

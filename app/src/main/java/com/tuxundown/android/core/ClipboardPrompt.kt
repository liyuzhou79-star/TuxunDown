package com.tuxundown.android.core

/**
 * v6.3: the clipboard-prompt frequency verdict — should the
 * "剪贴板里有链接" one-tap prompt be shown for [url]?
 *
 * THE CALIBER (two layers, one verdict — this EXTENDS the
 * pre-v6.3 mechanism, it does not replace it):
 *
 * - PERSISTED single slot (DownloadCenter.clipboardPrompted,
 *   in place since v1.3): the LAST link the user was prompted
 *   about. It survives sessions, so the most recent prompted
 *   link never prompts again even after a restart. That
 *   cross-session behaviour is kept exactly as it was.
 * - SESSION set (DownloadCenter's in-memory set, new in
 *   v6.3): every link prompted about — or dismissed — during
 *   THIS process run. Memory only: it is never written to
 *   disk and dies with the process.
 *
 * A prompt is suppressed when EITHER layer hits. The session
 * set closes the old mechanism's one hole: the single slot
 * only remembered the most recent link, so within one session
 * the pattern A -> B -> A re-prompted A (B had overwritten
 * the slot). Now A stays suppressed for the whole session,
 * while genuinely new content still prompts as usual, and the
 * persisted slot keeps doing its cross-session job for the
 * latest link. (The inverse trade is deliberate and recorded:
 * an OLDER link — prompted last session, since overwritten in
 * the slot by a newer one — may prompt once again in a new
 * session. That is the pre-v6.3 behaviour for every link but
 * the latest, unchanged.)
 *
 * Pure: the caller passes both layers in. Pinned by
 * ClipboardPromptTest.
 */
object ClipboardPrompt {

    fun shouldPrompt(
        url: String,
        persistedPrompted: String,
        sessionSeen: Set<String>,
    ): Boolean {
        if (url.isBlank()) return false
        if (url == persistedPrompted) return false
        if (url in sessionSeen) return false
        return true
    }
}

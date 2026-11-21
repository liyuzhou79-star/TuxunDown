package com.tuxundown.android.core

/**
 * v2.1: pull every download link out of ARBITRARY text — a
 * paragraph shared from a chat app, a forum post pasted into the
 * add dialog, release notes with the address buried mid-sentence.
 *
 * The matcher is the sniffer's PLAIN_URL pattern widened to
 * ftp://, followed by a trailing-punctuation trim: sentence
 * punctuation the character class legally includes (a final "."
 * or ",") or that CJK prose glues onto a link （，。、；：）】…)
 * is not part of the address. Results are de-duplicated,
 * order-preserving and capped — a pasted log file must not turn
 * into ten thousand tasks. Pure, unit-tested.
 */
object LinkExtract {
    // The character class also excludes CJK sentence punctuation:
    // prose glues it directly onto a link with no whitespace, and
    // a match that swallowed "，复制到浏览器打开" would be useless
    // (the trailing trim alone cannot rescue a mid-string glue).
    private val URL_IN_TEXT =
        Regex("""(?:https?|ftp)://[^\s"'<>\(\)\[\]\{\}，。、；：！？（）【】《》「」『』…·]+""")

    /** Characters that end a sentence, not a URL, when trailing. */
    private const val TRAILING = ".,;:!?，。、；：！？）】〕》」』…·"

    const val MAX_LINKS = 100

    fun extract(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (m in URL_IN_TEXT.findAll(text)) {
            var url = m.value
            while (url.isNotEmpty() && url.last() in TRAILING) {
                url = url.dropLast(1)
            }
            // A "match" that is only a scheme plus punctuation
            // is not a link.
            if (url.substringAfter("://", "").isEmpty()) continue
            if (seen.add(url)) {
                out.add(url)
                if (out.size >= MAX_LINKS) break
            }
        }
        return out
    }
}

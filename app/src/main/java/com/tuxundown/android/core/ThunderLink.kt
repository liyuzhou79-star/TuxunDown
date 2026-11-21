package com.tuxundown.android.core

/**
 * Thunder （迅雷） link decoding (v2.5).
 *
 * A thunder:// link is not a protocol this app speaks — it is
 * an ENVELOPE: Base64 of "AA" + the real URL + "ZZ". Decoding
 * the envelope recovers an ordinary http/https/ftp address,
 * which then goes down the exact same path as a pasted link
 * (probe / sniff / direct task). That is all this does: no
 * ed2k, no magnet, no Thunder network — those would need a
 * client this app does not have, and nothing here pretends
 * otherwise. A link that does not decode to a web address is
 * reported as undecodable, never guessed at.
 *
 * Pure (java.util.Base64; minSdk 29 is well past its API 26
 * introduction) — unit-tested.
 */
object ThunderLink {

    private const val SCHEME = "thunder://"

    /** A thunder token inside arbitrary text. The Base64
     *  alphabet is the whole match class, so glued-on CJK
     *  prose punctuation can never be swallowed into it. */
    private val TOKEN = Regex(
        """thunder://[A-Za-z0-9+/=_-]+""", RegexOption.IGNORE_CASE
    )

    fun isThunderLink(text: String): Boolean =
        text.trim().startsWith(SCHEME, ignoreCase = true)

    fun containsToken(text: String): Boolean = TOKEN.containsMatchIn(text)

    /**
     * Decode ONE thunder:// link to its real address, or null
     * when the envelope is broken (bad Base64, no web address
     * inside). The AA/ZZ markers are stripped when present;
     * envelopes minted without them still decode.
     */
    fun decode(raw: String): String? {
        val t = raw.trim()
        if (!t.startsWith(SCHEME, ignoreCase = true)) return null
        var payload = t.substring(SCHEME.length).trim()
        // A pasted link sometimes arrives percent-encoded
        // (%3D for the padding) or with a query glued on.
        payload = payload.substringBefore('?').substringBefore('#')
        if (payload.contains('%')) {
            payload = runCatching {
                java.net.URLDecoder.decode(payload, "UTF-8")
            }.getOrDefault(payload)
        }
        val bytes = decodeBase64(payload) ?: return null
        var inner = runCatching { String(bytes, Charsets.UTF_8) }
            .getOrNull()?.trim() ?: return null
        if (inner.startsWith("AA")) inner = inner.substring(2)
        if (inner.endsWith("ZZ")) inner = inner.dropLast(2)
        inner = inner.trim()
        return if (
            inner.startsWith("http://", ignoreCase = true) ||
            inner.startsWith("https://", ignoreCase = true) ||
            inner.startsWith("ftp://", ignoreCase = true)
        ) {
            inner
        } else {
            null
        }
    }

    private fun decodeBase64(payload: String): ByteArray? {
        val cleaned = payload.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        // Standard first, then the URL-safe and MIME variants:
        // different generators mint different flavours.
        val decoders: List<(String) -> ByteArray> = listOf(
            { java.util.Base64.getDecoder().decode(it) },
            { java.util.Base64.getUrlDecoder().decode(it) },
            { java.util.Base64.getMimeDecoder().decode(it) },
        )
        for (d in decoders) {
            runCatching { return d(cleaned) }
        }
        return null
    }

    /** Every thunder token in [text], decoded, de-duplicated,
     *  in order of appearance. Undecodable tokens are skipped. */
    fun findDecoded(text: String): List<String> {
        if (!containsToken(text)) return emptyList()
        val out = mutableListOf<String>()
        for (m in TOKEN.findAll(text)) {
            val url = decode(m.value) ?: continue
            if (url !in out) out.add(url)
        }
        return out
    }

    data class Prepared(
        /** The text with every thunder token replaced by its
         *  decoded address (undecodable tokens removed). */
        val text: String,
        val decoded: Int,
        val failed: Int,
    )

    /**
     * Rewrite [text] for the normal link pipeline: thunder
     * tokens become their real addresses in place, so prose,
     * multi-link pastes and bare links all continue exactly as
     * if the user had pasted the real addresses. Counts let
     * the caller report the undecodable ones honestly.
     */
    fun replaceTokens(text: String): Prepared {
        if (!containsToken(text)) return Prepared(text, 0, 0)
        var decoded = 0
        var failed = 0
        val out = TOKEN.replace(text) { m ->
            val url = decode(m.value)
            if (url != null) {
                decoded++
                url
            } else {
                failed++
                ""
            }
        }
        return Prepared(out, decoded, failed)
    }
}

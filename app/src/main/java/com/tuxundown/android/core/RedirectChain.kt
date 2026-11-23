package com.tuxundown.android.core

import java.net.URL

/**
 * v6.3: the redirect-chain verdict behind short-link expansion
 * (see [ShortLinkExpander] for the network driver). Pure: the
 * input is ONE hop's response facts (the URL that was asked,
 * the status code, the raw Location header) plus the chain
 * state so far (the visited set, the hops already followed);
 * the output is Follow this address / Done, this is the final
 * address / Fall back to the original, with a reason. No I/O,
 * no Android API — pinned by RedirectChainTest.
 *
 * THE RULING:
 *
 * - Redirect codes are exactly 301 / 302 / 303 / 307 / 308.
 *   HttpURLConnection's own follower is NOT used by the driver:
 *   it refuses to cross http <-> https, which is precisely the
 *   hop a short link is most likely to take (http://t.cn/…
 *   landing on an https file). Following is manual, one hop at
 *   a time, and every hop is judged here.
 * - A relative Location is resolved against the CURRENT hop's
 *   URL (RFC 3986, via java.net.URL's context constructor) —
 *   "Location: /file.zip" and "Location: ../file.zip" are
 *   ordinary shortener output, not errors.
 * - The resolved target must be http or https. Anything else
 *   (ftp:, intent:, file:, a scheme the resolver invented) is a
 *   FALLBACK, never a follow: expansion exists to find the
 *   final DOWNLOAD address, and a non-http(s) target is not
 *   one the create funnel would have taken from this hop.
 * - Loop detection keys on [CreateDedup.normalize] — the same
 *   normalization the task funnel judges duplicates by (scheme
 *   + host lowercased, default port dropped, fragment dropped):
 *   a chain that revisits an address it already asked, in any
 *   of those spellings, is a loop.
 * - [MAX_HOPS] = 10. Real shorteners chain 1–3 hops (shortener
 *   -> landing page -> CDN); 10 is several times that, so no
 *   honest chain is cut, while a loop the visited-set somehow
 *   misses (a counter or timestamp in the query makes every
 *   hop "new") still stops at a bounded cost — each hop is a
 *   header-only exchange with the probe timeout, so the worst
 *   case is ~10 small requests, never a download. (The sniff
 *   page-fetcher's tighter 6 is for full PAGE fetches; header
 *   hops are far cheaper, so the bound can be more generous.)
 * - Any non-redirect answer below 400 is DONE: the address
 *   asked IS the final address (a direct link that never
 *   jumps expands to itself). 400+ is a FALLBACK with the
 *   code, so the dialog can name the failure instead of
 *   creating a task against a dead hop.
 *
 * Every Fallback means the same thing downstream: create the
 * task with the ORIGINAL address and say so — expansion is an
 * opt-in convenience, never a reason to lose the download.
 */
object RedirectChain {

    const val MAX_HOPS = 10

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    fun isRedirect(code: Int): Boolean = code in REDIRECT_CODES

    /** Why a chain cannot be followed to a final address. */
    enum class FallbackReason {
        /** Redirect with no (or a blank) Location header. */
        NO_LOCATION,

        /** Location could not be resolved into a URL at all. */
        BAD_LOCATION,

        /** Location resolved to a non-http(s) address. */
        NON_HTTP_TARGET,

        /** The chain revisits an already-asked address. */
        LOOP,

        /** More than [MAX_HOPS] redirects in a row. */
        TOO_MANY_HOPS,

        /** The hop answered an HTTP error (see [Verdict.Fallback.httpCode]). */
        HTTP_ERROR,
    }

    sealed class Verdict {
        /** Follow the chain to [nextUrl] (already resolved, http(s)). */
        data class Follow(val nextUrl: String) : Verdict()

        /** [finalUrl] answered without redirecting: it IS final. */
        data class Done(val finalUrl: String) : Verdict()

        /** Give up on expansion; the caller keeps the original. */
        data class Fallback(
            val reason: FallbackReason,
            val httpCode: Int = 0,
        ) : Verdict()
    }

    /** The loop-detection key of one address (see the file doc). */
    fun keyOf(url: String): String = CreateDedup.normalize(url)

    /**
     * Judge one hop. [visitedKeys] holds [keyOf] of every
     * address already asked INCLUDING [currentUrl]; [hopsDone]
     * is how many redirects have been followed so far.
     */
    fun judge(
        currentUrl: String,
        statusCode: Int,
        location: String?,
        visitedKeys: Set<String>,
        hopsDone: Int,
    ): Verdict {
        if (!isRedirect(statusCode)) {
            return if (statusCode in 200..399) {
                Verdict.Done(currentUrl)
            } else {
                Verdict.Fallback(FallbackReason.HTTP_ERROR, statusCode)
            }
        }
        if (hopsDone >= MAX_HOPS) {
            return Verdict.Fallback(FallbackReason.TOO_MANY_HOPS)
        }
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) {
            return Verdict.Fallback(FallbackReason.NO_LOCATION)
        }
        val resolved = runCatching { URL(URL(currentUrl), loc).toString() }
            .getOrNull()
            ?: return Verdict.Fallback(FallbackReason.BAD_LOCATION)
        val scheme = resolved.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") {
            return Verdict.Fallback(FallbackReason.NON_HTTP_TARGET)
        }
        if (keyOf(resolved) in visitedKeys) {
            return Verdict.Fallback(FallbackReason.LOOP)
        }
        return Verdict.Follow(resolved)
    }

    /** Plain-language wording of a fallback, for the dialog. */
    fun fallbackText(v: Verdict.Fallback): String = when (v.reason) {
        FallbackReason.NO_LOCATION -> "服务器跳转但没给新地址"
        FallbackReason.BAD_LOCATION -> "服务器给的跳转地址无效"
        FallbackReason.NON_HTTP_TARGET -> "跳转到的不是 http/https 地址"
        FallbackReason.LOOP -> "链接跳转成环"
        FallbackReason.TOO_MANY_HOPS -> "跳转次数太多（超过 $MAX_HOPS 次）"
        FallbackReason.HTTP_ERROR -> Errors.httpMessage(v.httpCode)
    }
}

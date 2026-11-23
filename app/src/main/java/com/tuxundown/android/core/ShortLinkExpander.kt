package com.tuxundown.android.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * v6.3: short-link expansion — the network driver behind the
 * add dialog's 「展开短链接」 checkbox (default OFF; unchecked
 * this object is never called and nothing changes). When the
 * user ticks it, the dialog's ONE main link is followed through
 * its redirect chain BEFORE any task is created, and the task
 * is created with the FINAL address (see startSmart in
 * MainActivity for the wiring and the ordering caliber:
 * expansion completes before CreateDedup judges, so dedup sees
 * the address the task will actually carry).
 *
 * How a chain is walked:
 * - Connections open through [ProxyState.open] — the app's one
 *   connection door (the engine, LinkRouter.probe and the
 *   sniffer all open this way), so the configured proxy applies
 *   exactly as it does to every other request; there is no
 *   second, bare connection path. With a proxy configured and
 *   unreachable, the failure says so (and nothing went direct),
 *   same wording as the sniffer.
 * - Following is MANUAL (instanceFollowRedirects = false):
 *   HttpURLConnection's built-in follower refuses http <->
 *   https hops. Each hop is judged by the pure [RedirectChain]
 *   (hop cap, loop detection, relative-Location resolution,
 *   non-http(s) refusal — see its file doc).
 * - Each hop is a HEAD request with the probe-class timeout
 *   ([TIMEOUT_MS], LinkRouter.probe's 12 s caliber: a probe
 *   must never hang the dialog). A hop that refuses HEAD
 *   (405 / 501 — some shorteners do) is retried once as a
 *   1-byte Range GET, LinkRouter.probe's own fallback; the
 *   body is never read either way, only the status line and
 *   the Location header are used.
 * - ONLY the main link is expanded. Mirrors are never expanded
 *   (a mirror is already an address of the file itself, typed
 *   as such), and ftp links never reach this object (no HTTP
 *   redirects exist to follow).
 *
 * Outcomes are always stated by the caller, never silent:
 * [Outcome.Expanded] names the final address, [Outcome.Direct]
 * says the link never jumped, and [Outcome.FellBack] carries
 * the plain reason — the caller then creates the task with the
 * ORIGINAL address and says it is doing so.
 */
object ShortLinkExpander {

    /** Per-hop timeout: LinkRouter.probe's caliber (12 s). */
    const val TIMEOUT_MS = 12_000

    sealed class Outcome {
        /** The chain jumped [hops] times and landed on [finalUrl]. */
        data class Expanded(val finalUrl: String, val hops: Int) : Outcome()

        /** The link answered directly — it is its own final address. */
        object Direct : Outcome()

        /** Expansion failed; create with the original address. */
        data class FellBack(val reason: String) : Outcome()
    }

    fun expand(
        startUrl: String,
        userAgent: String = DownloadEngine.DEFAULT_UA,
    ): Outcome {
        var current = startUrl
        val visited = mutableSetOf(RedirectChain.keyOf(startUrl))
        var hops = 0
        while (true) {
            val (code, location) = try {
                requestHop(current, userAgent)
            } catch (e: Exception) {
                // Same honesty as the sniffer: with a proxy
                // configured, an I/O failure almost always means
                // the proxy is unreachable — say so (and that
                // nothing went direct) instead of a bare cause.
                val ps = ProxyState.settings
                return Outcome.FellBack(
                    if (ps.active && e is java.io.IOException) {
                        ProxySettings.unreachableMessage(ps, e)
                    } else {
                        "打开链接失败：${Errors.human(e)}"
                    }
                )
            }
            when (val v = RedirectChain.judge(current, code, location, visited, hops)) {
                is RedirectChain.Verdict.Follow -> {
                    current = v.nextUrl
                    visited.add(RedirectChain.keyOf(v.nextUrl))
                    hops++
                }
                is RedirectChain.Verdict.Done ->
                    return if (hops == 0) {
                        Outcome.Direct
                    } else {
                        Outcome.Expanded(current, hops)
                    }
                is RedirectChain.Verdict.Fallback ->
                    return Outcome.FellBack(RedirectChain.fallbackText(v))
            }
        }
    }

    /**
     * One hop's facts: the status code and the raw Location
     * header. HEAD first; a HEAD-refusing hop (405 / 501) is
     * retried once as a 1-byte Range GET (LinkRouter.probe's
     * fallback). The body is never read — the connection is
     * disconnected, not drained, because a Range-ignoring
     * server could be streaming the whole file at us.
     */
    private fun requestHop(url: String, userAgent: String): Pair<Int, String?> {
        var method = "HEAD"
        repeat(2) {
            val c: HttpURLConnection = ProxyState.open(URL(url))
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.setRequestProperty("User-Agent", userAgent)
            if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")
            val code = c.responseCode
            val location = c.getHeaderField("Location")
            c.disconnect()
            if (method == "HEAD" && (code == 405 || code == 501)) {
                method = "GET"
            } else {
                return code to location
            }
        }
        // The HEAD -> GET retry above always returns on its
        // second pass; reaching here would mean the loop shape
        // changed, so fail loudly instead of inventing a hop.
        throw IllegalStateException("服务器没有回应")
    }
}

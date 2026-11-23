package com.tuxundown.android.core

import java.net.URL

/**
 * v8.2 audit round 67: the shared redirect rule for the
 * engine-side auxiliary requests (the link pre-check, the
 * queue preheat and the sidecar checksum fetch).
 *
 * These requests carry the task's session Cookie, so they
 * must never let the platform follow a redirect on its own:
 * a platform-followed hop re-sends the headers it was given
 * to whatever host the Location names. Following is manual,
 * exactly as the HLS playlist probe has done since v8.1 —
 * each hop is a fresh request whose Cookie is re-judged by
 * [SessionScope] against the task's ORIGINAL url, and the
 * chain stops after [MAX_REDIRECTS] hops. Referer is not a
 * credential and keeps riding every hop unchanged, per
 * SessionScope's standing rule.
 *
 * This object is the pure half of that rule (which codes
 * count as a redirect, and where a hop may go); the callers
 * own the actual connections. Unit-tested (AuxRedirectTest).
 */
object AuxRedirect {

    /** Hop cap, the same bound the HLS probe uses. */
    const val MAX_REDIRECTS = 5

    /** Redirect codes worth following: 301–308 except the
     *  non-redirect 304 and the unused 306. */
    fun isRedirect(code: Int): Boolean =
        code in 301..308 && code != 304 && code != 306

    /**
     * The next hop's URL, resolved against the CURRENT hop
     * (a relative Location is ordinary server output), or
     * null when the chain must stop here: no Location, a
     * Location that does not resolve, or the hop cap
     * already reached ([hopsDone] redirects followed so far).
     */
    fun nextUrl(currentUrl: String, location: String?, hopsDone: Int): String? {
        if (hopsDone >= MAX_REDIRECTS) return null
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return null
        return runCatching { URL(URL(currentUrl), loc).toString() }.getOrNull()
    }
}

package com.tuxundown.android.core

import java.net.URL

/**
 * Session-cookie scoping (v4.7, audit round 32): WHICH requests
 * may carry a task's captured Cookie header at all.
 *
 * The rule, and why: a task's Cookie is a login credential for
 * ONE host — the host of the task's own primary link, which is
 * where the sniffer captured it (CookieManager hands out cookies
 * per domain) or where the user pasted it for. Browsers scope
 * cookies by domain for exactly this reason. Before this round
 * the engine funnel re-sent the primary host's Cookie verbatim
 * to EVERY URL it opened for the task:
 * - mirror chunk requests, mirror validation probes and samples
 *   — a mirror is a URL the user pasted for SPEED, on a host that
 *   has no claim on the primary's session;
 * - connection warmup over the task's sources;
 * - every redirect hop, including hops to a DIFFERENT host the
 *   primary server (or a mirror) points at.
 * A credential must never travel to a destination picked for
 * other reasons. So the funnel now asks here, per hop, with the
 * hop's own URL as [requestUrl]:
 *
 * - the cookie goes out ONLY when the request URL's host equals
 *   the primary URL's host EXACTLY (no suffix / subdomain match:
 *   `cdn.example.com` is a different destination from
 *   `example.com`, and a session for one is not a session for
 *   the other);
 * - host comparison is case-insensitive and port-agnostic (the
 *   same host on another port is the same session owner, per
 *   RFC 6265's port-blind cookie model);
 * - anything unparseable — a blank URL, a URL with no host, a
 *   null cookie — fails CLOSED: no cookie is sent.
 *
 * Referer is deliberately NOT scoped here: it is the
 * anti-hotlink signal servers check cross-host BY DESIGN, and
 * its value is the page the user browsed — a different
 * sensitivity class from a credential. It keeps riding every
 * hop unchanged.
 *
 * Pure — unit-tested (SessionScopeTest).
 */
object SessionScope {

    /** Lowercased host of [url], or null when it has none /
     *  cannot be parsed. */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { URL(url.trim()).host }.getOrNull()
        return host?.lowercase()?.takeIf { it.isNotBlank() }
    }

    /**
     * May [cookie] (captured for the task whose primary link is
     * [ownerUrl]) be sent on a request to [requestUrl]? True
     * only for a non-blank cookie and an exact same-host match;
     * every doubt answers false.
     */
    fun maySendCookie(cookie: String?, ownerUrl: String?, requestUrl: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        val owner = hostOf(ownerUrl) ?: return false
        val target = hostOf(requestUrl) ?: return false
        return owner == target
    }

    /** The cookie to attach to a request to [requestUrl], or
     *  null when it must not travel there (see [maySendCookie]). */
    fun cookieFor(cookie: String?, ownerUrl: String?, requestUrl: String?): String? =
        if (maySendCookie(cookie, ownerUrl, requestUrl)) cookie else null
}

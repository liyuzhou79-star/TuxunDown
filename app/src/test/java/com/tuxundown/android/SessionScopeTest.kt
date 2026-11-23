package com.tuxundown.android

import com.tuxundown.android.core.SessionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScopeTest {

    private val cookie = "session=abc123"
    private val owner = "https://example.com/files/big.zip"

    @Test
    fun sameHostSends() {
        assertTrue(SessionScope.maySendCookie(cookie, owner, "https://example.com/files/big.zip"))
        // A redirect hop that stays on the owner's host keeps it.
        assertTrue(SessionScope.maySendCookie(cookie, owner, "https://example.com/dl?token=x"))
        // Scheme change on the same host is still the same owner.
        assertTrue(SessionScope.maySendCookie(cookie, owner, "http://example.com/files/big.zip"))
        assertEquals(
            cookie,
            SessionScope.cookieFor(cookie, owner, "https://example.com/other"),
        )
    }

    @Test
    fun differentHostDoesNotSend() {
        // The mirror case: a user-pasted mirror on another host.
        assertFalse(SessionScope.maySendCookie(cookie, owner, "https://mirror.net/files/big.zip"))
        // A cross-host redirect hop loses the cookie too.
        assertFalse(SessionScope.maySendCookie(cookie, owner, "https://cdn.other.org/big.zip"))
        assertNull(SessionScope.cookieFor(cookie, owner, "https://mirror.net/files/big.zip"))
    }

    @Test
    fun subdomainDoesNotSend() {
        // Exact match only — no suffix / parent-domain widening.
        assertFalse(SessionScope.maySendCookie(cookie, owner, "https://cdn.example.com/big.zip"))
        assertFalse(
            SessionScope.maySendCookie(
                cookie, "https://cdn.example.com/big.zip", "https://example.com/big.zip",
            ),
        )
        // A look-alike host that merely ends with the owner's name.
        assertFalse(SessionScope.maySendCookie(cookie, owner, "https://notexample.com/big.zip"))
        assertFalse(SessionScope.maySendCookie(cookie, owner, "https://example.com.evil.net/big.zip"))
    }

    @Test
    fun differentPortStillSends() {
        // Ports do not scope cookies (RFC 6265): same host, other
        // port, same session owner.
        assertTrue(SessionScope.maySendCookie(cookie, owner, "https://example.com:8443/big.zip"))
        assertTrue(
            SessionScope.maySendCookie(
                cookie, "https://example.com:8443/big.zip", "https://example.com/big.zip",
            ),
        )
    }

    @Test
    fun hostCaseDoesNotMatter() {
        assertTrue(SessionScope.maySendCookie(cookie, owner, "https://EXAMPLE.COM/big.zip"))
        assertTrue(
            SessionScope.maySendCookie(
                cookie, "https://ExAmPle.com/big.zip", "https://example.com/big.zip",
            ),
        )
        assertEquals("example.com", SessionScope.hostOf("https://EXAMPLE.com:8443/x"))
    }

    @Test
    fun unparseableFailsClosed() {
        assertFalse(SessionScope.maySendCookie(cookie, owner, ""))
        assertFalse(SessionScope.maySendCookie(cookie, owner, "   "))
        assertFalse(SessionScope.maySendCookie(cookie, owner, null))
        assertFalse(SessionScope.maySendCookie(cookie, owner, "not a url"))
        assertFalse(SessionScope.maySendCookie(cookie, owner, "/relative/path.zip"))
        assertFalse(SessionScope.maySendCookie(cookie, owner, "https:///no-host.zip"))
        // A broken OWNER url means no host owns the cookie: closed.
        assertFalse(SessionScope.maySendCookie(cookie, "", "https://example.com/big.zip"))
        assertFalse(SessionScope.maySendCookie(cookie, null, "https://example.com/big.zip"))
        assertFalse(SessionScope.maySendCookie(cookie, "not a url", "https://example.com/big.zip"))
        assertNull(SessionScope.hostOf(""))
        assertNull(SessionScope.hostOf(null))
        assertNull(SessionScope.hostOf("not a url"))
    }

    // ---- A-F3 (v6.5): the task's explicit Authorization header ----

    private val auth = "Bearer token-xyz"
    private val basicUserPass = "Basic dXNlcjpwYXNz" // user:pass

    @Test
    fun authorizationSendsToOwnHost() {
        assertTrue(
            SessionScope.maySendAuthorization(auth, owner, "https://example.com/files/big.zip"),
        )
        // Scheme change / other port / case on the same host:
        // the same caliber as the cookie rule.
        assertTrue(SessionScope.maySendAuthorization(auth, owner, "http://example.com/x"))
        assertTrue(
            SessionScope.maySendAuthorization(auth, owner, "https://EXAMPLE.com:8443/x"),
        )
        assertEquals(
            auth,
            SessionScope.authorizationFor(auth, owner, "https://example.com/dl?token=x"),
        )
    }

    @Test
    fun authorizationNeverSendsCrossHost() {
        // A mirror the user pasted for speed gets nothing.
        assertFalse(
            SessionScope.maySendAuthorization(auth, owner, "https://mirror.net/files/big.zip"),
        )
        assertNull(SessionScope.authorizationFor(auth, owner, "https://mirror.net/x"))
        // A redirect chain: hop 1 stays on the owner's host and
        // carries it, hop 2 leaves for another host and drops it.
        assertEquals(
            auth,
            SessionScope.authorizationFor(auth, owner, "https://example.com/hop1"),
        )
        assertNull(SessionScope.authorizationFor(auth, owner, "https://cdn.other.org/hop2"))
    }

    @Test
    fun authorizationSubdomainCaliberMatchesCookie() {
        // Exact match only — the same subdomain verdicts as
        // maySendCookie above, both directions.
        assertFalse(
            SessionScope.maySendAuthorization(auth, owner, "https://cdn.example.com/big.zip"),
        )
        assertFalse(
            SessionScope.maySendAuthorization(
                auth, "https://cdn.example.com/big.zip", "https://example.com/big.zip",
            ),
        )
        assertFalse(
            SessionScope.maySendAuthorization(auth, owner, "https://example.com.evil.net/x"),
        )
        // And the two predicates agree on a spread of targets.
        for (target in listOf(
            "https://example.com/a", "https://cdn.example.com/a",
            "https://mirror.net/a", "https://example.com:8443/a",
            "http://example.com/a", "not a url", "",
        )) {
            assertEquals(
                "caliber drift on $target",
                SessionScope.maySendCookie(cookie, owner, target),
                SessionScope.maySendAuthorization(auth, owner, target),
            )
        }
    }

    @Test
    fun authorizationFailsClosedLikeCookie() {
        assertFalse(SessionScope.maySendAuthorization(null, owner, "https://example.com/x"))
        assertFalse(SessionScope.maySendAuthorization("", owner, "https://example.com/x"))
        assertFalse(SessionScope.maySendAuthorization("   ", owner, "https://example.com/x"))
        assertFalse(SessionScope.maySendAuthorization(auth, "", "https://example.com/x"))
        assertFalse(SessionScope.maySendAuthorization(auth, null, "https://example.com/x"))
        assertFalse(SessionScope.maySendAuthorization(auth, owner, "not a url"))
        assertFalse(SessionScope.maySendAuthorization(auth, owner, null))
        assertNull(SessionScope.authorizationFor(null, owner, "https://example.com/x"))
    }

    @Test
    fun explicitAuthorizationWinsOverUserinfoBasicOnOwnHost() {
        // Same host: the explicit header goes out, so the
        // userinfo Basic must NOT be built (pre-A-F3 precedence
        // preserved where the explicit header actually travels).
        assertNull(
            SessionScope.basicAuthorizationFor(
                "user:pass", auth, owner, "https://example.com/files/big.zip",
            ),
        )
    }

    @Test
    fun crossHostHopKeepsItsOwnUserinfoBasic() {
        // The explicit header is withheld from this cross-host
        // hop — but the hop URL's OWN user:pass is the target's
        // credential and still produces its Basic header.
        assertEquals(
            basicUserPass,
            SessionScope.basicAuthorizationFor(
                "user:pass", auth, owner, "https://mirror.net/files/big.zip",
            ),
        )
        // URL-encoded userinfo is decoded before encoding,
        // exactly as the old inline code did (user:p@ss).
        assertEquals(
            "Basic dXNlcjpwQHNz",
            SessionScope.basicAuthorizationFor(
                "user:p%40ss", auth, owner, "https://mirror.net/x",
            ),
        )
    }

    @Test
    fun noExplicitHeaderKeepsOldUserinfoBehavior() {
        // No explicit Authorization at all: userinfo Basic is
        // built on any host, exactly as before A-F3 — and a hop
        // URL without userinfo still gets nothing.
        assertEquals(
            basicUserPass,
            SessionScope.basicAuthorizationFor(
                "user:pass", null, owner, "https://example.com/files/big.zip",
            ),
        )
        assertEquals(
            basicUserPass,
            SessionScope.basicAuthorizationFor(
                "user:pass", null, owner, "https://mirror.net/files/big.zip",
            ),
        )
        assertNull(
            SessionScope.basicAuthorizationFor(
                null, null, owner, "https://example.com/files/big.zip",
            ),
        )
        assertNull(
            SessionScope.basicAuthorizationFor(
                "", null, owner, "https://example.com/files/big.zip",
            ),
        )
        assertNull(
            SessionScope.basicAuthorizationFor(
                null, auth, owner, "https://example.com/files/big.zip",
            ),
        )
    }

    @Test
    fun nullOrBlankCookieNeverSends() {
        assertFalse(SessionScope.maySendCookie(null, owner, "https://example.com/big.zip"))
        assertFalse(SessionScope.maySendCookie("", owner, "https://example.com/big.zip"))
        assertFalse(SessionScope.maySendCookie("   ", owner, "https://example.com/big.zip"))
        assertNull(SessionScope.cookieFor(null, owner, "https://example.com/big.zip"))
    }
}

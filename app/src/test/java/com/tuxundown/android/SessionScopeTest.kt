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

    @Test
    fun nullOrBlankCookieNeverSends() {
        assertFalse(SessionScope.maySendCookie(null, owner, "https://example.com/big.zip"))
        assertFalse(SessionScope.maySendCookie("", owner, "https://example.com/big.zip"))
        assertFalse(SessionScope.maySendCookie("   ", owner, "https://example.com/big.zip"))
        assertNull(SessionScope.cookieFor(null, owner, "https://example.com/big.zip"))
    }
}

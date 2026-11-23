package com.tuxundown.android

import com.tuxundown.android.core.AuxRedirect
import com.tuxundown.android.core.SessionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.2 audit round 67: the manual-redirect rule behind the
 * engine-side auxiliary requests (link pre-check, queue
 * preheat, sidecar checksum fetch) — the same shape the HLS
 * probe adopted in v8.1.
 */
class AuxRedirectTest {

    @Test
    fun redirectCodesMatchTheHlsProbeCaliber() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            assertTrue("$code", AuxRedirect.isRedirect(code))
        }
        // 304 is not a redirect to follow and 306 is unused;
        // ordinary answers are not redirects either.
        for (code in listOf(200, 204, 304, 306, 400, 404, 500)) {
            assertFalse("$code", AuxRedirect.isRedirect(code))
        }
    }

    @Test
    fun nextUrlResolvesRelativeAndAbsoluteLocations() {
        assertEquals(
            "https://example.com/dl/file.zip",
            AuxRedirect.nextUrl("https://example.com/a/b", "/dl/file.zip", 0),
        )
        assertEquals(
            "https://cdn.other.net/file.zip",
            AuxRedirect.nextUrl(
                "https://example.com/a", "https://cdn.other.net/file.zip", 0,
            ),
        )
        assertEquals(
            "https://example.com/a/next",
            AuxRedirect.nextUrl("https://example.com/a/this", "next", 0),
        )
    }

    @Test
    fun nextUrlStopsAtTheCapAndWithoutALocation() {
        assertEquals(5, AuxRedirect.MAX_REDIRECTS)
        assertNull(
            AuxRedirect.nextUrl("https://example.com/a", "/b", AuxRedirect.MAX_REDIRECTS),
        )
        assertNull(AuxRedirect.nextUrl("https://example.com/a", null, 0))
        assertNull(AuxRedirect.nextUrl("https://example.com/a", "  ", 0))
        // The fifth hop is still allowed; the sixth is not.
        assertEquals(
            "https://example.com/b",
            AuxRedirect.nextUrl("https://example.com/a", "/b", 4),
        )
    }

    @Test
    fun cookieIsRejudgedAgainstTheOriginalUrlOnEveryHop() {
        // The chain the three call sites now walk: hop 1 stays
        // on the task's host and keeps the Cookie, hop 2 leaves
        // for a CDN host and drops it, and a hop back to the
        // original host is judged against the ORIGINAL url
        // again (the HlsProbe caliber), not the previous hop.
        val owner = "https://example.com/files/big.zip"
        val cookie = "session=abc123"
        assertEquals(
            cookie,
            SessionScope.cookieFor(cookie, owner, "https://example.com/hop1"),
        )
        assertNull(SessionScope.cookieFor(cookie, owner, "https://cdn.other.net/hop2"))
        assertEquals(
            cookie,
            SessionScope.cookieFor(cookie, owner, "https://example.com/hop3"),
        )
        // An Authorization, where a caller carries one, follows
        // exactly the same per-hop verdict.
        assertNull(
            SessionScope.authorizationFor(
                "Bearer tok", owner, "https://cdn.other.net/hop2",
            ),
        )
    }
}

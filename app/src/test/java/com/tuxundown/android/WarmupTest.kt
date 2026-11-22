package com.tuxundown.android

import com.tuxundown.android.core.TaskStore
import com.tuxundown.android.core.WarmHosts
import com.tuxundown.android.core.Warmup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupTest {

    @Test
    fun proxyActiveWarmsNothing() {
        assertTrue(
            Warmup.sourcesToWarm(
                "https://a.example.com/file.zip",
                listOf("https://b.example.com/file.zip"),
                proxyActive = true,
            ).isEmpty()
        )
    }

    @Test
    fun ftpPrimaryWarmsNothing() {
        assertTrue(
            Warmup.sourcesToWarm(
                "ftp://a.example.com/file.zip",
                emptyList(),
                proxyActive = false,
            ).isEmpty()
        )
    }

    @Test
    fun ftpMirrorIsFilteredAndHttpMirrorIsKept() {
        assertEquals(
            listOf(
                "https://a.example.com/file.zip",
                "http://b.example.com/file.zip",
            ),
            Warmup.sourcesToWarm(
                "https://a.example.com/file.zip",
                listOf(
                    "ftp://c.example.com/file.zip",
                    "http://b.example.com/file.zip",
                ),
                proxyActive = false,
            ),
        )
    }

    @Test
    fun primaryAndMirrorsAreDedupedInOrder() {
        assertEquals(
            listOf(
                "https://a.example.com/file.zip",
                "https://b.example.com/file.zip",
                "https://c.example.com/file.zip",
            ),
            Warmup.sourcesToWarm(
                "https://a.example.com/file.zip",
                listOf(
                    "https://b.example.com/file.zip",
                    "https://a.example.com/file.zip",
                    "https://b.example.com/file.zip",
                    "https://c.example.com/file.zip",
                ),
                proxyActive = false,
            ),
        )
    }

    @Test
    fun allHttpsSourcesAreReturned() {
        val mirrors = listOf(
            "https://b.example.com/file.zip",
            "https://c.example.com/file.zip",
        )
        assertEquals(
            listOf("https://a.example.com/file.zip") + mirrors,
            Warmup.sourcesToWarm(
                "https://a.example.com/file.zip",
                mirrors,
                proxyActive = false,
            ),
        )
    }

    @Test
    fun totalIsCappedAtPrimaryPlusMaxSources() {
        val limit = 1 + TaskStore.MAX_SOURCES
        val mirrors = (1..(limit + 10)).map { "https://m$it.example.com/file.zip" }
        val warmed = Warmup.sourcesToWarm(
            "https://a.example.com/file.zip",
            mirrors,
            proxyActive = false,
        )
        assertEquals(limit, warmed.size)
        assertEquals("https://a.example.com/file.zip", warmed.first())
        // The cap keeps the FIRST mirrors in order, not a tail slice.
        assertEquals(mirrors[limit - 2], warmed.last())
    }

    @Test
    fun emptySourcesWarmsOnlyThePrimary() {
        assertEquals(
            listOf("https://a.example.com/file.zip"),
            Warmup.sourcesToWarm(
                "https://a.example.com/file.zip",
                emptyList(),
                proxyActive = false,
            ),
        )
    }

    // ---- v4.4: enqueue-time DNS pre-resolve decision ----

    @Test
    fun preResolveExtractsTheHost() {
        assertEquals(
            "a.example.com",
            Warmup.hostToPreResolve(
                "https://a.example.com/some/path/file.zip?x=1",
                proxyActive = false,
            ),
        )
        assertEquals(
            "b.example.com",
            Warmup.hostToPreResolve(
                "http://user@b.example.com:8080/file.zip",
                proxyActive = false,
            ),
        )
    }

    @Test
    fun preResolveSkipsBehindAProxy() {
        // Behind a proxy, resolution belongs to the proxy side;
        // a local lookup is useless and must not happen.
        assertEquals(
            null,
            Warmup.hostToPreResolve(
                "https://a.example.com/file.zip",
                proxyActive = true,
            ),
        )
    }

    @Test
    fun preResolveSkipsNonHttpAndHostlessUrls() {
        assertEquals(
            null,
            Warmup.hostToPreResolve("ftp://a.example.com/file.zip", proxyActive = false),
        )
        assertEquals(
            null,
            Warmup.hostToPreResolve("magnet:?xt=urn:btih:abc", proxyActive = false),
        )
        assertEquals(null, Warmup.hostToPreResolve("", proxyActive = false))
        assertEquals(null, Warmup.hostToPreResolve("https://", proxyActive = false))
        assertEquals(null, Warmup.hostToPreResolve("not a url", proxyActive = false))
    }

    // ---- v4.5: in-flight warm registry (WarmHosts) ----

    @Test
    fun warmHostsClaimBlocksTheSecondWarmerUntilRelease() {
        val host = "claim-${System.nanoTime()}.example.com"
        assertTrue(WarmHosts.tryClaim(host))
        // A second warmer (queue preheat vs engine warmup, in
        // either order) must not warm the same host at the
        // same moment.
        assertFalse(WarmHosts.tryClaim(host))
        WarmHosts.release(host)
        assertTrue(WarmHosts.tryClaim(host))
        WarmHosts.release(host)
    }

    @Test
    fun warmHostsAreCaseInsensitiveAndIndependent() {
        val a = "casea-${System.nanoTime()}.example.com"
        val b = "caseb-${System.nanoTime()}.example.com"
        assertTrue(WarmHosts.tryClaim(a.uppercase()))
        assertFalse(WarmHosts.tryClaim(a))
        // A different host is a different claim.
        assertTrue(WarmHosts.tryClaim(b))
        WarmHosts.release(a)
        WarmHosts.release(b)
        // Blank hosts can never be claimed.
        assertFalse(WarmHosts.tryClaim(""))
        assertFalse(WarmHosts.tryClaim("  "))
    }

    @Test
    fun nonHttpSchemesAndBlanksAreFiltered() {
        assertEquals(
            listOf("https://a.example.com/file.zip"),
            Warmup.sourcesToWarm(
                "https://a.example.com/file.zip",
                listOf("", "  ", "file:///sdcard/file.zip", "magnet:?xt=urn:btih:abc"),
                proxyActive = false,
            ),
        )
    }
}

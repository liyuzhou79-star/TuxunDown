package com.tuxundown.android

import com.tuxundown.android.core.RedirectChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.3: pins RedirectChain — the pure verdict behind short-link
 * expansion. Chains are walked exactly the way the network
 * driver walks them (judge one hop, follow, judge the next),
 * against a scripted url -> (code, Location) map, so the whole
 * chain behaviour is pinned without any network.
 */
class RedirectChainTest {

    /** Walk a scripted chain; returns the terminal verdict. */
    private fun walk(
        start: String,
        script: Map<String, Pair<Int, String?>>,
    ): RedirectChain.Verdict {
        var current = start
        val visited = mutableSetOf(RedirectChain.keyOf(start))
        var hops = 0
        while (true) {
            val (code, loc) = script[current] ?: (200 to null)
            when (val v = RedirectChain.judge(current, code, loc, visited, hops)) {
                is RedirectChain.Verdict.Follow -> {
                    current = v.nextUrl
                    visited.add(RedirectChain.keyOf(v.nextUrl))
                    hops++
                }
                else -> return v
            }
        }
    }

    @Test
    fun directLinkWithoutRedirectIsDoneAtItself() {
        val v = walk("https://ex.com/file.zip", emptyMap())
        assertEquals(
            RedirectChain.Verdict.Done("https://ex.com/file.zip"), v,
        )
    }

    @Test
    fun multiHopChainLandsOnTheFinalAddress() {
        val v = walk(
            "https://t.cn/abc",
            mapOf(
                "https://t.cn/abc" to (301 to "https://ex.com/landing"),
                "https://ex.com/landing" to (302 to "https://cdn.ex.com/file.zip"),
                "https://cdn.ex.com/file.zip" to (200 to null),
            ),
        )
        assertEquals(
            RedirectChain.Verdict.Done("https://cdn.ex.com/file.zip"), v,
        )
    }

    @Test
    fun loopBackToAnAlreadyAskedAddressFallsBack() {
        val v = walk(
            "https://a.ex.com/x",
            mapOf(
                "https://a.ex.com/x" to (302 to "https://b.ex.com/y"),
                "https://b.ex.com/y" to (302 to "https://a.ex.com/x"),
            ),
        )
        assertTrue(v is RedirectChain.Verdict.Fallback)
        assertEquals(
            RedirectChain.FallbackReason.LOOP,
            (v as RedirectChain.Verdict.Fallback).reason,
        )
    }

    @Test
    fun selfRedirectIsALoop() {
        val v = RedirectChain.judge(
            "https://ex.com/a", 301, "https://ex.com/a",
            setOf(RedirectChain.keyOf("https://ex.com/a")), 0,
        )
        assertEquals(
            RedirectChain.FallbackReason.LOOP,
            (v as RedirectChain.Verdict.Fallback).reason,
        )
    }

    @Test
    fun chainLongerThanTheCapFallsBack() {
        // 15 distinct hops, each one "new" (a counting query
        // would defeat loop detection the same way) — the cap
        // is the only thing that stops it, at exactly MAX_HOPS
        // followed redirects.
        val script = (0..15).associate { i ->
            "https://ex.com/h$i" to (302 to "https://ex.com/h${i + 1}")
        }
        val v = walk("https://ex.com/h0", script)
        assertTrue(v is RedirectChain.Verdict.Fallback)
        assertEquals(
            RedirectChain.FallbackReason.TOO_MANY_HOPS,
            (v as RedirectChain.Verdict.Fallback).reason,
        )
    }

    @Test
    fun chainExactlyAtTheCapStillCompletes() {
        // MAX_HOPS redirects followed, then a final 200: allowed.
        val script = (0 until RedirectChain.MAX_HOPS).associate { i ->
            "https://ex.com/h$i" to (302 to "https://ex.com/h${i + 1}")
        } + ("https://ex.com/h${RedirectChain.MAX_HOPS}" to (200 to null))
        val v = walk("https://ex.com/h0", script)
        assertEquals(
            RedirectChain.Verdict.Done(
                "https://ex.com/h${RedirectChain.MAX_HOPS}"
            ),
            v,
        )
    }

    @Test
    fun relativeLocationResolvesAgainstTheCurrentHop() {
        val v = RedirectChain.judge(
            "https://ex.com/a/b/page", 301, "/files/x.zip",
            setOf(RedirectChain.keyOf("https://ex.com/a/b/page")), 0,
        )
        assertEquals(
            RedirectChain.Verdict.Follow("https://ex.com/files/x.zip"), v,
        )
        val up = RedirectChain.judge(
            "https://ex.com/a/b/page", 302, "../x.zip",
            setOf(RedirectChain.keyOf("https://ex.com/a/b/page")), 0,
        )
        assertEquals(
            RedirectChain.Verdict.Follow("https://ex.com/a/x.zip"), up,
        )
    }

    @Test
    fun crossProtocolHopIsFollowed() {
        // The hop HttpURLConnection's own follower refuses:
        // http short link landing on an https file.
        val v = RedirectChain.judge(
            "http://t.cn/abc", 301, "https://cdn.ex.com/file.zip",
            setOf(RedirectChain.keyOf("http://t.cn/abc")), 0,
        )
        assertEquals(
            RedirectChain.Verdict.Follow("https://cdn.ex.com/file.zip"), v,
        )
    }

    @Test
    fun nonHttpTargetFallsBack() {
        val v = RedirectChain.judge(
            "https://ex.com/a", 302, "ftp://ex.com/file.zip",
            setOf(RedirectChain.keyOf("https://ex.com/a")), 0,
        )
        assertTrue(v is RedirectChain.Verdict.Fallback)
        assertEquals(
            RedirectChain.FallbackReason.NON_HTTP_TARGET,
            (v as RedirectChain.Verdict.Fallback).reason,
        )
    }

    @Test
    fun redirectWithoutLocationFallsBack() {
        val v = RedirectChain.judge(
            "https://ex.com/a", 301, null,
            setOf(RedirectChain.keyOf("https://ex.com/a")), 0,
        )
        assertEquals(
            RedirectChain.FallbackReason.NO_LOCATION,
            (v as RedirectChain.Verdict.Fallback).reason,
        )
    }

    @Test
    fun httpErrorFallsBackWithTheCode() {
        val v = RedirectChain.judge(
            "https://ex.com/gone", 404, null,
            setOf(RedirectChain.keyOf("https://ex.com/gone")), 0,
        )
        assertTrue(v is RedirectChain.Verdict.Fallback)
        val f = v as RedirectChain.Verdict.Fallback
        assertEquals(RedirectChain.FallbackReason.HTTP_ERROR, f.reason)
        assertEquals(404, f.httpCode)
    }
}

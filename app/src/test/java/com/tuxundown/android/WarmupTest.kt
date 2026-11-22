package com.tuxundown.android

import com.tuxundown.android.core.TaskStore
import com.tuxundown.android.core.Warmup
import org.junit.Assert.assertEquals
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

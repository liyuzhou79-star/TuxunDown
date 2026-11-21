package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.HostMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMemoryTest {

    private val now = 1_700_000_000_000L

    @Test
    fun hostOfParsesAndLowercases() {
        assertEquals("media.example.com", HostMemory.hostOf("https://Media.Example.com/a/b.zip"))
        assertEquals("", HostMemory.hostOf("not a url"))
    }

    @Test
    fun recordAndLookupRoundTrip() {
        var entries = HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        assertEquals(16, HostMemory.lookup(entries, "a.com", now + 1000))
        assertEquals(0, HostMemory.lookup(entries, "b.com", now + 1000))
        // A fresh success resets the failure streak.
        entries = HostMemory.recordFailure(entries, "a.com", now)
        entries = HostMemory.recordSuccess(entries, "a.com", 8, now)
        assertEquals(8, HostMemory.lookup(entries, "a.com", now + 1000))
    }

    @Test
    fun expiredEntryIsForgotten() {
        val entries = HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        assertEquals(0, HostMemory.lookup(entries, "a.com", now + HostMemory.TTL_MS + 1))
    }

    @Test
    fun twoConsecutiveFailuresForgetTheHost() {
        var entries = HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        entries = HostMemory.recordFailure(entries, "a.com", now)
        assertEquals(16, HostMemory.lookup(entries, "a.com", now + 1000)) // one strike: kept
        entries = HostMemory.recordFailure(entries, "a.com", now)
        assertEquals(0, HostMemory.lookup(entries, "a.com", now + 1000)) // two strikes: gone
        assertTrue(entries.isEmpty())
    }

    @Test
    fun threadsAreClampedToEngineRange() {
        val entries = HostMemory.recordSuccess(emptyList(), "a.com", 999, now)
        assertEquals(DownloadEngine.MAX_THREADS, HostMemory.lookup(entries, "a.com", now))
    }

    @Test
    fun serializeParseRoundTripAndGarbageTolerance() {
        var entries = HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        entries = HostMemory.recordSuccess(entries, "b.org", 4, now)
        val back = HostMemory.parse(HostMemory.serialize(entries))
        assertEquals(entries.sortedBy { it.host }, back.sortedBy { it.host })
        assertTrue(HostMemory.parse("garbage\n\t\t\n").isEmpty())
        assertTrue(HostMemory.parse("").isEmpty())
    }
}

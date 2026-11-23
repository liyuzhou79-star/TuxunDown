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

    // ---- v2.8: rate-limit ceiling ----

    @Test
    fun rateLimitRecordsHalfTheRejectedLoadWithAFloor() {
        var entries = HostMemory.recordRateLimit(emptyList(), "a.com", 32, now)
        assertEquals(16, HostMemory.capOf(entries, "a.com", now))
        // Rejected at 6 -> half is 3, but the floor is MIN_CAP.
        entries = HostMemory.recordRateLimit(emptyList(), "b.com", 6, now)
        assertEquals(HostMemory.MIN_CAP, HostMemory.capOf(entries, "b.com", now))
        // A cap-only entry teaches no optimum.
        assertEquals(0, HostMemory.lookup(entries, "b.com", now))
    }

    @Test
    fun freshEvidenceOnlyTightensTheCap() {
        var entries = HostMemory.recordRateLimit(emptyList(), "a.com", 32, now) // cap 16
        entries = HostMemory.recordRateLimit(entries, "a.com", 16, now) // -> 8
        assertEquals(8, HostMemory.capOf(entries, "a.com", now))
        // Rejected at a HIGHER level later must not loosen it.
        entries = HostMemory.recordRateLimit(entries, "a.com", 32, now)
        assertEquals(8, HostMemory.capOf(entries, "a.com", now))
    }

    @Test
    fun completionsAtTheCapRelaxItStepByStep() {
        var entries = HostMemory.recordRateLimit(emptyList(), "a.com", 32, now) // cap 16
        // Settling BELOW the cap proves nothing: no relaxation.
        entries = HostMemory.recordSuccess(entries, "a.com", 8, now)
        assertEquals(16, HostMemory.capOf(entries, "a.com", now))
        // Settling AT the cap earns +4.
        entries = HostMemory.recordSuccess(entries, "a.com", 16, now)
        assertEquals(20, HostMemory.capOf(entries, "a.com", now))
        entries = HostMemory.recordSuccess(entries, "a.com", 20, now)
        assertEquals(24, HostMemory.capOf(entries, "a.com", now))
        entries = HostMemory.recordSuccess(entries, "a.com", 24, now)
        assertEquals(28, HostMemory.capOf(entries, "a.com", now))
        // v4.2: the engine max is 64 now, so the steps keep going
        // +4 at a time instead of retiring at 28...
        var cap = 28
        while (cap < 60) {
            entries = HostMemory.recordSuccess(entries, "a.com", cap, now)
            cap += HostMemory.CAP_RELAX_STEP
            assertEquals(cap, HostMemory.capOf(entries, "a.com", now))
        }
        // ...until a step would reach the engine max: the cap retires.
        entries = HostMemory.recordSuccess(entries, "a.com", 60, now)
        assertEquals(0, HostMemory.capOf(entries, "a.com", now))
    }

    // ---- v4.2: cap learning at the raised 64 ceiling ----

    @Test
    fun rateLimitAt64RecordsCap32() {
        // Rejected while running the full 64: the ceiling is half.
        val entries = HostMemory.recordRateLimit(emptyList(), "a.com", 64, now)
        assertEquals(32, HostMemory.capOf(entries, "a.com", now))
        assertEquals(0, HostMemory.lookup(entries, "a.com", now))
    }

    @Test
    fun relaxNearTheTopRetiresOnlyAt64() {
        // A rejection at 112 teaches cap 56; one settled completion
        // at the cap relaxes it +4 to 60 — NOT retired yet, because
        // 56 + 4 = 60 < 64.
        var entries = HostMemory.recordRateLimit(emptyList(), "a.com", 112, now)
        assertEquals(56, HostMemory.capOf(entries, "a.com", now))
        entries = HostMemory.recordSuccess(entries, "a.com", 56, now)
        assertEquals(60, HostMemory.capOf(entries, "a.com", now))
        // Settling at 60: 60 + 4 >= 64, so the ceiling retires.
        entries = HostMemory.recordSuccess(entries, "a.com", 60, now)
        assertEquals(0, HostMemory.capOf(entries, "a.com", now))
    }

    @Test
    fun successThreadsClampAt64() {
        val entries = HostMemory.recordSuccess(emptyList(), "a.com", 100, now)
        assertEquals(64, HostMemory.lookup(entries, "a.com", now))
        assertEquals(DownloadEngine.MAX_THREADS, HostMemory.lookup(entries, "a.com", now))
        // And a settled 64 is remembered verbatim.
        val at64 = HostMemory.recordSuccess(emptyList(), "b.com", 64, now)
        assertEquals(64, HostMemory.lookup(at64, "b.com", now))
    }

    @Test
    fun capSurvivesSuccessAndFailureBookkeeping() {
        var entries = HostMemory.recordSuccess(emptyList(), "a.com", 24, now)
        entries = HostMemory.recordRateLimit(entries, "a.com", 24, now) // cap 12
        assertEquals(12, HostMemory.capOf(entries, "a.com", now))
        // The optimum survives the rate-limit record...
        assertEquals(24, HostMemory.lookup(entries, "a.com", now))
        // ...and the cap survives an ordinary failure strike.
        entries = HostMemory.recordFailure(entries, "a.com", now)
        assertEquals(12, HostMemory.capOf(entries, "a.com", now))
        // The cap expires with the entry.
        assertEquals(0, HostMemory.capOf(entries, "a.com", now + HostMemory.TTL_MS + 1))
    }

    @Test
    fun capRoundTripsAndLegacyLinesParse() {
        var entries = HostMemory.recordSuccess(emptyList(), "a.com", 24, now)
        entries = HostMemory.recordRateLimit(entries, "a.com", 24, now)
        val back = HostMemory.parse(HostMemory.serialize(entries))
        assertEquals(12, HostMemory.capOf(back, "a.com", now))
        // A pre-v2.8 four-field line parses with cap 0.
        val legacy = HostMemory.parse("old.com\t16\t1\t$now")
        assertEquals(16, HostMemory.lookup(legacy, "old.com", now))
        assertEquals(0, HostMemory.capOf(legacy, "old.com", now))
    }

    // ---- v4.3: manual apply from the connection-count comparison ----

    @Test
    fun manualApplyWritesThePickedCountVerbatim() {
        // 4 is below the usual probing settle range, but it is
        // what the user measured and picked: stored as-is.
        var entries = HostMemory.recordManual(emptyList(), "a.com", 4, now)
        assertEquals(4, HostMemory.lookup(entries, "a.com", now))
        entries = HostMemory.recordManual(entries, "a.com", 16, now)
        assertEquals(16, HostMemory.lookup(entries, "a.com", now))
        entries = HostMemory.recordManual(entries, "a.com", 64, now)
        assertEquals(64, HostMemory.lookup(entries, "a.com", now))
    }

    @Test
    fun manualApplyPreservesTheLearnedCap() {
        var entries = HostMemory.recordRateLimit(emptyList(), "a.com", 32, now) // cap 16
        entries = HostMemory.recordManual(entries, "a.com", 64, now)
        assertEquals(64, HostMemory.lookup(entries, "a.com", now))
        // A manual pick is not completion evidence: the ceiling
        // is NOT relaxed even though the pick sits above it.
        assertEquals(16, HostMemory.capOf(entries, "a.com", now))
    }

    @Test
    fun manualApplyResetsTheFailureStreak() {
        var entries = HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        entries = HostMemory.recordFailure(entries, "a.com", now)
        assertEquals(1, entries.first { it.host == "a.com" }.fails)
        entries = HostMemory.recordManual(entries, "a.com", 8, now)
        assertEquals(0, entries.first { it.host == "a.com" }.fails)
    }

    @Test
    fun manualApplyClampsToTheEngineRange() {
        val over = HostMemory.recordManual(emptyList(), "a.com", 999, now)
        assertEquals(DownloadEngine.MAX_THREADS, HostMemory.lookup(over, "a.com", now))
        val under = HostMemory.recordManual(emptyList(), "a.com", 0, now)
        assertEquals(1, HostMemory.lookup(under, "a.com", now))
    }

    @Test
    fun manualApplyBlankHostAndOtherHostsUntouched() {
        var entries = HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        entries = HostMemory.recordSuccess(entries, "b.com", 8, now)
        val before = entries
        assertEquals(before, HostMemory.recordManual(entries, "  ", 32, now))
        entries = HostMemory.recordManual(entries, "a.com", 4, now)
        assertEquals(4, HostMemory.lookup(entries, "a.com", now))
        assertEquals(8, HostMemory.lookup(entries, "b.com", now))
    }

    @Test
    fun manualApplySurvivesSerializationRoundTrip() {
        var entries = HostMemory.recordRateLimit(emptyList(), "a.com", 32, now)
        entries = HostMemory.recordManual(entries, "a.com", 4, now)
        val back = HostMemory.parse(HostMemory.serialize(entries))
        assertEquals(4, HostMemory.lookup(back, "a.com", now))
        assertEquals(16, HostMemory.capOf(back, "a.com", now))
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

    // ---- v5.1 audit round 36: parse cache (see
    // HostMemoryParseCache) — the host table must be parsed
    // once per actual change, never per read. ----

    @Test
    fun parseCacheReturnsSameInstanceWhileTextUnchanged() {
        val cache = com.tuxundown.android.core.HostMemoryParseCache()
        val text = HostMemory.serialize(
            HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        )
        val first = cache.get(text)
        assertEquals(1, cache.version)
        val second = cache.get(text)
        org.junit.Assert.assertSame(first, second)
        assertEquals(1, cache.version)
        assertEquals(16, HostMemory.lookup(second, "a.com", now))
    }

    @Test
    fun parseCacheReparsesOnlyWhenTextChanges() {
        val cache = com.tuxundown.android.core.HostMemoryParseCache()
        val t1 = HostMemory.serialize(
            HostMemory.recordSuccess(emptyList(), "a.com", 16, now)
        )
        val t2 = HostMemory.serialize(
            HostMemory.recordSuccess(emptyList(), "a.com", 8, now)
        )
        cache.get(t1)
        val changed = cache.get(t2)
        assertEquals(2, cache.version)
        assertEquals(8, HostMemory.lookup(changed, "a.com", now))
        // Clearing the table is a change like any other.
        val cleared = cache.get("")
        assertTrue(cleared.isEmpty())
        assertEquals(3, cache.version)
        // And an unchanged empty read stays cached.
        org.junit.Assert.assertSame(cleared, cache.get(""))
        assertEquals(3, cache.version)
    }
}

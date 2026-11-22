package com.tuxundown.android

import com.tuxundown.android.core.FreeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeNameTest {

    @Test
    fun noConflictReturnsTheNameItself() {
        assertEquals("a.zip", FreeName.pick("a.zip", 123L) { false })
    }

    @Test
    fun numberedSeriesSkipsTakenNames() {
        val taken = setOf("a.zip", "a (1).zip", "a (2).zip")
        assertEquals("a (3).zip", FreeName.pick("a.zip", 123L) { it in taken })
    }

    @Test
    fun extensionlessNamesNumberOnTheWholeName() {
        val taken = setOf("README", "README (1)")
        assertEquals("README (2)", FreeName.pick("README", 123L) { it in taken })
    }

    @Test
    fun lastNumberedSlotIsStillReachable() {
        // Everything numbered below the cap is taken; the cap
        // slot itself is free and must win over the fallback.
        val free = "a (${FreeName.MAX_NUMBERED}).zip"
        assertEquals(free, FreeName.pick("a.zip", 123L) { it != free })
    }

    @Test
    fun pastTheCapFallsBackToATimestampSuffix() {
        // Every plain / numbered name is taken; only names
        // carrying the timestamp are free.
        val picked = FreeName.pick("a.zip", 4242L) { candidate ->
            !candidate.contains("4242")
        }
        assertEquals("a-4242.zip", picked)
    }

    @Test
    fun timestampFallbackNumbersWhenTheStampIsTakenToo() {
        val free = "a-4242 (1).zip"
        val picked = FreeName.pick("a.zip", 4242L) { it != free }
        assertEquals(free, picked)
    }

    @Test
    fun everythingTakenThrowsInsteadOfOverwriting() {
        assertThrows(IllegalStateException::class.java) {
            FreeName.pick("a.zip", 4242L) { true }
        }
    }
}

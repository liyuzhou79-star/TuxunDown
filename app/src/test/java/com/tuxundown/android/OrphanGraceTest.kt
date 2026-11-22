package com.tuxundown.android

import com.tuxundown.android.core.OrphanGrace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanGraceTest {

    private val now = 1_000_000_000L

    @Test
    fun justTouchedIsFresh() {
        assertTrue(OrphanGrace.isFresh(now - 1_000, now))
        assertTrue(OrphanGrace.isFresh(now, now))
    }

    @Test
    fun graceBoundaryIsExclusive() {
        assertTrue(OrphanGrace.isFresh(now - OrphanGrace.GRACE_MS + 1, now))
        assertFalse(OrphanGrace.isFresh(now - OrphanGrace.GRACE_MS, now))
    }

    @Test
    fun oldPartIsNotFresh() {
        assertFalse(OrphanGrace.isFresh(now - 10 * 60_000, now))
    }

    @Test
    fun unknownAgeIsNotFresh() {
        // A provider reporting no timestamp keeps the pre-grace
        // behaviour instead of hiding its orphans forever.
        assertFalse(OrphanGrace.isFresh(0, now))
        assertFalse(OrphanGrace.isFresh(-1, now))
    }

    @Test
    fun futureTimestampCountsAsFresh() {
        // Clock skew must err towards never deleting a live file.
        assertTrue(OrphanGrace.isFresh(now + 5_000, now))
    }
}

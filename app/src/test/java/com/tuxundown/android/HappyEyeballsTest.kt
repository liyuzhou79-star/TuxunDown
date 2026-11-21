package com.tuxundown.android

import com.tuxundown.android.core.HappyEyeballs
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v2.6: dual-stack racing — plan order and race semantics. */
class HappyEyeballsTest {

    private fun v4(s: String): InetAddress = InetAddress.getByName(s)
    private fun v6(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun bothFamiliesDetection() {
        assertTrue(
            HappyEyeballs.hasBothFamilies(listOf(v4("1.2.3.4"), v6("2001:db8::1")))
        )
        assertFalse(HappyEyeballs.hasBothFamilies(listOf(v4("1.2.3.4"), v4("5.6.7.8"))))
        assertFalse(HappyEyeballs.hasBothFamilies(listOf(v6("2001:db8::1"))))
        assertFalse(HappyEyeballs.hasBothFamilies(emptyList()))
    }

    @Test
    fun orderKeepsResolversFirstChoiceAndAlternates() {
        val a = v6("2001:db8::1")
        val b = v6("2001:db8::2")
        val c = v4("1.2.3.4")
        val d = v4("5.6.7.8")
        assertEquals(listOf(a, c, b, d), HappyEyeballs.order(listOf(a, b, c, d)))
        // v4-first resolution stays v4-first.
        assertEquals(listOf(c, a, d, b), HappyEyeballs.order(listOf(c, d, a, b)))
        // Single-family lists pass through untouched.
        assertEquals(listOf(c, d), HappyEyeballs.order(listOf(c, d)))
    }

    @Test
    fun racePicksTheFastestNotTheFirst() {
        val disposed = CountDownLatch(1)
        val winner = HappyEyeballs.race(
            listOf<() -> String>(
                { Thread.sleep(400); "slow-first" },
                { Thread.sleep(20); "fast-second" },
            ),
            staggerMs = 30,
            deadlineMs = 5_000,
            dispose = { disposed.countDown() },
        )
        assertEquals("fast-second", winner)
        // The loser, when it finally lands, is disposed — not leaked.
        assertTrue(disposed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun raceSkipsFailures() {
        val winner = HappyEyeballs.race(
            listOf<() -> String>(
                { throw IOException("v6 dead") },
                { "v4 alive" },
            ),
            staggerMs = 10,
            deadlineMs = 5_000,
        )
        assertEquals("v4 alive", winner)
    }

    @Test
    fun raceThrowsTheFirstFailureWhenAllFail() {
        try {
            HappyEyeballs.race(
                listOf<() -> String>(
                    { throw IOException("first family's verdict") },
                    { throw IOException("second") },
                ),
                staggerMs = 10,
                deadlineMs = 5_000,
            )
            fail("expected the race to throw")
        } catch (e: IOException) {
            assertEquals("first family's verdict", e.message)
        }
    }

    @Test
    fun raceHonoursTheDeadline() {
        try {
            HappyEyeballs.race(
                listOf<() -> String>(
                    { Thread.sleep(3_000); "too late" },
                    { Thread.sleep(3_000); "also late" },
                ),
                staggerMs = 10,
                deadlineMs = 150,
            )
            fail("expected a deadline failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("超时"))
        }
    }

    @Test
    fun singleAttemptRunsInline() {
        assertEquals(
            "only",
            HappyEyeballs.race(listOf<() -> String>({ "only" })),
        )
    }
}

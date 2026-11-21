package com.tuxundown.android

import com.tuxundown.android.core.EngineClaims
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1 audit C1: one task, one engine — the claim set is the invariant. */
class EngineClaimsTest {

    @Test
    fun secondClaimRefusedUntilRelease() {
        val claims = EngineClaims()
        assertTrue(claims.claim("a"))
        assertFalse("a second run for the same task must be refused", claims.claim("a"))
        assertTrue(claims.isClaimed("a"))
        claims.release("a")
        assertFalse(claims.isClaimed("a"))
        assertTrue("after release the task can be claimed again", claims.claim("a"))
    }

    @Test
    fun claimsArePerTask() {
        val claims = EngineClaims()
        assertTrue(claims.claim("a"))
        assertTrue("a different task is unaffected", claims.claim("b"))
        claims.release("a")
        assertTrue(claims.isClaimed("b"))
        assertFalse(claims.isClaimed("a"))
    }

    @Test
    fun releaseOfUnclaimedIsHarmless() {
        val claims = EngineClaims()
        claims.release("never-claimed")
        assertFalse(claims.isClaimed("never-claimed"))
        assertTrue(claims.claim("never-claimed"))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.LostNetworkJudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.6 (audit round 51): the default-network-lost fast-fail
 * verdict. The judge is deliberately conservative — these
 * tests pin every direction of that conservatism, because a
 * misfire would cut healthy downloads on unrelated network
 * churn.
 */
class LostNetworkJudgeTest {

    @Test
    fun losingTheTrackedDefaultFires() {
        val judge = LostNetworkJudge()
        judge.noteDefault("wifi")
        assertTrue(judge.onLost("wifi"))
    }

    @Test
    fun losingANonDefaultNetworkNeverFires() {
        // The dual-channel cellular leg dropping while WiFi is
        // the default, a VPN going away — same shape: the lost
        // key is not the tracked default.
        val judge = LostNetworkJudge()
        judge.noteDefault("wifi")
        assertFalse(judge.onLost("cellular"))
        assertFalse(judge.onLost("vpn"))
        // And the tracker still holds the default afterwards:
        // the real default loss later must still fire.
        assertEquals("wifi", judge.trackedDefault())
        assertTrue(judge.onLost("wifi"))
    }

    @Test
    fun lossWithUnknownIdentityNeverFires() {
        // Nothing tracked yet (no onAvailable /
        // onCapabilitiesChanged has resolved a default): stay
        // quiet and let the standing timeout machinery judge.
        val judge = LostNetworkJudge()
        assertNull(judge.trackedDefault())
        assertFalse(judge.onLost("wifi"))
        assertFalse(judge.onLost("cellular"))
    }

    @Test
    fun aFiredLossFiresOnlyOnce() {
        val judge = LostNetworkJudge()
        judge.noteDefault("wifi")
        assertTrue(judge.onLost("wifi"))
        // The verdict consumed the tracked default: a duplicate
        // onLost for the same network must not cut twice.
        assertNull(judge.trackedDefault())
        assertFalse(judge.onLost("wifi"))
    }

    @Test
    fun retrackedDefaultAfterASwitchIsTheNewNetwork() {
        // WiFi -> cellular failover: once the new default is
        // tracked, the OLD network's late onLost must not fire,
        // and the NEW default's loss must.
        val judge = LostNetworkJudge()
        judge.noteDefault("wifi")
        judge.noteDefault("cellular")
        assertFalse(judge.onLost("wifi"))
        assertTrue(judge.onLost("cellular"))
    }

    @Test
    fun cellularAsDefaultFiresWhenItIsLost() {
        // No WiFi at all: the cellular network IS the default,
        // so its loss is a default loss (the judge keys on
        // "is default", never on the transport type — the
        // separate cellular-leg callback only covers the leg
        // while something else is default).
        val judge = LostNetworkJudge()
        judge.noteDefault("cellular")
        assertTrue(judge.onLost("cellular"))
    }
}

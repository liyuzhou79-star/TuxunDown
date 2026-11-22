package com.tuxundown.android

import android.os.BatteryManager
import com.tuxundown.android.core.ChargeGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.9: the charge-only gate's decisions (see ChargeGuard).
 * The switch only ever parks when it is on AND power is out;
 * "charging" means CHARGING or FULL (plugged in at 100% is
 * still plugged in).
 */
class ChargeGuardTest {

    @Test
    fun parksOnlyWhenSwitchOnAndUnplugged() {
        assertTrue(ChargeGuard.shouldPark(chargeOnly = true, charging = false))
        assertFalse(ChargeGuard.shouldPark(chargeOnly = true, charging = true))
        assertFalse(ChargeGuard.shouldPark(chargeOnly = false, charging = false))
        assertFalse(ChargeGuard.shouldPark(chargeOnly = false, charging = true))
    }

    @Test
    fun chargingMeansChargingOrFull() {
        assertTrue(ChargeGuard.isCharging(BatteryManager.BATTERY_STATUS_CHARGING))
        assertTrue(ChargeGuard.isCharging(BatteryManager.BATTERY_STATUS_FULL))
        assertFalse(ChargeGuard.isCharging(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertFalse(ChargeGuard.isCharging(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertFalse(ChargeGuard.isCharging(BatteryManager.BATTERY_STATUS_UNKNOWN))
        assertFalse(ChargeGuard.isCharging(-1))
    }
}

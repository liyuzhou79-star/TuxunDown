package com.tuxundown.android.core

import android.os.BatteryManager

/**
 * Charge-only downloads (v3.9): the pure decisions behind the
 * 充电时才下载 switch. The service reads the battery state
 * from the sticky ACTION_BATTERY_CHANGED broadcast (no
 * permission, no registered receiver — see
 * DownloadCenter.isCharging) and asks here what it means.
 *
 * The gate's shape mirrors the older gates on purpose: like
 * 仅 WiFi it holds only tasks that have NOT started yet —
 * unplugging mid-download never interrupts a running transfer —
 * and like the outage parking (NetRetry) the task simply waits
 * in the queue with a plain note on its card until the missing
 * condition returns, then starts on its own.
 */
object ChargeGuard {

    /** Park a not-yet-started task only when the switch is on
     *  AND power is disconnected. */
    fun shouldPark(chargeOnly: Boolean, charging: Boolean): Boolean =
        chargeOnly && !charging

    /**
     * Sticky-broadcast status → "external power is connected".
     * FULL counts: a full battery still on the cable is plugged
     * in, and the user who enabled the switch means "on power",
     * not "actively sipping charge". Anything else (discharging,
     * not charging, unknown, a missing extra) is not power.
     */
    fun isCharging(batteryStatus: Int): Boolean =
        batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
}

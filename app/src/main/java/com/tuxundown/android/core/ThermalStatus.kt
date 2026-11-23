package com.tuxundown.android.core

import android.content.Context
import android.os.PowerManager

/**
 * Device thermal status, made visible (v6.1, audit round 46).
 *
 * Why this exists: charging + a large download is the hottest
 * scenario this app creates, and when the phone thermally
 * throttles, downloads slow down for reasons that have nothing
 * to do with the network — but until now the app sensed none of
 * it, so a throttled slowdown looked exactly like a bad link.
 * This file only ever SURFACES the state. It never pauses,
 * slows or otherwise touches a transfer: what to do about a
 * hot phone is the user's decision, not the app's.
 *
 * Source: [PowerManager.getCurrentThermalStatus] (API 29;
 * minSdk is 29, so the API exists on every supported device —
 * no version guard). It needs NO permission (verified in audit
 * round 46 against the official reference: the thermal methods
 * carry no permission requirement, unlike newWakeLock on the
 * same page). No listener is registered anywhere: the health
 * check reads once per tap, and the notification hint reads
 * once per already-throttled aggregate post (see
 * DownloadService.notifyAggregate) — no new polling loop.
 *
 * Band mapping — the seven platform statuses collapse to the
 * three bands a user can act on:
 * - NONE / LIGHT -> NORMAL （正常）: no throttling, or so mild
 *   it does not limit performance in any way a download could
 *   feel. LIGHT means "minor throttling, no user impact" in
 *   the platform's own wording, so it belongs with normal.
 * - MODERATE -> WARM （偏热）: the platform is throttling
 *   "not severe" — performance is being trimmed but the
 *   device is coping; worth knowing, not yet the explanation
 *   for a big slowdown.
 * - SEVERE / CRITICAL / EMERGENCY / SHUTDOWN -> THROTTLING
 *   （发热降速中）: from SEVERE up the platform says the
 *   throttling is major / the device is actively shedding
 *   load to protect itself. This is the band where a download
 *   slowdown may well be the phone, not the network — the
 *   only band the running-download hint fires on.
 *
 * Absent-on-failure caliber: any read failure, a missing
 * PowerManager, or a status value outside the seven known
 * constants (a future platform could add one) yields null,
 * and every surface treats null as "no thermal item at all" —
 * the state is never guessed or faked. An OEM thermal HAL
 * that always reports NONE is the platform's own answer and
 * is shown as 正常； that is honest — it is what the system
 * reports — and it is also why the hint can only ever help,
 * never mislead in the other direction.
 */
object ThermalStatus {

    /** The three user-facing bands, in rising heat order. */
    enum class Band(val label: String) {
        NORMAL("正常"),
        WARM("偏热"),
        THROTTLING("发热降速中"),
    }

    /**
     * Pure mapping from a platform thermal status constant to
     * a band (see the file doc for the reasoning). Returns null
     * for any value outside the seven known constants, so an
     * unknown future status reads as "cannot judge" instead of
     * being silently bucketed. The platform constants are
     * compile-time ints, so this function is plain JVM logic
     * and unit-tested directly.
     */
    fun bandOf(status: Int): Band? = when (status) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT -> Band.NORMAL
        PowerManager.THERMAL_STATUS_MODERATE -> Band.WARM
        PowerManager.THERMAL_STATUS_SEVERE,
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> Band.THROTTLING
        else -> null
    }

    /**
     * The current band, read once from the platform. Null when
     * the status cannot be read (service missing, OEM failure,
     * unknown status value) — callers must treat null as
     * "thermal item absent", never as a default band. Wrapped
     * in runCatching: a thermal read must never be able to
     * break a download, a notification or a health check.
     */
    fun currentBand(context: Context): Band? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@runCatching null
        bandOf(pm.currentThermalStatus)
    }.getOrNull()

    /** The one honest line shown while a download runs in the
     *  THROTTLING band. Hint only — nothing in the app acts on
     *  it automatically. */
    const val HINT = "手机发热降速中，速度变慢可能不是网络的问题"

    /** The hint for a band: only THROTTLING earns one; NORMAL /
     *  WARM / unreadable (null) all stay silent. */
    fun hintFor(band: Band?): String? =
        if (band == Band.THROTTLING) HINT else null
}

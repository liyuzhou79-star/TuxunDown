package com.tuxundown.android.core

/**
 * Mobile-data protection (v1.4): on a metered (non-WiFi) network, a
 * task whose total size exceeds the user's threshold is paused
 * BEFORE the bytes flow, unless the user already confirmed this
 * task on mobile data. Pure decision — unit-tested.
 */
object TrafficGuard {

    /**
     * @param onWifi current network is WiFi
     * @param totalSize task size in bytes; <= 0 (unknown) never blocks
     * @param thresholdMB user threshold in MB; <= 0 disables the guard
     * @param confirmed the user explicitly accepted this task on mobile
     */
    fun shouldBlock(
        onWifi: Boolean,
        totalSize: Long,
        thresholdMB: Int,
        confirmed: Boolean,
    ): Boolean {
        if (onWifi || confirmed) return false
        if (thresholdMB <= 0 || totalSize <= 0) return false
        return totalSize > thresholdMB.toLong() * 1024 * 1024
    }
}

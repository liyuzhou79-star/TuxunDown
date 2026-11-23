package com.tuxundown.android.core

/**
 * v6.6 (audit round 51): which network loss should fail the
 * running engines' in-flight connections fast — the verdict
 * behind the main network callback's new onLost in
 * DownloadService and DownloadEngine.failInFlightOnNetworkLost.
 *
 * Pure logic over OPAQUE identity keys: the service maps each
 * android.net.Network to a key and resolves WHICH network is
 * the default (via ConnectivityManager.activeNetwork — the
 * callback fires for every network, so its own argument never
 * identifies the default); this class never sees an Android
 * type, so the whole verdict is unit-tested on the JVM.
 *
 * Conservative by construction — fast-fail fires ONLY when the
 * lost network is the very network currently tracked as the
 * default:
 * - a secondary network going away (the dual-channel cellular
 *   leg while WiFi is default — that leg has its own callback
 *   and its own onLost — a VPN dropping, any other network)
 *   never fires;
 * - a loss while NO default has been tracked yet (identity
 *   unknown) never fires: stay quiet and let the standing
 *   machinery (30 s read timeout, StallPolicy) judge, exactly
 *   as before v6.6;
 * - a fired loss FORGETS the tracked default, so a duplicate
 *   onLost for the same network cannot fire twice; the next
 *   onAvailable / onCapabilitiesChanged re-tracks whatever is
 *   default then.
 *
 * Methods are synchronized: callbacks are expected on one
 * framework thread, but the guard costs nothing and the class
 * makes no assumption about its caller.
 */
class LostNetworkJudge {

    private var defaultKey: String? = null

    /** A network became available / its capabilities changed:
     *  [key] is the CURRENT default network's key, as resolved
     *  by the caller at that moment. */
    @Synchronized
    fun noteDefault(key: String) {
        defaultKey = key
    }

    /** The tracked default's key right now (null = unknown). */
    @Synchronized
    fun trackedDefault(): String? = defaultKey

    /**
     * A network was lost. Returns true — exactly once — when
     * [lostKey] IS the tracked default; any other loss leaves
     * the tracker untouched and returns false.
     */
    @Synchronized
    fun onLost(lostKey: String): Boolean {
        val cur = defaultKey ?: return false
        if (cur != lostKey) return false
        defaultKey = null
        return true
    }
}

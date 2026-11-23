package com.tuxundown.android.core

import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * v6.6 (audit round 51): the live connections of ONE engine
 * run — every HttpURLConnection the engine opens (all of them
 * pass through openFollowingRedirects, the engine's single
 * opening throat) and every FtpClient session it starts.
 * Registered at open, unregistered the moment the engine is
 * done with the connection (disconnect, warm keep-alive
 * release, body fully consumed, session close), so the set
 * holds only connections a blocked read could be sitting on.
 *
 * Its one job: when the service learns the DEFAULT network
 * died (LostNetworkJudge's verdict), the engine drains this
 * registry and cuts everything in it — each blocked read
 * wakes with a SocketException and flows through the engine's
 * EXISTING retry / FailureKind / NetRetry machinery. This
 * class decides nothing; it is a phone book, not a policy.
 *
 * HONEST SCOPE: an entry does not record WHICH leg a
 * dual-channel connection rides (default vs the cellular
 * extraOpener) — HttpURLConnection exposes no such thing once
 * opened. A default-network loss therefore also cuts an odd
 * connection currently riding the still-alive cellular leg;
 * that connection's worker simply retries its chunk from the
 * offset already written. One wasted attempt, never wrong
 * bytes.
 *
 * Thread-safe (concurrent sets): workers register and
 * unregister while the connectivity callback thread drains.
 * Unregister is idempotent and draining empties the registry,
 * so a connection the stall sampler already cut (its eviction
 * unregisters through the same engine helper) is simply
 * absent — and disconnecting a connection twice would be
 * harmless anyway.
 */
class InFlightConnections {

    private val http: MutableSet<HttpURLConnection> =
        ConcurrentHashMap.newKeySet()
    private val ftp: MutableSet<FtpClient> =
        ConcurrentHashMap.newKeySet()

    fun register(c: HttpURLConnection) {
        http.add(c)
    }

    fun unregister(c: HttpURLConnection) {
        http.remove(c)
    }

    fun registerFtp(c: FtpClient) {
        ftp.add(c)
    }

    fun unregisterFtp(c: FtpClient) {
        ftp.remove(c)
    }

    fun httpCount(): Int = http.size

    fun ftpCount(): Int = ftp.size

    /**
     * Snapshot + clear the HTTP side: everything in flight at
     * this instant. A connection registered after the drain is
     * not in the list — it opened after the loss was judged
     * (on whatever network is current then) and keeps the
     * standing timeout machinery as its backstop.
     */
    fun drainHttp(): List<HttpURLConnection> {
        val out = ArrayList<HttpURLConnection>()
        val it = http.iterator()
        while (it.hasNext()) {
            out.add(it.next())
            it.remove()
        }
        return out
    }

    /** Snapshot + clear the FTP side (see [drainHttp]). */
    fun drainFtp(): List<FtpClient> {
        val out = ArrayList<FtpClient>()
        val it = ftp.iterator()
        while (it.hasNext()) {
            out.add(it.next())
            it.remove()
        }
        return out
    }
}

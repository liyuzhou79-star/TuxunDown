package com.tuxundown.android.core

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * v2.6: Happy Eyeballs (RFC 8305) for download connects —
 * when a host answers on BOTH IPv6 and IPv4, the two families
 * race instead of the app waiting out a broken/slow family
 * before trying the other.
 *
 * What java.net honestly allows, and where the line is:
 * - HTTPS: [RacingSocketFactory] replaces the TLS socket
 *   factory on the connection. The factory resolves the host,
 *   races raw TCP connects across families (staggered, first
 *   success wins, losers closed), then layers TLS over the
 *   winning socket with the platform factory — SNI set,
 *   hostname verification still done by the connection stack
 *   afterwards, exactly as before. Any failure anywhere in
 *   the race falls back to the platform's own connect, so the
 *   worst case is today's behaviour.
 * - Plain HTTP: there is NO public hook to hand
 *   HttpURLConnection a pre-connected socket or a socket
 *   factory, so plain-HTTP connects keep the platform path
 *   unchanged. Stated plainly rather than faked.
 * - Through a proxy the proxy resolves and connects, and on
 *   the dual-channel leg the Network stack does its own
 *   thing; both are skipped by construction (the factory is
 *   only installed on the direct HTTPS path).
 * - A host with only one family never races: the factory
 *   delegates immediately.
 *
 * The plan ([order], [hasBothFamilies]) and the race itself
 * are pure / JVM-testable; only the factory glue is Android.
 */
object HappyEyeballs {

    /** Delay between successive connection attempts. */
    const val STAGGER_MS = 250L

    /** Per-attempt TCP connect timeout. */
    const val CONNECT_TIMEOUT_MS = 10_000

    /** Whole-race deadline; past it the caller falls back. */
    const val DEADLINE_MS = 12_000L

    fun hasBothFamilies(addresses: List<InetAddress>): Boolean =
        // NB: no Inet4Address here — it is not public API in
        // Android's java.net. An address from getAllByName is
        // one family or the other, so "not v6" IS v4.
        addresses.any { it is Inet6Address } && addresses.any { it !is Inet6Address }

    /**
     * Attempt order: the resolver's first choice stays first
     * (its family is presumably preferred), then families
     * alternate so a dead first family costs one stagger, not
     * a full timeout per same-family address.
     */
    fun order(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size < 2) return addresses
        val firstIsV6 = addresses.first() is Inet6Address
        val same = addresses.filter { (it is Inet6Address) == firstIsV6 }
        val other = addresses.filter { (it is Inet6Address) != firstIsV6 }
        val out = ArrayList<InetAddress>(addresses.size)
        var i = 0
        var j = 0
        while (i < same.size || j < other.size) {
            if (i < same.size) out.add(same[i++])
            if (j < other.size) out.add(other[j++])
        }
        return out
    }

    /**
     * Race [attempts]: attempt k starts [staggerMs] × k after
     * the race begins (unless someone already won). The first
     * success wins and is returned; results that finish after
     * the race was decided are handed to [dispose]. When every
     * attempt fails, the FIRST attempt's failure is thrown (it
     * is the resolver-preferred family's verdict); when the
     * deadline passes first, an [IOException] is thrown.
     */
    fun <T> race(
        attempts: List<() -> T>,
        staggerMs: Long = STAGGER_MS,
        deadlineMs: Long = DEADLINE_MS,
        dispose: (T) -> Unit = {},
    ): T {
        require(attempts.isNotEmpty()) { "no attempts" }
        if (attempts.size == 1) return attempts[0]()
        val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "happy-eyeballs").apply { isDaemon = true }
        }
        val won = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val winner = AtomicReference<T?>()
        val firstError = AtomicReference<Throwable?>()
        val failures = AtomicInteger(0)
        try {
            attempts.forEachIndexed { idx, attempt ->
                executor.execute {
                    if (idx > 0 && staggerMs > 0) {
                        try {
                            Thread.sleep(staggerMs * idx)
                        } catch (e: InterruptedException) {
                            return@execute
                        }
                    }
                    if (won.get()) return@execute
                    try {
                        val v = attempt()
                        if (won.compareAndSet(false, true)) {
                            winner.set(v)
                            latch.countDown()
                        } else {
                            runCatching { dispose(v) }
                        }
                    } catch (t: Throwable) {
                        if (idx == 0) firstError.compareAndSet(null, t)
                        if (failures.incrementAndGet() == attempts.size) {
                            latch.countDown()
                        }
                    }
                }
            }
            if (!latch.await(deadlineMs, TimeUnit.MILLISECONDS)) {
                // Claim the race so late finishers dispose of
                // themselves instead of leaking.
                won.set(true)
                throw IOException("连接超时（双栈竞速没有一路连上）")
            }
            winner.get()?.let { return it }
            throw firstError.get() ?: IOException("连接失败")
        } finally {
            // shutdown(), NOT shutdownNow(): an attempt already
            // in flight must be allowed to finish so it can hand
            // its result to dispose (a real socket connect does
            // not stop on interrupt anyway — it would complete
            // and leak if we had already torn the pool down).
            // Attempts still waiting out their stagger bail on
            // the won flag; the threads are daemons regardless.
            executor.shutdown()
        }
    }
}

/**
 * TLS socket factory that races the TCP connect across
 * address families, then lets the platform factory layer TLS
 * over the winner. Installed per-connection by the engine's
 * direct HTTPS path only (see HappyEyeballs for the scope).
 * Every overload other than (host, port) delegates untouched.
 */
class RacingSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> =
        delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> =
        delegate.supportedCipherSuites

    override fun createSocket(): Socket = delegate.createSocket()

    override fun createSocket(host: String, port: Int): Socket =
        raceConnect(host, port) ?: delegate.createSocket(host, port)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = delegate.createSocket(host, port, localHost, localPort)

    override fun createSocket(address: InetAddress, port: Int): Socket =
        delegate.createSocket(address, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = delegate.createSocket(address, port, localAddress, localPort)

    override fun createSocket(
        socket: Socket,
        host: String,
        port: Int,
        autoClose: Boolean,
    ): Socket = delegate.createSocket(socket, host, port, autoClose)

    /** The raced, TLS-layered socket, or null to fall back to
     *  the platform's own connect (single-family host, DNS
     *  failure, race failure, TLS layering failure — every
     *  fallback lands on exactly today's behaviour). */
    private fun raceConnect(host: String, port: Int): Socket? {
        val addresses = runCatching {
            InetAddress.getAllByName(host).toList()
        }.getOrNull() ?: return null
        if (!HappyEyeballs.hasBothFamilies(addresses)) return null
        // Explicit <Socket> + a typed dispose lambda: the nested
        // lambda-in-lambda shape below defeats type inference
        // otherwise (v2.6 step C's first CI run proved it).
        val plain: Socket = try {
            HappyEyeballs.race<Socket>(
                HappyEyeballs.order(addresses).map { addr ->
                    {
                        val s = Socket()
                        s.connect(
                            java.net.InetSocketAddress(addr, port),
                            HappyEyeballs.CONNECT_TIMEOUT_MS,
                        )
                        s
                    }
                },
                dispose = { s: Socket -> runCatching { s.close() } },
            )
        } catch (e: Exception) {
            return null
        }
        return runCatching {
            val tls = delegate.createSocket(plain, host, port, true) as SSLSocket
            // Name the host for SNI before any handshake: a
            // virtual-hosted server picks its certificate by
            // this name. (The stack's hostname verification of
            // the returned certificate is a separate, later
            // step and is untouched.)
            val params = tls.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            tls.sslParameters = params
            tls
        }.getOrElse {
            runCatching { plain.close() }
            null
        }
    }

    companion object {
        /** Shared instance wrapping the platform default factory. */
        val shared: SSLSocketFactory by lazy {
            RacingSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
        }
    }
}

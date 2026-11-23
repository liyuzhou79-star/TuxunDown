package com.tuxundown.android

import com.tuxundown.android.core.FtpClient
import com.tuxundown.android.core.InFlightConnections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * v6.6 (audit round 51): the per-engine in-flight registry the
 * network-lost fast-fail drains. Connections here are never
 * connected (openConnection() alone performs no I/O) — the
 * tests pin the bookkeeping: register / unregister / drain,
 * idempotence, and the HTTP/FTP sides staying independent.
 */
class InFlightConnectionsTest {

    private fun conn(path: String): HttpURLConnection =
        URL("http://example.com/$path").openConnection() as HttpURLConnection

    @Test
    fun registerAndUnregisterTrackLiveConnections() {
        val reg = InFlightConnections()
        val a = conn("a")
        val b = conn("b")
        reg.register(a)
        reg.register(b)
        assertEquals(2, reg.httpCount())
        reg.unregister(a)
        assertEquals(1, reg.httpCount())
        // The survivor is exactly the one not unregistered.
        assertEquals(listOf(b), reg.drainHttp())
    }

    @Test
    fun registeringTheSameConnectionTwiceCountsOnce() {
        val reg = InFlightConnections()
        val a = conn("a")
        reg.register(a)
        reg.register(a)
        assertEquals(1, reg.httpCount())
        assertEquals(listOf(a), reg.drainHttp())
    }

    @Test
    fun unregisteringAnUnknownConnectionIsANoOp() {
        // The fast-fail drain and a worker's own end-of-life can
        // race: whoever unregisters second must find nothing to
        // do, never an error.
        val reg = InFlightConnections()
        reg.unregister(conn("never-registered"))
        reg.unregisterFtp(FtpClient())
        assertEquals(0, reg.httpCount())
        assertEquals(0, reg.ftpCount())
    }

    @Test
    fun drainReturnsEverythingAndEmptiesTheRegistry() {
        val reg = InFlightConnections()
        val a = conn("a")
        val b = conn("b")
        reg.register(a)
        reg.register(b)
        val drained = reg.drainHttp()
        assertEquals(2, drained.size)
        assertTrue(drained.containsAll(listOf(a, b)))
        assertEquals(0, reg.httpCount())
        // A second drain (a duplicate fast-fail) cuts nothing.
        assertTrue(reg.drainHttp().isEmpty())
    }

    @Test
    fun httpAndFtpSidesAreIndependent() {
        val reg = InFlightConnections()
        val ftp = FtpClient()
        reg.register(conn("a"))
        reg.registerFtp(ftp)
        assertEquals(1, reg.httpCount())
        assertEquals(1, reg.ftpCount())
        // Draining one side leaves the other untouched.
        assertEquals(listOf(ftp), reg.drainFtp())
        assertEquals(0, reg.ftpCount())
        assertEquals(1, reg.httpCount())
        reg.unregisterFtp(ftp) // already drained: no-op
        assertEquals(1, reg.drainHttp().size)
    }
}

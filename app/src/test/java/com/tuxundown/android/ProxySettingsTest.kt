package com.tuxundown.android

import com.tuxundown.android.core.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/** v1.9: the download proxy's pure logic — validation, activation
 *  rules, the auth header and the failure wording. */
class ProxySettingsTest {

    @Test
    fun inactiveUnlessEnabledAndComplete() {
        assertNull(ProxySettings().javaProxy())
        assertNull(ProxySettings(enabled = true, host = "", port = 7890).javaProxy())
        assertNull(ProxySettings(enabled = true, host = "127.0.0.1", port = 0).javaProxy())
        assertNull(ProxySettings(enabled = false, host = "127.0.0.1", port = 7890).javaProxy())
        val on = ProxySettings(enabled = true, host = "127.0.0.1", port = 7890)
        assertTrue(on.active)
        assertEquals(Proxy.Type.HTTP, on.javaProxy()!!.type())
        val socks = on.copy(type = ProxySettings.TYPE_SOCKS)
        assertEquals(Proxy.Type.SOCKS, socks.javaProxy()!!.type())
        assertEquals("127.0.0.1:7890", on.label())
    }

    @Test
    fun authHeaderOnlyForHttpWithUsername() {
        val base = ProxySettings(
            enabled = true, host = "127.0.0.1", port = 7890,
            username = "user", password = "pass",
        )
        val header = base.proxyAuthHeader()
        assertNotNull(header)
        assertEquals(
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("user:pass".toByteArray(Charsets.UTF_8)),
            header,
        )
        assertNull(base.copy(type = ProxySettings.TYPE_SOCKS).proxyAuthHeader())
        assertNull(base.copy(username = "").proxyAuthHeader())
        assertNull(base.copy(enabled = false).proxyAuthHeader())
    }

    @Test
    fun validateSpeaksPlainly() {
        assertNull(ProxySettings.validate(false, "", ""))
        assertNull(ProxySettings.validate(true, "127.0.0.1", "7890"))
        assertNotNull(ProxySettings.validate(true, "", "7890"))
        assertNotNull(ProxySettings.validate(true, "127. 0.0.1", "7890"))
        assertNotNull(ProxySettings.validate(true, "127.0.0.1", "abc"))
        assertNotNull(ProxySettings.validate(true, "127.0.0.1", "0"))
        assertNotNull(ProxySettings.validate(true, "127.0.0.1", "65536"))
        assertNull(ProxySettings.validate(true, "127.0.0.1", "65535"))
    }

    @Test
    fun unreachableMessageNamesProxyAndNoDirectFallback() {
        val s = ProxySettings(
            enabled = true, type = ProxySettings.TYPE_SOCKS,
            host = "10.0.0.2", port = 1080,
        )
        val msg = ProxySettings.unreachableMessage(
            s, java.io.IOException("Connection refused")
        )
        assertTrue(msg.contains("SOCKS5"))
        assertTrue(msg.contains("10.0.0.2:1080"))
        assertTrue(msg.contains("没有改走直连"))
    }
}

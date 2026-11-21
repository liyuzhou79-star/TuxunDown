package com.tuxundown.android

import com.tuxundown.android.core.Errors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorsTest {

    @Test
    fun httpCodesCarryPlainMeaning() {
        assertTrue(Errors.httpMessage(403).contains("拒绝访问"))
        assertTrue(Errors.httpMessage(404).contains("不存在"))
        assertTrue(Errors.httpMessage(410).contains("不存在"))
        assertTrue(Errors.httpMessage(416).contains("点继续"))
        assertTrue(Errors.httpMessage(429).contains("限流"))
        assertTrue(Errors.httpMessage(500).contains("服务器那边出错"))
        assertTrue(Errors.httpMessage(503).contains("服务器那边出错"))
        assertTrue(Errors.httpMessage(400).contains("没听懂"))
    }

    @Test
    fun httpCodeNeverStandsAlone() {
        // Even an unlisted code gets a full sentence, not a bare number.
        val msg = Errors.httpMessage(418)
        assertTrue(msg.contains("418"))
        assertTrue(msg.length > 15)
    }

    @Test
    fun chineseMessagesPassThroughUntouched() {
        val own = IllegalStateException("服务器上的文件已更新，旧进度作废，已重新开始下载。")
        assertEquals(own.message, Errors.human(own))
    }

    @Test
    fun englishIoMessagesAreMappedByType() {
        val host = Errors.human(UnknownHostException("media.example.org"))
        assertTrue(host.contains("找不到这个服务器"))
        assertFalse(host.contains("UnknownHostException"))
        assertFalse(host.contains("media.example.org"))

        val refused = Errors.human(ConnectException("Connection refused"))
        assertTrue(refused.contains("连不上服务器"))
        assertFalse(refused.contains("refused"))

        val timeout = Errors.human(SocketTimeoutException("Read timed out"))
        assertTrue(timeout.contains("超时"))
        assertFalse(timeout.contains("timed out"))

        val ssl = Errors.human(SSLHandshakeException("PKIX path building failed"))
        assertTrue(ssl.contains("安全连接"))
        assertFalse(ssl.contains("PKIX"))
    }

    @Test
    fun genericExceptionDropsRawText() {
        val msg = Errors.human(IllegalStateException("weird internal state 0x3f"))
        assertFalse(msg.contains("weird"))
        assertFalse(msg.contains("IllegalStateException"))
        assertTrue(msg.contains("没预料到"))
    }

    @Test
    fun plainIoFallsBackToNetworkWording() {
        val msg = Errors.human(IOException("stream closed unexpectedly"))
        assertTrue(msg.contains("网络或读写出错"))
        assertFalse(msg.contains("stream closed"))
    }
}

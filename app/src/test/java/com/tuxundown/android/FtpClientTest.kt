package com.tuxundown.android

import com.tuxundown.android.core.FtpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7: FTP client pure helpers — PASV reply parsing and ftp://
 * URL parsing (login, port, percent-decoded path). The socket
 * transfer itself needs a real server and is device-verified.
 */
class FtpClientTest {

    @Test
    fun pasvParsesHostAndPort() {
        val r = FtpClient.parsePasv("227 Entering Passive Mode (192,168,1,10,19,136).")
        assertEquals("192.168.1.10" to (19 * 256 + 136), r)
    }

    @Test
    fun pasvParsesBareNumbers() {
        val r = FtpClient.parsePasv("227 (10,0,0,2,255,255)")
        assertEquals("10.0.0.2" to 65535, r)
    }

    @Test
    fun pasvRejectsGarbage() {
        assertNull(FtpClient.parsePasv("227 no numbers here"))
        assertNull(FtpClient.parsePasv("227 (1,2,3)"))
        assertNull(FtpClient.parsePasv("227 (a,b,c,d,e,f)"))
        assertNull(FtpClient.parsePasv("227 (1,2,3,4,0,0)")) // port 0
    }

    @Test
    fun urlAnonymousDefaults() {
        val u = FtpClient.parseUrl("ftp://ftp.example.com/pub/file.zip")!!
        assertEquals("ftp.example.com", u.host)
        assertEquals(21, u.port)
        assertEquals("anonymous", u.user)
        assertEquals("/pub/file.zip", u.path)
    }

    @Test
    fun urlUserinfoAndPort() {
        val u = FtpClient.parseUrl("ftp://bob:s3cret@ftp.example.com:2121/a.bin")!!
        assertEquals("bob", u.user)
        assertEquals("s3cret", u.password)
        assertEquals(2121, u.port)
    }

    @Test
    fun urlPercentDecoding() {
        val u = FtpClient.parseUrl(
            "ftp://user%40corp:p%40ss@ftp.example.com/dir/%E6%96%87%E4%BB%B6.zip"
        )!!
        assertEquals("user@corp", u.user)
        assertEquals("p@ss", u.password)
        assertEquals("/dir/文件.zip", u.path)
    }

    @Test
    fun urlRejectsNonFtpAndRoot() {
        assertNull(FtpClient.parseUrl("https://example.com/file.zip"))
        assertNull(FtpClient.parseUrl("ftp://ftp.example.com/"))
        assertNull(FtpClient.parseUrl("not a url"))
    }

    @Test
    fun isFtpUrlCheck() {
        assertTrue(FtpClient.isFtpUrl("ftp://x/y"))
        assertTrue(FtpClient.isFtpUrl("  FTP://x/y"))
        assertTrue(!FtpClient.isFtpUrl("https://x/y"))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.2: the ONE shared redactor behind every copy出口
 * (diagnostics, health check, crash page). The corpus below is
 * FAKE data shaped like real secrets — no real credential may
 * ever appear in this file.
 */
class RedactTest {

    @Test
    fun urlsFoldUserinfoQueryAndFragment() {
        val out = Redact.forCopy(
            "下载失败 https://fakeuser:fakepass@cdn.example.com:8443/a/b.zip?token=FAKE123#frag"
        )
        assertFalse(out.contains("fakeuser"))
        assertFalse(out.contains("fakepass"))
        assertFalse(out.contains("FAKE123"))
        assertFalse(out.contains("frag"))
        assertTrue(out.contains("https://cdn.example.com:8443/a/b.zip?（参数已隐藏）"))
        // An ftp link folds the same way.
        val ftp = Redact.forCopy("ftp://fakeuser:fakepass@files.example.com/pub/a.iso")
        assertEquals("ftp://files.example.com/pub/a.iso", ftp)
    }

    @Test
    fun keyValueSecretsLoseTheirValuesButKeepTheirNames() {
        val out = Redact.forCopy(
            "password=fakepass1 token: FAKETOKEN2 secret=fakesecret3 " +
                "api-key=FAKEKEY4 apikey: FAKEKEY5 Authorization=FAKEAUTH6"
        )
        for (secret in listOf(
            "fakepass1", "FAKETOKEN2", "fakesecret3",
            "FAKEKEY4", "FAKEKEY5", "FAKEAUTH6",
        )) {
            assertFalse("leaked: $secret", out.contains(secret))
        }
        assertTrue(out.contains("password"))
        assertTrue(out.contains("api-key"))
        assertTrue(out.contains("Authorization"))
    }

    @Test
    fun bearerAndBasicBlobsAreHidden() {
        val out = Redact.forCopy(
            "Authorization: Bearer FAKEBEARERTOKEN123 " +
                "备用 Basic ZmFrZXVzZXI6ZmFrZXBhc3M="
        )
        assertFalse(out.contains("FAKEBEARERTOKEN123"))
        assertFalse(out.contains("ZmFrZXVzZXI6ZmFrZXBhc3M="))
        // A standalone Bearer (no key name in front) is covered too.
        val bare = Redact.forCopy("请求头是 Bearer FAKESTANDALONE99 请查")
        assertFalse(bare.contains("FAKESTANDALONE99"))
    }

    @Test
    fun cookieLineValueIsHiddenToEndOfLine() {
        val out = Redact.forCopy(
            "Cookie: session=FAKESESSION; theme=fakedark; uid=42\n下一行正常"
        )
        assertFalse(out.contains("FAKESESSION"))
        assertFalse(out.contains("fakedark"))
        // The name stays (the shared secret-pair pass rewrites
        // the marker's colon, so pin the name, not the colon).
        assertTrue(out.contains("Cookie"))
        assertTrue(out.contains("已隐藏"))
        assertTrue(out.contains("下一行正常"))
    }

    @Test
    fun keystoreCiphertextShapeIsRemoved() {
        val out = Redact.forCopy("代理密码 v1:RkFLRUNJUEhFUlRFWFQxMjM0NTY= 已保存")
        assertFalse(out.contains("RkFLRUNJUEhFUlRFWFQxMjM0NTY="))
        assertTrue(out.contains("密文已隐藏"))
    }

    @Test
    fun plainTextPassesThroughUntouched() {
        val plain = "下载目录可用 12.3 GB，共 5 个任务，没有异常"
        assertEquals(plain, Redact.forCopy(plain))
        val line = "当前网络：WiFi · 排队任务在等充电"
        assertEquals(line, Redact.forCopy(line))
    }

    @Test
    fun urlCarriesCredentialsDetectsQueryAndUserinfo() {
        assertTrue(Redact.urlCarriesCredentials("https://a.b/f.zip?sig=abc"))
        assertTrue(Redact.urlCarriesCredentials("https://user:pass@a.b/f.zip"))
        assertTrue(Redact.urlCarriesCredentials("ftp://user@a.b/f.zip"))
        assertFalse(Redact.urlCarriesCredentials("https://a.b/f.zip"))
        assertFalse(Redact.urlCarriesCredentials("https://a.b/f.zip#frag"))
        assertFalse(Redact.urlCarriesCredentials(""))
    }
}

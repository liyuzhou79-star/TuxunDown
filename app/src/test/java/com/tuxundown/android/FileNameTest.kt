package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.7 compat: Content-Disposition filename decoding — RFC 5987
 * charsets (incl. GBK), raw (non-encoded) UTF-8 / GBK header bytes
 * that arrive as ISO-8859-1 mojibake, path-separator sanitising,
 * and '+' staying a plus in URL path names.
 */
class FileNameTest {

    @Test
    fun rfc5987Utf8() {
        val cd = "attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6.zip"
        assertEquals(
            "文件.zip",
            DownloadEngine.fileNameFrom(cd, "https://x.com/get"),
        )
    }

    @Test
    fun rfc5987Gbk() {
        // 文件 in GBK = CE C4 BC FE, percent-encoded under a GBK charset.
        val cd = "attachment; filename*=GBK''%CE%C4%BC%FE.zip"
        assertEquals(
            "文件.zip",
            DownloadEngine.fileNameFrom(cd, "https://x.com/get"),
        )
    }

    @Test
    fun plainQuotedName() {
        val cd = "attachment; filename=\"report 2026.pdf\""
        assertEquals(
            "report 2026.pdf",
            DownloadEngine.fileNameFrom(cd, "https://x.com/get"),
        )
    }

    @Test
    fun rawUtf8BytesInHeader() {
        // A server that writes raw UTF-8 bytes for 文件.zip into the
        // header: HttpURLConnection decodes them as ISO-8859-1.
        val mojibake = String(
            "文件.zip".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1
        )
        val cd = "attachment; filename=\"$mojibake\""
        assertEquals(
            "文件.zip",
            DownloadEngine.fileNameFrom(cd, "https://x.com/get"),
        )
    }

    @Test
    fun rawGbkBytesInHeader() {
        val mojibake = String(
            "文件.zip".toByteArray(java.nio.charset.Charset.forName("GBK")),
            Charsets.ISO_8859_1,
        )
        val cd = "attachment; filename=\"$mojibake\""
        assertEquals(
            "文件.zip",
            DownloadEngine.fileNameFrom(cd, "https://x.com/get"),
        )
    }

    @Test
    fun pathSeparatorsAreStripped() {
        val cd = "attachment; filename=\"../../evil.apk\""
        assertEquals(
            "evil.apk",
            DownloadEngine.fileNameFrom(cd, "https://x.com/get"),
        )
    }

    @Test
    fun plusInUrlPathStaysPlus() {
        assertEquals(
            "a+b.zip",
            DownloadEngine.fileNameFrom(null, "https://x.com/files/a+b.zip"),
        )
    }

    @Test
    fun percentEncodedUrlNameStillDecodes() {
        assertEquals(
            "a b.zip",
            DownloadEngine.fileNameFrom(null, "https://x.com/files/a%20b.zip"),
        )
    }
}

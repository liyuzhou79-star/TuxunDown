package com.tuxundown.android

import com.tuxundown.android.sniff.SniffParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffParserTest {

    private val html = """
        <html><body>
          <a href="/files/app-release.apk">点击下载</a>
          <a href="docs/manual.pdf">说明书</a>
          <img src="img/cover.png">
          <a href="https://example.com/about">关于我们</a>
          <video src="/media/intro.mp4"></video>
        </body></html>
    """.trimIndent()

    @Test
    fun extractsDownloadableResources() {
        val res = SniffParser.extract(html, "https://example.com/page/index.html")
        val urls = res.map { it.url }.toSet()
        assertTrue("https://example.com/files/app-release.apk" in urls)
        assertTrue("https://example.com/page/docs/manual.pdf" in urls)
        assertTrue("https://example.com/page/img/cover.png" in urls)
        assertTrue("https://example.com/media/intro.mp4" in urls)
        assertEquals(4, res.size)
    }

    @Test
    fun marksDownloadEntry() {
        val res = SniffParser.extract(html, "https://example.com/")
        val apk = res.first { it.url.endsWith(".apk") }
        assertTrue(apk.looksLikeDownloadEntry)
        assertEquals("apk", apk.kind)
    }

    @Test
    fun kindOfIgnoresQuery() {
        assertEquals("archive", SniffParser.kindOf("https://x.com/a.zip?token=1"))
        assertEquals(null, SniffParser.kindOf("https://x.com/page.html"))
    }
}

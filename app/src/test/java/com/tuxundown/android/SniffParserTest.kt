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

    @Test
    fun kataNetworksStylePageListsBinGz() {
        // Mirrors katagotraining.org/networks/kata1/: a big table whose
        // rows carry a filename anchor plus a [Download] anchor per file.
        val page = """
            <table>
              <tr><td><a href="https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b28c512nbt-s13255194368-d5935380940.bin.gz">kata1-b28c512nbt-s13255194368-d5935380940</a></td>
              <td><a href="https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b28c512nbt-s13255194368-d5935380940.bin.gz">[Download]</a></td></tr>
              <tr><td><a href="https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz">kata1-b18c384nbt</a></td>
              <td><a href="https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz">[Download]</a></td></tr>
            </table>
        """.trimIndent()
        val res = SniffParser.extract(page, "https://katagotraining.org/networks/kata1/")
        assertEquals(2, res.size)
        assertTrue(res.all { it.url.endsWith(".bin.gz") && it.kind == "archive" })
        assertTrue(res.all { it.looksLikeDownloadEntry })
        assertEquals(
            "kata1-b28c512nbt-s13255194368-d5935380940.bin.gz",
            res.first { it.url.contains("b28") }.name,
        )
    }

    @Test
    fun metaRefreshTargetIsFound() {
        val page = """<meta http-equiv="refresh" content="0; url=/real/download.html">"""
        assertEquals(
            "https://example.com/real/download.html",
            SniffParser.metaRefreshTarget(page, "https://example.com/jump.html"),
        )
    }

    @Test
    fun jsEmbeddedAndDataSrcLinksAreFound() {
        val page = """
            <script>var downUrl = "https://cdn.example.com/pkg/tool_v2.zip";</script>
            <img data-src="/pics/shot.jpg" src="/pics/placeholder.png">
            <source src="/media/clip.webm" type="video/webm">
        """.trimIndent()
        val res = SniffParser.extract(page, "https://example.com/post/1.html")
        val urls = res.map { it.url }.toSet()
        assertTrue("https://cdn.example.com/pkg/tool_v2.zip" in urls)
        assertTrue("https://example.com/pics/shot.jpg" in urls)
        assertTrue("https://example.com/media/clip.webm" in urls)
    }

    @Test
    fun entriesSortBeforeImages() {
        val page = """
            <img src="/a.png">
            <a href="/files/x.apk">下载APP</a>
            <img src="/b.jpg">
        """.trimIndent()
        val res = SniffParser.extract(page, "https://example.com/")
        assertTrue(res.first().url.endsWith(".apk"))
        assertEquals("image", res.last().kind)
    }
}

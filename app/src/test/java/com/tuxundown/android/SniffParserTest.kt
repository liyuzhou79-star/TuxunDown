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
    fun metaVideoTagsAreExtracted() {
        // v2.3: pages that publish their video ONLY as social
        // metadata (no anchor / <video> tag anywhere).
        val page = """
            <html><head>
              <meta property="og:video" content="https://cdn.example.com/v/12345.mp4">
              <meta name="twitter:player:stream" content="https://cdn.example.com/v/12345.mp4">
              <meta property="og:image" content="https://cdn.example.com/v/poster.jpg">
            </head><body><p>watch this</p></body></html>
        """.trimIndent()
        val res = SniffParser.extract(page, "https://example.com/watch/1")
        val video = res.firstOrNull { it.url.endsWith(".mp4") }
        assertTrue(video != null)
        assertEquals("video", video!!.kind)
        assertTrue(video.looksLikeDownloadEntry)
        // Both meta tags name the same file: still one entry.
        assertEquals(1, res.count { it.url.endsWith(".mp4") })
        // The og:image poster is NOT promoted to a download entry
        // (it may still list as a plain image via the raw-URL pass,
        // like any other URL in the page).
        val poster = res.firstOrNull { it.url.endsWith(".jpg") }
        if (poster != null) {
            assertEquals("image", poster.kind)
            assertTrue(!poster.looksLikeDownloadEntry)
        }
    }

    @Test
    fun metaSecureUrlAndRelativeResolve() {
        val page = """
            <html><head>
              <meta property="og:video:secure_url" content="/media/clip.webm">
            </head><body></body></html>
        """.trimIndent()
        val res = SniffParser.extract(page, "https://example.com/watch/2")
        assertEquals(1, res.size)
        assertEquals("https://example.com/media/clip.webm", res[0].url)
        assertEquals("video", res[0].kind)
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
    fun bareScriptUrlWithDownloadInPathIsNotAnEntry() {
        // Audit A13: a raw JS URL that merely contains "download"
        // (analytics/tracker style) must not be listed at all, and
        // must never become the auto-followed single entry. A raw
        // URL that really points at a file still counts.
        val page = """
            <script>var t = "https://stats.example.com/download/tracker.js";</script>
            <script>var u = "https://cdn.example.com/download/app.zip";</script>
        """.trimIndent()
        val res = SniffParser.extract(page, "https://example.com/")
        val urls = res.map { it.url }.toSet()
        assertTrue("https://stats.example.com/download/tracker.js" !in urls)
        assertTrue("https://cdn.example.com/download/app.zip" in urls)
        assertTrue(res.first { it.url.endsWith(".zip") }.looksLikeDownloadEntry)
    }

    @Test
    fun githubReleaseAssetsAreFoundIncludingExtensionless() {
        // v1.6 pattern: GitHub release asset links live under
        // /releases/download/<tag>/… — most carry an extension, but
        // extensionless binaries must still be listed as entries.
        val page = """
            <a href="/liyuzhou79-star/TuxunDown/releases/download/v1.2.0/TuxunDown-v1.2.0.apk">TuxunDown-v1.2.0.apk</a>
            <a href="/liyuzhou79-star/TuxunDown/releases/download/v1.2.0/tool-linux">tool-linux</a>
            <a href="/liyuzhou79-star/TuxunDown/releases">All releases</a>
            <a href="/liyuzhou79-star/TuxunDown">Repo home</a>
        """.trimIndent()
        val res = SniffParser.extract(
            page, "https://github.com/liyuzhou79-star/TuxunDown/releases/tag/v1.2.0"
        )
        val urls = res.map { it.url }.toSet()
        assertTrue(
            "https://github.com/liyuzhou79-star/TuxunDown/releases/download/v1.2.0/TuxunDown-v1.2.0.apk" in urls
        )
        val bare = res.firstOrNull {
            it.url == "https://github.com/liyuzhou79-star/TuxunDown/releases/download/v1.2.0/tool-linux"
        }
        assertTrue(bare != null && bare.looksLikeDownloadEntry)
        // The releases index and the repo home are NOT downloads.
        assertTrue(res.none { it.url.endsWith("/releases") || it.url.endsWith("/TuxunDown") })
    }

    @Test
    fun sourceForgeFileLinksAreFound() {
        // v1.6 pattern: SourceForge file links sit under
        // /projects/<p>/files/… and the fetch hop ends in /download.
        val page = """
            <a href="https://sourceforge.net/projects/sevenzip/files/7-zip/24.09/7z2409-x64.exe/download">7z2409-x64.exe</a>
            <a href="https://sourceforge.net/projects/sevenzip/files/7-zip/24.09/7z2409-linux-x64.tar.xz/download">tar.xz</a>
            <a href="https://sourceforge.net/projects/sevenzip/">Project home</a>
        """.trimIndent()
        val res = SniffParser.extract(page, "https://sourceforge.net/projects/sevenzip/files/")
        assertEquals(2, res.size)
        assertTrue(res.all { it.looksLikeDownloadEntry })
        assertTrue(res.any { it.kind == "other" && it.name.contains("7z2409-x64") })
    }

    @Test
    fun archiveOrgFileLinksAreFound() {
        // v1.6 pattern: archive.org item files live under
        // /download/<id>/…; the details page itself must not match.
        val page = """
            <a href="https://archive.org/download/ms-dos_frogger/Frogger%20(1983)(Sierra%20On-Line).zip">Frogger.zip</a>
            <a href="https://archive.org/download/ms-dos_frogger/frogger_screenshot.gif">screenshot</a>
            <a href="https://archive.org/details/ms-dos_frogger">Details</a>
        """.trimIndent()
        val res = SniffParser.extract(page, "https://archive.org/details/ms-dos_frogger")
        val urls = res.map { it.url }.toSet()
        assertTrue(urls.any { it.contains("/download/ms-dos_frogger/") && it.endsWith(".zip") })
        assertTrue(res.none { it.url.contains("/details/") })
        assertTrue(
            res.first { it.url.contains("/download/") && it.url.endsWith(".zip") }
                .looksLikeDownloadEntry
        )
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

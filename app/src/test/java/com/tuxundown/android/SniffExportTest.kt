package com.tuxundown.android

import com.tuxundown.android.core.FtpClient
import com.tuxundown.android.core.LinkExtract
import com.tuxundown.android.core.ThunderLink
import com.tuxundown.android.sniff.SniffExport
import com.tuxundown.android.sniff.SniffedResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2: the sniff-session export must round-trip through BOTH
 * import doors — the .txt importer's line rule (a link is a
 * line starting with a scheme; name/header lines are skipped)
 * and the batch-paste extractor (LinkExtract over the whole
 * text). If either stops finding exactly the exported links,
 * the export is a dead letter.
 */
class SniffExportTest {

    private val resources = listOf(
        SniffedResource("https://a.b/movie.mp4", "movie.mp4", "video"),
        SniffedResource("https://a.b/song.mp3", "song.mp3", "audio"),
        SniffedResource("ftp://a.b/pack.zip", "pack.zip", "archive"),
        SniffedResource("https://cdn.x.y/f.bin", "", "other"),
    )

    /** The .txt importer's own line rule, mirrored from the
     *  import launcher: a line counts as a link when it starts
     *  with http, is an ftp link, or is a thunder envelope. */
    private fun lineLinks(text: String): List<String> =
        text.lines().map { it.trim() }.filter {
            it.startsWith("http") || FtpClient.isFtpUrl(it) ||
                ThunderLink.isThunderLink(it)
        }

    @Test
    fun exportCarriesHeaderNamesAndLinks() {
        val text = SniffExport.build(resources)
        assertTrue(text.startsWith("TuxunDown 链接清单（共 4 个）\n"))
        // Name line directly above its link line.
        assertTrue(text.contains("movie.mp4\nhttps://a.b/movie.mp4\n"))
        // A blank name produces no name line, just the link.
        assertTrue(text.contains("\nhttps://cdn.x.y/f.bin\n"))
    }

    @Test
    fun txtImportLineRuleRecoversExactlyTheLinks() {
        val text = SniffExport.build(resources)
        assertEquals(resources.map { it.url }, lineLinks(text))
    }

    @Test
    fun batchPasteExtractorRecoversEveryLink() {
        // LinkExtract (the paste door's extractor) speaks
        // http(s) AND ftp — the whole list comes back, in
        // order, names and header lines ignored.
        val text = SniffExport.build(resources)
        assertEquals(resources.map { it.url }, LinkExtract.extract(text))
    }

    @Test
    fun credentialHintAppearsOnlyForSignedOrUserinfoLinks() {
        // v7.2 (audit round 57): the same one-line reminder as
        // the queue share list; the plain fixture list above
        // carries no query/userinfo, so it stays hint-free and
        // the round-trip tests above are unaffected.
        val plain = SniffExport.build(resources)
        assertFalse(plain.contains("凭据"))
        val signed = SniffExport.build(
            listOf(SniffedResource("https://a.b/f.zip?sig=fake1", "f.zip", "archive"))
        )
        assertTrue(signed.contains("凭据"))
        // The hint line is not a link line: the .txt rule and
        // the paste extractor still recover exactly the link.
        assertEquals(
            listOf("https://a.b/f.zip?sig=fake1"), lineLinks(signed),
        )
    }

    @Test
    fun emptySessionExportsAValidEmptyList() {
        val text = SniffExport.build(emptyList())
        assertTrue(text.startsWith("TuxunDown 链接清单（共 0 个）\n"))
        assertTrue(lineLinks(text).isEmpty())
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.LinkExtract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1: links out of arbitrary shared / pasted text. */
class LinkExtractTest {

    @Test
    fun bareUrlReturnsItself() {
        assertEquals(
            listOf("https://a.com/f.zip"),
            LinkExtract.extract("https://a.com/f.zip"),
        )
    }

    @Test
    fun linksInsideProseAreFoundInOrder() {
        val text = "新版发布了，快去 https://a.com/app.apk 下载，" +
            "备用地址 ftp://mirror.b.com/app.apk 也可以，官网 https://a.com/ 有说明。"
        assertEquals(
            listOf(
                "https://a.com/app.apk",
                "ftp://mirror.b.com/app.apk",
                "https://a.com/",
            ),
            LinkExtract.extract(text),
        )
    }

    @Test
    fun cjkPunctuationIsNotPartOfTheLink() {
        assertEquals(
            listOf("https://a.com/f.zip"),
            LinkExtract.extract("地址：https://a.com/f.zip，复制到浏览器打开。"),
        )
        assertEquals(
            listOf("https://a.com/x.apk"),
            LinkExtract.extract("（https://a.com/x.apk）长按复制"),
        )
    }

    @Test
    fun trailingSentencePunctuationIsTrimmed() {
        assertEquals(
            listOf("https://a.com/f.zip"),
            LinkExtract.extract("Get it at https://a.com/f.zip."),
        )
        assertEquals(
            listOf("https://a.com/f.zip"),
            LinkExtract.extract("wow, https://a.com/f.zip!"),
        )
    }

    @Test
    fun duplicatesCollapseAndOrderIsKept() {
        val text = "https://a.com/1.zip https://a.com/2.zip https://a.com/1.zip"
        assertEquals(
            listOf("https://a.com/1.zip", "https://a.com/2.zip"),
            LinkExtract.extract(text),
        )
    }

    @Test
    fun noLinksMeansEmpty() {
        assertTrue(LinkExtract.extract("这里没有任何链接").isEmpty())
        assertTrue(LinkExtract.extract("").isEmpty())
        assertTrue(LinkExtract.extract("http://").isEmpty())
    }

    @Test
    fun queryStringsSurvive() {
        assertEquals(
            listOf("https://a.com/dl?id=42&token=xyz"),
            LinkExtract.extract("链接 https://a.com/dl?id=42&token=xyz 好了"),
        )
    }

    @Test
    fun extractionIsCapped() {
        val text = (1..150).joinToString(" ") { "https://a.com/f$it.zip" }
        assertEquals(LinkExtract.MAX_LINKS, LinkExtract.extract(text).size)
    }
}

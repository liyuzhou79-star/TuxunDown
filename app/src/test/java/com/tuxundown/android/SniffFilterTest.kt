package com.tuxundown.android

import com.tuxundown.android.sniff.SniffFilter
import com.tuxundown.android.sniff.SniffedResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9: sniff noise folding + duplicate hints. */
class SniffFilterTest {

    private fun r(url: String, kind: String = "other", size: Long = -1) =
        SniffedResource(url = url, name = url.substringAfterLast('/'), kind = kind, size = size)

    @Test
    fun trackersAndTinyImagesAreNoise() {
        assertTrue(SniffFilter.isNoise(r("https://www.google-analytics.com/g/collect")))
        assertTrue(SniffFilter.isNoise(r("https://hm.baidu.com/hm.js?x=1")))
        assertTrue(SniffFilter.isNoise(r("https://a.b/logo.png", kind = "image", size = 4_000)))
        // A real download is never noise, and an UNSIZED image
        // proves nothing yet — it stays visible.
        assertFalse(SniffFilter.isNoise(r("https://a.b/app.apk", kind = "apk", size = 5_000_000)))
        assertFalse(SniffFilter.isNoise(r("https://a.b/photo.jpg", kind = "image", size = -1)))
        assertFalse(SniffFilter.isNoise(r("https://a.b/wallpaper.jpg", kind = "image", size = 900_000)))
    }

    @Test
    fun duplicatesNeedSameNameAndSameMeasuredSize() {
        val list = listOf(
            r("https://a.b/file.zip", kind = "archive", size = 1234),
            r("https://cdn.b/file.zip", kind = "archive", size = 1234),
            r("https://a.b/other.zip", kind = "archive", size = 1234), // same size, other name
            r("https://a.b/file.zip?x=1", kind = "archive", size = -1), // unsized: unproven
        )
        val dups = SniffFilter.duplicateCounts(list)
        assertEquals(2, dups["https://a.b/file.zip"])
        assertEquals(2, dups["https://cdn.b/file.zip"])
        assertEquals(null, dups["https://a.b/other.zip"])
        assertEquals(null, dups["https://a.b/file.zip?x=1"])
    }
}

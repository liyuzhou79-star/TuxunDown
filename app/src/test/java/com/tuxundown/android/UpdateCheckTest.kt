package com.tuxundown.android

import com.tuxundown.android.core.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.0.0: update-check version math + release JSON parsing. */
class UpdateCheckTest {

    @Test
    fun compareVersions_ordersNumericallyNotLexically() {
        // "1.10.0" > "1.9.0" — a plain string compare gets this wrong.
        assertTrue(UpdateCheck.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateCheck.compareVersions("1.9.0", "1.10.0") < 0)
        assertTrue(UpdateCheck.compareVersions("2.0.0", "1.99.9") > 0)
        assertEquals(0, UpdateCheck.compareVersions("2.0.0", "2.0.0"))
    }

    @Test
    fun compareVersions_toleratesPrefixesAndMissingParts() {
        assertEquals(0, UpdateCheck.compareVersions("v2.0.0", "2.0.0"))
        assertEquals(0, UpdateCheck.compareVersions("2.0", "2.0.0"))
        assertTrue(UpdateCheck.compareVersions("2.0.1", "2.0") > 0)
        assertTrue(UpdateCheck.isNewer("v1.2.1", "1.2.0"))
        assertFalse(UpdateCheck.isNewer("1.2.0", "1.2.0"))
        // An installed acceptance build ahead of the formal page is
        // NOT an update offer.
        assertFalse(UpdateCheck.isNewer("1.2.0", "2.0.0"))
    }

    @Test
    fun parseLatest_picksTheApkAsset() {
        val json = """
            {
              "tag_name": "v2.1.0",
              "body": "## 新功能\n- 更快了",
              "assets": [
                {"name": "notes.txt",
                 "browser_download_url": "https://example.com/notes.txt"},
                {"name": "TuxunDown-v2.1.0.apk",
                 "browser_download_url": "https://example.com/TuxunDown-v2.1.0.apk"}
              ]
            }
        """.trimIndent()
        val latest = UpdateCheck.parseLatest(json)
        assertNotNull(latest)
        assertEquals("2.1.0", latest!!.version)
        assertEquals("TuxunDown-v2.1.0.apk", latest.apkName)
        assertEquals("https://example.com/TuxunDown-v2.1.0.apk", latest.apkUrl)
        assertTrue(latest.notes.contains("更快了"))
    }

    @Test
    fun parseLatest_rejectsShapesItCannotOffer() {
        // No APK asset -> nothing installable -> null.
        assertNull(
            UpdateCheck.parseLatest(
                """{"tag_name": "v9.9.9", "assets": []}"""
            )
        )
        // Not JSON at all.
        assertNull(UpdateCheck.parseLatest("this is not json"))
        // Missing tag.
        assertNull(
            UpdateCheck.parseLatest(
                """{"assets": [{"name": "a.apk",
                    "browser_download_url": "https://x/a.apk"}]}"""
            )
        )
    }
}

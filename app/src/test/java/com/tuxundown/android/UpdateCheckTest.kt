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
    fun compareVersions_3xChainOrdersCorrectly() {
        // v3.6 audit round 22 (release readiness): the whole
        // published/acceptance chain 1.2.0 -> 3.7.0 must order
        // strictly ascending under the same comparator the About
        // page uses, so a future formal release is offered to
        // every older install — and to none of the newer ones.
        val chain = listOf(
            "1.2.0", "1.9.0", "2.0.0", "2.9.0", "3.0.0", "3.5.0", "3.6.0", "3.7.0",
        )
        for (i in 0 until chain.size - 1) {
            assertTrue(
                "${chain[i]} < ${chain[i + 1]}",
                UpdateCheck.compareVersions(chain[i], chain[i + 1]) < 0,
            )
            assertTrue(UpdateCheck.isNewer(chain[i + 1], chain[i]))
            assertFalse(UpdateCheck.isNewer(chain[i], chain[i + 1]))
        }
        // Two-digit minors keep ordering numerically past 3.9.
        assertTrue(UpdateCheck.compareVersions("3.10.0", "3.9.0") > 0)
        // An installed 3.7.0 acceptance build is ahead of the
        // only formal release page (v1.2.0): not an update offer.
        assertFalse(UpdateCheck.isNewer("1.2.0", "3.7.0"))
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

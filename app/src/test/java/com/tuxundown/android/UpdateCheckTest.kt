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
        // published/acceptance chain 1.2.0 -> 3.9.0 must order
        // strictly ascending under the same comparator the About
        // page uses, so a future formal release is offered to
        // every older install — and to none of the newer ones.
        val chain = listOf(
            "1.2.0", "1.9.0", "2.0.0", "2.9.0", "3.0.0", "3.5.0", "3.6.0", "3.7.0",
            "3.8.0", "3.9.0",
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
        // An installed 3.9.0 acceptance build is ahead of the
        // only formal release page (v1.2.0): not an update offer.
        assertFalse(UpdateCheck.isNewer("1.2.0", "3.9.0"))
    }

    @Test
    fun compareVersions_6xBoundaryCasesPinThe6_0Upgrade() {
        // v6.0 audit round 45 (release readiness): the digit-
        // boundary cases the 6.0 upgrade actually crosses. The
        // comparator is numeric per part, so a two-digit minor
        // or major must never fall back to lexical order.
        // 5.9 -> 6.0: the upgrade every 5.9 install will be offered.
        assertTrue(UpdateCheck.isNewer("6.0.0", "5.9.0"))
        assertFalse(UpdateCheck.isNewer("5.9.0", "6.0.0"))
        assertTrue(UpdateCheck.compareVersions("5.9.0", "6.0.0") < 0)
        // 6.0 -> 6.10 and 6.9 -> 6.10: two-digit minor wins.
        assertTrue(UpdateCheck.compareVersions("6.10.0", "6.0.0") > 0)
        assertTrue(UpdateCheck.compareVersions("6.10.0", "6.9.0") > 0)
        assertTrue(UpdateCheck.compareVersions("6.9.0", "6.10.0") < 0)
        // 6.0 -> 6.0.1: a fourth part counts, missing counts as 0.
        assertTrue(UpdateCheck.compareVersions("6.0.1", "6.0.0") > 0)
        assertTrue(UpdateCheck.compareVersions("6.0.0", "6.0.1") < 0)
        assertEquals(0, UpdateCheck.compareVersions("6.0", "6.0.0"))
        // 10.0: a two-digit major beats every 6.x / 9.x.
        assertTrue(UpdateCheck.compareVersions("10.0.0", "6.10.0") > 0)
        assertTrue(UpdateCheck.compareVersions("10.0.0", "9.9.9") > 0)
        assertTrue(UpdateCheck.isNewer("10.0.0", "6.0.0"))
        // An installed 6.0.0 is ahead of the last 5.x formal page.
        assertFalse(UpdateCheck.isNewer("5.9.0", "6.0.0"))
    }

    @Test
    fun compareVersions_fullChainThrough6_0StaysAscending() {
        // v6.0 audit round 45: the whole published chain (the
        // ChangelogTest list, 1.2.0 -> 5.9.0) plus the 6.x tail
        // must order strictly ascending under the same comparator
        // the About page uses, so 6.0.0 is offered to every older
        // install and to none of the newer ones.
        val chain = listOf(
            "1.2.0", "1.3.0", "1.4.0", "1.5.0", "1.6.0", "1.7.0", "1.8.0",
            "1.9.0", "2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.4.0", "2.5.0",
            "2.6.0", "2.7.0", "2.8.0", "2.9.0", "3.0.0", "3.1.0", "3.2.0",
            "3.3.0", "3.4.0", "3.5.0", "3.6.0", "3.7.0", "3.8.0", "3.9.0",
            "4.0.0", "4.1.0", "4.2.0", "4.3.0", "4.4.0", "4.5.0", "4.6.0",
            "4.7.0", "4.8.0", "4.9.0", "5.0.0", "5.1.0", "5.2.0", "5.3.0",
            "5.4.0", "5.5.0", "5.6.0", "5.7.0", "5.8.0", "5.9.0",
            "6.0.0", "6.0.1", "6.9.0", "6.10.0", "10.0.0",
        )
        for (i in 0 until chain.size - 1) {
            assertTrue(
                "${chain[i]} < ${chain[i + 1]}",
                UpdateCheck.compareVersions(chain[i], chain[i + 1]) < 0,
            )
            assertTrue(UpdateCheck.isNewer(chain[i + 1], chain[i]))
            assertFalse(UpdateCheck.isNewer(chain[i], chain[i + 1]))
        }
        assertEquals(0, UpdateCheck.compareVersions("6.0.0", "6.0.0"))
    }

    @Test
    fun compareVersions_7xBoundaryCasesPinThe7_0Upgrade() {
        // v7.0 audit round 55 (release readiness): the digit-
        // boundary cases the 7.0 upgrade actually crosses.
        // 6.9 -> 7.0: the upgrade every 6.9 install will be offered.
        assertTrue(UpdateCheck.compareVersions("6.9.0", "7.0.0") < 0)
        assertTrue(UpdateCheck.compareVersions("7.0.0", "6.9.0") > 0)
        assertTrue(UpdateCheck.isNewer("7.0.0", "6.9.0"))
        assertFalse(UpdateCheck.isNewer("6.9.0", "7.0.0"))
        // 7.0 -> 7.10 and 7.9 -> 7.10: two-digit minor wins.
        assertTrue(UpdateCheck.compareVersions("7.0.0", "7.10.0") < 0)
        assertTrue(UpdateCheck.compareVersions("7.9.0", "7.10.0") < 0)
        assertTrue(UpdateCheck.isNewer("7.10.0", "7.0.0"))
        assertTrue(UpdateCheck.isNewer("7.10.0", "7.9.0"))
        assertFalse(UpdateCheck.isNewer("7.0.0", "7.10.0"))
        assertFalse(UpdateCheck.isNewer("7.9.0", "7.10.0"))
        // 9.x -> 10.0: two-digit major wins, both directions.
        assertTrue(UpdateCheck.compareVersions("9.9.9", "10.0.0") < 0)
        assertTrue(UpdateCheck.compareVersions("10.0.0", "9.9.9") > 0)
        assertTrue(UpdateCheck.isNewer("10.0.0", "9.9.9"))
        assertFalse(UpdateCheck.isNewer("9.9.9", "10.0.0"))
        assertEquals(0, UpdateCheck.compareVersions("7.0", "7.0.0"))
        assertEquals(0, UpdateCheck.compareVersions("7.0.0", "7.0.0"))
    }

    @Test
    fun compareVersions_fullChainThrough6_9PlusFutureStaysAscending() {
        // v7.0 audit round 55: the whole published chain
        // 1.2.0 -> 6.9.0 (the ChangelogTest list) plus the
        // future tail 7.0.0 / 7.10.0 / 9.9.9 / 10.0.0 must
        // order strictly ascending, isNewer both directions
        // per adjacent pair.
        val chain = listOf(
            "1.2.0", "1.3.0", "1.4.0", "1.5.0", "1.6.0", "1.7.0", "1.8.0",
            "1.9.0", "2.0.0", "2.1.0", "2.2.0", "2.3.0", "2.4.0", "2.5.0",
            "2.6.0", "2.7.0", "2.8.0", "2.9.0", "3.0.0", "3.1.0", "3.2.0",
            "3.3.0", "3.4.0", "3.5.0", "3.6.0", "3.7.0", "3.8.0", "3.9.0",
            "4.0.0", "4.1.0", "4.2.0", "4.3.0", "4.4.0", "4.5.0", "4.6.0",
            "4.7.0", "4.8.0", "4.9.0", "5.0.0", "5.1.0", "5.2.0", "5.3.0",
            "5.4.0", "5.5.0", "5.6.0", "5.7.0", "5.8.0", "5.9.0",
            "6.0.0", "6.1.0", "6.2.0", "6.3.0", "6.4.0", "6.5.0", "6.6.0",
            "6.7.0", "6.8.0", "6.9.0",
            "7.0.0", "7.10.0", "9.9.9", "10.0.0",
        )
        for (i in 0 until chain.size - 1) {
            assertTrue(
                "${chain[i]} < ${chain[i + 1]}",
                UpdateCheck.compareVersions(chain[i], chain[i + 1]) < 0,
            )
            assertTrue(UpdateCheck.isNewer(chain[i + 1], chain[i]))
            assertFalse(UpdateCheck.isNewer(chain[i], chain[i + 1]))
        }
    }

    @Test
    fun compareVersions_8xBoundaryCasesPinThe8_0Upgrade() {
        // v8.0 audit round 65 (release readiness): the exact
        // cross-boundary pair this release crosses. The 7.x
        // tests above pin 6.9 -> 7.0 and 9.9.9 -> 10.0.0 but
        // never named 7.9 -> 8.0, and never swept the reverse
        // direction for an installed 8.0.0.
        // 7.9.0 -> 8.0.0: the upgrade every 7.9.0 install
        // must be offered.
        assertTrue(UpdateCheck.compareVersions("7.9.0", "8.0.0") < 0)
        assertTrue(UpdateCheck.compareVersions("8.0.0", "7.9.0") > 0)
        assertTrue(UpdateCheck.isNewer("8.0.0", "7.9.0"))
        assertTrue(UpdateCheck.isNewer("v8.0.0", "7.9.0"))
        assertFalse(UpdateCheck.isNewer("7.9.0", "8.0.0"))
        // An installed 8.0.0 must NEVER be offered any
        // <= 7.9.0 release as an "update" (no reverse offer),
        // and 8.0.0 itself is not newer than 8.0.0.
        val notNewerFor80 = listOf(
            "1.2.0", "5.9.0", "6.9.0", "7.0.0", "7.7.0",
            "7.8.0", "7.9.0", "8.0.0",
        )
        for (old in notNewerFor80) {
            assertFalse(
                "$old must not be offered to an 8.0.0 install",
                UpdateCheck.isNewer(old, "8.0.0"),
            )
            assertTrue(UpdateCheck.isNewer("8.0.0", old) || old == "8.0.0")
        }
        assertEquals(0, UpdateCheck.compareVersions("8.0", "8.0.0"))
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

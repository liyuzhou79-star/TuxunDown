package com.tuxundown.android

import com.tuxundown.android.core.Changelog
import com.tuxundown.android.core.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.6: the in-app changelog must cover the whole 1.2 -> 3.9
 * chain exactly once, newest first, one real summary per
 * version — a missing or duplicated entry is a release-notes
 * bug the user can see, so it is pinned here.
 */
class ChangelogTest {

    @Test
    fun coversTheWholeChainNewestFirst() {
        val expected = listOf(
            "6.6.0", "6.5.0", "6.4.0", "6.3.0", "6.2.0", "6.1.0", "6.0.0", "5.9.0", "5.8.0", "5.7.0", "5.6.0", "5.5.0", "5.4.0", "5.3.0", "5.2.0", "5.1.0", "5.0.0", "4.9.0", "4.8.0", "4.7.0", "4.6.0", "4.5.0", "4.4.0", "4.3.0", "4.2.0", "4.1.0", "4.0.0", "3.9.0", "3.8.0", "3.7.0", "3.6.0", "3.5.0", "3.4.0", "3.3.0", "3.2.0", "3.1.0", "3.0.0",
            "2.9.0", "2.8.0", "2.7.0", "2.6.0", "2.5.0", "2.4.0", "2.3.0",
            "2.2.0", "2.1.0", "2.0.0",
            "1.9.0", "1.8.0", "1.7.0", "1.6.0", "1.5.0", "1.4.0", "1.3.0",
            "1.2.0",
        )
        assertEquals(expected, Changelog.entries.map { it.version })
    }

    @Test
    fun strictlyDescendingAndEverySummaryReal() {
        val es = Changelog.entries
        for (i in 0 until es.size - 1) {
            assertTrue(
                "${es[i].version} must be newer than ${es[i + 1].version}",
                UpdateCheck.compareVersions(es[i].version, es[i + 1].version) > 0,
            )
        }
        for (e in es) {
            assertTrue("blank summary for ${e.version}", e.summary.isNotBlank())
            // One glanceable line (or two short clauses), not a
            // pasted release-notes paragraph.
            assertTrue(
                "summary too long for ${e.version}: ${e.summary.length}",
                e.summary.length <= 130,
            )
        }
    }
}

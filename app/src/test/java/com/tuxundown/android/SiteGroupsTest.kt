package com.tuxundown.android

import com.tuxundown.android.core.SiteGroups
import com.tuxundown.android.core.TaskRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteGroupsTest {

    private fun task(url: String, total: Long = -1, downloaded: Long = 0): TaskRecord {
        val t = TaskRecord(url = url)
        t.totalSize = total
        t.downloaded = downloaded
        return t
    }

    @Test
    fun groupsByHostInFirstAppearanceOrder() {
        val a1 = task("https://a.com/1.zip")
        val b1 = task("https://b.com/2.zip")
        val a2 = task("https://a.com/3.zip")
        val groups = SiteGroups.group(listOf(a1, b1, a2))
        assertEquals(listOf("a.com", "b.com"), groups.map { it.host })
        assertEquals(listOf(a1, a2), groups[0].tasks)
        assertEquals(listOf(b1), groups[1].tasks)
    }

    @Test
    fun inputOrderIsKeptInsideGroups() {
        // The caller hands in the tab-filtered + sorted list;
        // grouping must not re-sort inside a group.
        val big = task("https://a.com/big.zip", total = 1000)
        val small = task("https://a.com/small.zip", total = 10)
        val groups = SiteGroups.group(listOf(big, small))
        assertEquals(listOf(big, small), groups[0].tasks)
    }

    @Test
    fun unparseableHostGetsTheUnknownLabel() {
        val groups = SiteGroups.group(listOf(task("not a url at all")))
        assertEquals(SiteGroups.UNKNOWN_HOST, groups[0].host)
    }

    @Test
    fun aggregateProgressCountsOnlyKnownSizes() {
        val known = task("https://a.com/k.zip", total = 200, downloaded = 50)
        val unknown = task("https://a.com/u.zip", total = -1, downloaded = 9999)
        val g = SiteGroups.group(listOf(known, unknown))[0]
        assertEquals(200L, g.totalBytes)
        assertEquals(50L, g.doneBytes)
        assertEquals(25, g.progressPct)
    }

    @Test
    fun aggregateProgressIsNullWhenNoSizeIsKnown() {
        val g = SiteGroups.group(listOf(task("https://a.com/u.zip")))[0]
        assertNull(g.progressPct)
    }

    @Test
    fun downloadedIsClampedToTotal() {
        // A stale counter above the total must not push the
        // group past 100%.
        val over = task("https://a.com/o.zip", total = 100, downloaded = 250)
        val g = SiteGroups.group(listOf(over))[0]
        assertEquals(100, g.progressPct)
    }

    @Test
    fun emptyInputGivesNoGroups() {
        assertEquals(emptyList<SiteGroups.Group>(), SiteGroups.group(emptyList()))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.SpeedHistory
import com.tuxundown.android.core.SpeedVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.1: speed-test history — pure store (cap/order, JSON, tolerance). */
class SpeedHistoryTest {

    private fun entry(
        atMs: Long,
        point: String = "Cloudflare 测速节点",
        single: Long = 1_000_000,
        multi: Long = 2_000_000,
        verdict: SpeedVerdict.Verdict = SpeedVerdict.Verdict.FASTER,
    ) = SpeedHistory.Entry(atMs, point, single, multi, verdict)

    @Test
    fun addPrependsNewestFirst() {
        var list = SpeedHistory.clear()
        list = SpeedHistory.add(list, entry(1_000))
        list = SpeedHistory.add(list, entry(2_000))
        list = SpeedHistory.add(list, entry(3_000))
        assertEquals(listOf(3_000L, 2_000L, 1_000L), list.map { it.atMs })
    }

    @Test
    fun addTruncatesToTenKeepingNewest() {
        var list = SpeedHistory.clear()
        for (i in 1..12) list = SpeedHistory.add(list, entry(i * 1_000L))
        assertEquals(SpeedHistory.MAX_ENTRIES, list.size)
        // Newest (12000) first, oldest survivor is 3000; 1000/2000 fell off.
        assertEquals(12_000L, list.first().atMs)
        assertEquals(3_000L, list.last().atMs)
    }

    @Test
    fun clearEmpties() {
        val list = SpeedHistory.add(SpeedHistory.clear(), entry(1_000))
        assertTrue(SpeedHistory.clear().isEmpty())
        assertEquals(1, list.size)
    }

    @Test
    fun serializeParseRoundTrip() {
        val original = listOf(
            entry(1_700_000_000_000, "Cloudflare 测速节点", 3_700_000, 7_900_000, SpeedVerdict.Verdict.FASTER),
            entry(1_699_000_000_000, "kernel.org 镜像站（大文件，只取开头一段）", 5_000_000, 4_000_000, SpeedVerdict.Verdict.SLOWER),
            entry(1_698_000_000_000, "CacheFly 测速文件（大文件，只取开头一段）", 2_000_000, 2_100_000, SpeedVerdict.Verdict.SIMILAR),
        )
        assertEquals(original, SpeedHistory.parse(SpeedHistory.serialize(original)))
        assertEquals(emptyList<SpeedHistory.Entry>(), SpeedHistory.parse(SpeedHistory.serialize(emptyList())))
    }

    @Test
    fun parseToleratesGarbage() {
        assertTrue(SpeedHistory.parse("").isEmpty())
        assertTrue(SpeedHistory.parse("not json").isEmpty())
        assertTrue(SpeedHistory.parse("{}").isEmpty())
        assertTrue(SpeedHistory.parse("[1,2,3]").isEmpty())
        assertTrue(SpeedHistory.parse("[]").isEmpty())
    }

    @Test
    fun parseDropsUnsoundEntriesKeepsSoundOnes() {
        val good = SpeedHistory.serialize(listOf(entry(5_000)))
        // Inject unsound siblings around the one good entry:
        // unknown verdict, blank point, zero speed, missing fields.
        val mixed = "[" +
            """{"at":9000,"point":"X","single":100,"multi":200,"verdict":"NOPE"},""" +
            """{"at":8000,"point":"","single":100,"multi":200,"verdict":"FASTER"},""" +
            """{"at":7000,"point":"X","single":0,"multi":200,"verdict":"FASTER"},""" +
            """{"at":6000,"point":"X"},""" +
            good.trim('[', ']') +
            "]"
        val parsed = SpeedHistory.parse(mixed)
        assertEquals(1, parsed.size)
        assertEquals(5_000L, parsed[0].atMs)
    }

    @Test
    fun parseCapsAtTen() {
        val many = (1..15).map { entry(it * 1_000L) }
        val parsed = SpeedHistory.parse(SpeedHistory.serialize(many))
        assertEquals(SpeedHistory.MAX_ENTRIES, parsed.size)
        assertEquals(1_000L, parsed.first().atMs)
    }

    @Test
    fun verdictShortLabels() {
        assertEquals("多线程更快", SpeedHistory.verdictShort(SpeedVerdict.Verdict.FASTER))
        assertEquals("单多线程差不多", SpeedHistory.verdictShort(SpeedVerdict.Verdict.SIMILAR))
        assertEquals("多线程更慢", SpeedHistory.verdictShort(SpeedVerdict.Verdict.SLOWER))
    }
}

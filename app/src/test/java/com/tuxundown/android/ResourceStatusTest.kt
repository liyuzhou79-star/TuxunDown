package com.tuxundown.android

import com.tuxundown.android.core.MemoryTier
import com.tuxundown.android.core.MemoryTiers
import com.tuxundown.android.core.ResourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.7 (audit round 62, Step C): the 资源 section's
 * assembly caliber, pinned pure — all three rows present,
 * each row dropping alone when its own read failed, the
 * whole section absent when every read failed, and the
 * memory-tier row showing the tier's existing label
 * unchanged (see ResourceStatus).
 */
class ResourceStatusTest {

    @Test
    fun linesAssembleAllThreeRows() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = 42,
                memoryTier = MemoryTier.STANDARD,
                activeSamplers = 3,
            ),
        )
        assertEquals(3, rows.size)
        assertEquals("当前线程数", rows[0].first)
        assertEquals("本进程当前约 42 个（读取瞬间的近似值）", rows[0].second)
        assertEquals("内存档位", rows[1].first)
        assertEquals(MemoryTiers.tierLabel(MemoryTier.STANDARD), rows[1].second)
        assertEquals("采样器活跃数", rows[2].first)
        assertEquals("当前共 3 个（按正在运行的引擎计）", rows[2].second)
    }

    @Test
    fun linesShowEveryTierByItsExistingLabel() {
        // The row is the tier's own label, never reworded
        // or re-derived here.
        for (tier in MemoryTier.values()) {
            val rows = ResourceStatus.lines(
                ResourceStatus.Snapshot(
                    threadCount = null,
                    memoryTier = tier,
                    activeSamplers = null,
                ),
            )
            assertEquals(1, rows.size)
            assertEquals("内存档位", rows[0].first)
            assertEquals(MemoryTiers.tierLabel(tier), rows[0].second)
        }
    }

    @Test
    fun linesShowRealZeros() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = 0,
                memoryTier = MemoryTier.LOW,
                activeSamplers = 0,
            ),
        )
        assertEquals(3, rows.size)
        assertEquals("本进程当前约 0 个（读取瞬间的近似值）", rows[0].second)
        assertEquals("当前共 0 个（按正在运行的引擎计）", rows[2].second)
    }

    @Test
    fun linesDropEachUnreadableRowAlone() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = null,
                memoryTier = MemoryTier.HIGH,
                activeSamplers = null,
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("内存档位", rows[0].first)
    }

    @Test
    fun linesDropNegativeFigures() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = -1,
                memoryTier = null,
                activeSamplers = -2,
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun linesShowGradualFormWhenFullWidthOpenIsFalse() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = 42,
                memoryTier = MemoryTier.STANDARD,
                activeSamplers = 3,
                fullWidthOpen = false,
            ),
        )
        assertEquals(4, rows.size)
        // The form row is last; the earlier rows are
        // exactly as before.
        assertEquals("当前线程数", rows[0].first)
        assertEquals("内存档位", rows[1].first)
        assertEquals("采样器活跃数", rows[2].first)
        assertEquals("连接放行形态", rows[3].first)
        assertEquals("渐进放行（先开一批，两三秒内分档加满）", rows[3].second)
    }

    @Test
    fun linesShowInstantFormWhenFullWidthOpenIsTrue() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = null,
                memoryTier = null,
                activeSamplers = null,
                fullWidthOpen = true,
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("连接放行形态", rows[0].first)
        assertEquals("瞬间满开（记住的站第 0 秒全开）", rows[0].second)
    }

    @Test
    fun linesDropFormRowAloneWhenUnreadable() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = 42,
                memoryTier = MemoryTier.STANDARD,
                activeSamplers = 3,
                fullWidthOpen = null,
            ),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.first == "连接放行形态" })
        assertEquals("当前线程数", rows[0].first)
        assertEquals("内存档位", rows[1].first)
        assertEquals("采样器活跃数", rows[2].first)
    }

    @Test
    fun linesAllUnreadableMeansSectionAbsent() {
        val rows = ResourceStatus.lines(
            ResourceStatus.Snapshot(
                threadCount = null,
                memoryTier = null,
                activeSamplers = null,
            ),
        )
        assertTrue(rows.isEmpty())
    }
}

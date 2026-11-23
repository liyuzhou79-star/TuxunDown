package com.tuxundown.android

import com.tuxundown.android.core.TaskPriority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit round 40: TaskPriority had NO direct test — clamp was
 * only exercised indirectly with in-band values (1 / 0 / -1)
 * through QueueOrder and BandwidthBudget. The load-bearing
 * case is the OUT-OF-BAND one: a stored priority outside the
 * three tiers (legacy data, a hand-carried backup) must read
 * as NORMAL, never as a phantom tier that outranks 高 or
 * undercuts 低 in the queue order or the budget weights.
 */
class TaskPriorityTest {

    @Test
    fun theThreeTiersPassThrough() {
        assertEquals(TaskPriority.HIGH, TaskPriority.clamp(1))
        assertEquals(TaskPriority.NORMAL, TaskPriority.clamp(0))
        assertEquals(TaskPriority.LOW, TaskPriority.clamp(-1))
        assertEquals(1, TaskPriority.HIGH)
        assertEquals(0, TaskPriority.NORMAL)
        assertEquals(-1, TaskPriority.LOW)
    }

    @Test
    fun outOfBandValuesReadAsNormal() {
        assertEquals(TaskPriority.NORMAL, TaskPriority.clamp(2))
        assertEquals(TaskPriority.NORMAL, TaskPriority.clamp(99))
        assertEquals(TaskPriority.NORMAL, TaskPriority.clamp(Int.MAX_VALUE))
        assertEquals(TaskPriority.NORMAL, TaskPriority.clamp(-2))
        assertEquals(TaskPriority.NORMAL, TaskPriority.clamp(Int.MIN_VALUE))
    }

    @Test
    fun labelsFollowTheClampedTier() {
        assertEquals("高", TaskPriority.label(TaskPriority.HIGH))
        assertEquals("中", TaskPriority.label(TaskPriority.NORMAL))
        assertEquals("低", TaskPriority.label(TaskPriority.LOW))
        // A stray stored value labels as the tier it acts as.
        assertEquals("中", TaskPriority.label(7))
        assertEquals("中", TaskPriority.label(-42))
    }
}

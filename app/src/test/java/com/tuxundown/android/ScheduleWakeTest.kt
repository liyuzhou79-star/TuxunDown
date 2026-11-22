package com.tuxundown.android

import com.tuxundown.android.core.ScheduleWake
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.5 audit round 21: the scheduled-start backup alarm's
 * pure decisions — which tasks count as DUE (the receiver
 * may kick the service) and which instant to ARM at (the
 * earliest future slot). The platform half (AlarmManager,
 * the receiver's service start) is deliberately thin and
 * runCatching-wrapped; these functions are the whole
 * policy.
 */
class ScheduleWakeTest {

    private val now = 1_700_000_000_000L

    private fun task(
        status: TaskStatus,
        scheduledAt: Long = 0,
        daily: Boolean = false,
    ): TaskRecord = TaskRecord(
        url = "https://x/${System.nanoTime()}",
        status = status,
        scheduledAt = scheduledAt,
        scheduleDaily = daily,
    )

    @Test
    fun dueMeansQueuedAndTimeArrived() {
        assertTrue(ScheduleWake.anyDue(listOf(task(TaskStatus.QUEUED, now - 1)), now))
        // Exactly at the slot counts as due (the drain loop's
        // own rule is scheduledAt <= now).
        assertTrue(ScheduleWake.anyDue(listOf(task(TaskStatus.QUEUED, now)), now))
        assertFalse(ScheduleWake.anyDue(listOf(task(TaskStatus.QUEUED, now + 60_000)), now))
        // No schedule at all is not "due by schedule" — such
        // tasks are the drain loop's business, not the alarm's.
        assertFalse(ScheduleWake.anyDue(listOf(task(TaskStatus.QUEUED, 0)), now))
    }

    @Test
    fun onlyQueuedTasksCanBeDue() {
        for (s in listOf(
            TaskStatus.PAUSED, TaskStatus.RUNNING,
            TaskStatus.DONE, TaskStatus.FAILED,
        )) {
            assertFalse("status $s", ScheduleWake.anyDue(listOf(task(s, now - 1)), now))
        }
    }

    @Test
    fun dailySlotDueCountsTheSame() {
        // A daily task is just a QUEUED record whose slot
        // arrived; recurrence advances the slot in the
        // service, not here.
        assertTrue(
            ScheduleWake.anyDue(
                listOf(task(TaskStatus.QUEUED, now - 1, daily = true)), now
            )
        )
    }

    @Test
    fun dueTasksReturnsExactlyTheDueOnes() {
        val due = task(TaskStatus.QUEUED, now - 5)
        val future = task(TaskStatus.QUEUED, now + 5_000)
        val paused = task(TaskStatus.PAUSED, now - 5)
        assertEquals(
            listOf(due),
            ScheduleWake.dueTasks(listOf(future, due, paused), now),
        )
    }

    @Test
    fun nextWakeIsTheEarliestFutureSlot() {
        val tasks = listOf(
            task(TaskStatus.QUEUED, now + 3_600_000),
            task(TaskStatus.QUEUED, now + 600_000),
            task(TaskStatus.QUEUED, now - 1), // past: not a wake time
        )
        assertEquals(now + 600_000, ScheduleWake.nextWakeAt(tasks, now))
    }

    @Test
    fun nextWakeIgnoresNonQueuedAndUnscheduled() {
        assertEquals(
            0L,
            ScheduleWake.nextWakeAt(
                listOf(
                    task(TaskStatus.PAUSED, now + 600_000),
                    task(TaskStatus.QUEUED, 0),
                    task(TaskStatus.DONE, now + 600_000),
                ),
                now,
            ),
        )
        assertEquals(0L, ScheduleWake.nextWakeAt(emptyList(), now))
    }
}

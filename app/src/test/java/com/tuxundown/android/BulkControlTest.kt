package com.tuxundown.android

import com.tuxundown.android.core.BulkControl
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkControlTest {

    private fun task(status: TaskStatus) =
        TaskRecord(url = "https://example.com/f.bin", status = status)

    @Test
    fun runningMeansPauseAll() {
        val tasks = listOf(task(TaskStatus.RUNNING), task(TaskStatus.PAUSED))
        assertEquals(BulkControl.Action.PAUSE_ALL, BulkControl.action(tasks))
        assertEquals(1, BulkControl.activeCount(tasks))
        assertEquals(listOf(tasks[0]), BulkControl.pauseTargets(tasks))
    }

    @Test
    fun queuedAlsoMeansPauseAll() {
        val tasks = listOf(task(TaskStatus.QUEUED), task(TaskStatus.DONE))
        assertEquals(BulkControl.Action.PAUSE_ALL, BulkControl.action(tasks))
    }

    @Test
    fun onlyPausedOrFailedMeansResumeAll() {
        val tasks = listOf(
            task(TaskStatus.PAUSED), task(TaskStatus.FAILED), task(TaskStatus.DONE),
        )
        assertEquals(BulkControl.Action.RESUME_ALL, BulkControl.action(tasks))
        assertEquals(2, BulkControl.resumableCount(tasks))
        assertEquals(tasks.take(2), BulkControl.resumeTargets(tasks))
    }

    @Test
    fun buttonLabelFollowsAction() {
        assertEquals("全部暂停", BulkControl.buttonLabel(BulkControl.Action.PAUSE_ALL))
        assertEquals("全部继续", BulkControl.buttonLabel(BulkControl.Action.RESUME_ALL))
        assertEquals(null, BulkControl.buttonLabel(BulkControl.Action.NONE))
    }

    @Test
    fun buttonLabelAgreesWithActionOnTaskLists() {
        // The widget derives its button straight from
        // buttonLabel(action(tasks)): label and decision must
        // never drift apart on any task mix.
        fun labelOf(tasks: List<TaskRecord>) =
            BulkControl.buttonLabel(BulkControl.action(tasks))
        assertEquals(
            "全部暂停",
            labelOf(listOf(task(TaskStatus.RUNNING), task(TaskStatus.PAUSED))),
        )
        assertEquals(
            "全部继续",
            labelOf(listOf(task(TaskStatus.FAILED), task(TaskStatus.DONE))),
        )
        assertEquals(null, labelOf(listOf(task(TaskStatus.DONE))))
        assertEquals(null, labelOf(emptyList()))
    }

    @Test
    fun doneOnlyAndEmptyMeanNone() {
        assertEquals(
            BulkControl.Action.NONE,
            BulkControl.action(listOf(task(TaskStatus.DONE))),
        )
        assertEquals(BulkControl.Action.NONE, BulkControl.action(emptyList()))
        assertEquals(0, BulkControl.activeCount(emptyList()))
        assertEquals(0, BulkControl.resumableCount(emptyList()))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.LiveTickPolicy
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v7.6 (audit round 61, Step B-1/B-5): the UI live tick's
 * grade decision and per-card tick policy. The grade is
 * what stops the foreground 1 s spin in static scenes; the
 * live set is what keeps the fields that truly move per
 * second (sampler values, the finalizing flip, checksum
 * progress) on the 1 s beat.
 */
class LiveTickPolicyTest {

    private fun task(status: TaskStatus) =
        TaskRecord(url = "https://a.com/f.zip", status = status)

    private fun finishedDone() = task(TaskStatus.DONE).apply {
        savedUri = "content://downloads/f.zip"
    }

    @Test
    fun emptyAndStaticScenesAreIdle() {
        assertEquals(LiveTickPolicy.Grade.IDLE, LiveTickPolicy.grade(emptyList()))
        assertEquals(
            LiveTickPolicy.Grade.IDLE,
            LiveTickPolicy.grade(
                listOf(task(TaskStatus.PAUSED), finishedDone(), task(TaskStatus.FAILED)),
            ),
        )
    }

    @Test
    fun queuedOnlyIsSlow() {
        assertEquals(
            LiveTickPolicy.Grade.SLOW,
            LiveTickPolicy.grade(listOf(task(TaskStatus.PAUSED), task(TaskStatus.QUEUED))),
        )
    }

    @Test
    fun runningIsFast() {
        assertEquals(
            LiveTickPolicy.Grade.FAST,
            LiveTickPolicy.grade(listOf(task(TaskStatus.QUEUED), task(TaskStatus.RUNNING))),
        )
    }

    @Test
    fun finalizingDoneIsFastSoTheFlipIsPrompt() {
        // DONE with savedUri still blank is the finalize
        // window — its flip to finished must land per second.
        assertEquals(
            LiveTickPolicy.Grade.FAST,
            LiveTickPolicy.grade(listOf(task(TaskStatus.DONE))),
        )
    }

    @Test
    fun verifyingTaskIsFastSoChecksumProgressMoves() {
        val verifying = finishedDone().apply {
            checksumState = Checksum.STATE_VERIFYING
        }
        assertEquals(
            LiveTickPolicy.Grade.FAST,
            LiveTickPolicy.grade(listOf(verifying)),
        )
    }

    @Test
    fun delayMapping() {
        assertEquals(1_000L, LiveTickPolicy.delayMs(LiveTickPolicy.Grade.FAST))
        assertEquals(5_000L, LiveTickPolicy.delayMs(LiveTickPolicy.Grade.SLOW))
        assertEquals(0L, LiveTickPolicy.delayMs(LiveTickPolicy.Grade.IDLE))
    }

    @Test
    fun cardTickOnlyFlowsToLiveCards() {
        val tick = 42
        assertEquals(tick, LiveTickPolicy.cardTick(task(TaskStatus.RUNNING), tick))
        assertEquals(tick, LiveTickPolicy.cardTick(task(TaskStatus.DONE), tick)) // finalizing
        val verifying = finishedDone().apply {
            checksumState = Checksum.STATE_VERIFYING
        }
        assertEquals(tick, LiveTickPolicy.cardTick(verifying, tick))
        assertEquals(0, LiveTickPolicy.cardTick(task(TaskStatus.PAUSED), tick))
        assertEquals(0, LiveTickPolicy.cardTick(task(TaskStatus.QUEUED), tick))
        assertEquals(0, LiveTickPolicy.cardTick(task(TaskStatus.FAILED), tick))
        assertEquals(0, LiveTickPolicy.cardTick(finishedDone(), tick))
    }
}

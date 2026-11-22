package com.tuxundown.android

import com.tuxundown.android.core.QueuePreheat
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.5: queue preheat guards (see QueuePreheat). Every guard is
 * a parameter of the pure decision, and every one is pinned
 * here in both directions where the shape allows.
 */
class QueuePreheatTest {

    private fun task(
        status: TaskStatus,
        url: String = "https://a.example.com/file.zip",
        scheduledAt: Long = 0,
        priority: Int = 0,
    ): TaskRecord = TaskRecord(
        url = url, status = status, scheduledAt = scheduledAt,
    ).apply { this.priority = priority }

    private fun decide(
        tasks: List<TaskRecord>,
        onWifi: Boolean = true,
        dualActive: Boolean = false,
        trafficProtectionOn: Boolean = false,
        proxyActive: Boolean = false,
        alreadyWarmed: Set<String> = emptySet(),
        inFlightKey: String? = null,
        now: Long = 1_000,
    ) = QueuePreheat.decide(
        tasks, now, onWifi, dualActive, trafficProtectionOn,
        proxyActive, alreadyWarmed, inFlightKey,
    )

    // ---- happy path & target selection ----

    @Test
    fun runningPlusQueuedYieldsFirstQueuedPrimaryHost() {
        val running = task(TaskStatus.RUNNING, "https://r.example.com/x.bin")
        val queued = task(TaskStatus.QUEUED).apply {
            sources.add("https://mirror.example.com/file.zip")
        }
        val later = task(TaskStatus.QUEUED, "https://b.example.com/y.bin")
        val target = decide(listOf(running, queued, later))!!
        assertEquals(queued.id, target.taskId)
        // Primary link only — mirrors are the engine warmup's job.
        assertEquals("https://a.example.com/file.zip", target.url)
        assertEquals("a.example.com", target.host)
        assertEquals(QueuePreheat.keyFor(queued.id, "a.example.com"), target.key)
    }

    @Test
    fun targetFollowsNextEligibleOrder() {
        val running = task(TaskStatus.RUNNING)
        // A not-yet-due scheduled task is not in line; a due
        // high-priority task behind a normal one goes first.
        val scheduled = task(
            TaskStatus.QUEUED, "https://s.example.com/f", scheduledAt = 9_999,
        )
        val normal = task(TaskStatus.QUEUED, "https://n.example.com/f")
        val high = task(
            TaskStatus.QUEUED, "https://h.example.com/f", priority = 1,
        )
        val target = decide(listOf(running, scheduled, normal, high))!!
        assertEquals(high.id, target.taskId)
        // Deferred (parked) tasks are not in line either.
        val deferred = task(TaskStatus.QUEUED, "https://d.example.com/f")
            .apply { netDeferred = true }
        assertEquals(
            normal.id,
            decide(listOf(running, deferred, normal))!!.taskId,
        )
    }

    @Test
    fun targetIgnoresTheTasksOwnGates() {
        // Preheat is not a start decision: the candidate's own
        // size / mobile-confirmation state (its per-task
        // traffic-guard inputs at real start) play no part —
        // a huge, unconfirmed task is still the preheat target,
        // and every gate judges again when it really starts.
        val running = task(TaskStatus.RUNNING)
        val big = task(TaskStatus.QUEUED).apply {
            totalSize = 5_000_000_000L
            mobileConfirmed = false
        }
        assertEquals(big.id, decide(listOf(running, big))!!.taskId)
    }

    // ---- guard: someone must be running ----

    @Test
    fun noRunningTaskNoPreheat() {
        val queued = task(TaskStatus.QUEUED)
        assertNull(decide(listOf(queued)))
        assertNull(decide(emptyList()))
        assertNull(
            decide(listOf(task(TaskStatus.PAUSED), task(TaskStatus.DONE), queued)),
        )
    }

    @Test
    fun noQueuedTaskNoPreheat() {
        assertNull(decide(listOf(task(TaskStatus.RUNNING))))
    }

    // ---- guard: network (WiFi or dual only) ----

    @Test
    fun pureCellularNeverPreheats() {
        val tasks = listOf(task(TaskStatus.RUNNING), task(TaskStatus.QUEUED))
        assertNull(decide(tasks, onWifi = false, dualActive = false))
    }

    @Test
    fun dualChannelCountsAsWifiClass() {
        val tasks = listOf(task(TaskStatus.RUNNING), task(TaskStatus.QUEUED))
        // Dual active but the active network is not plain WiFi:
        // the user explicitly opted into WiFi+cellular together.
        assertTrue(decide(tasks, onWifi = false, dualActive = true) != null)
        assertTrue(decide(tasks, onWifi = true, dualActive = true) != null)
    }

    // ---- guard: traffic protection vetoes outright ----

    @Test
    fun trafficProtectionOnVetoesEvenOnWifi() {
        val tasks = listOf(task(TaskStatus.RUNNING), task(TaskStatus.QUEUED))
        assertNull(decide(tasks, onWifi = true, trafficProtectionOn = true))
        assertNull(
            decide(tasks, onWifi = false, dualActive = true, trafficProtectionOn = true),
        )
    }

    // ---- guard: proxy skips ----

    @Test
    fun proxyActiveSkips() {
        val tasks = listOf(task(TaskStatus.RUNNING), task(TaskStatus.QUEUED))
        assertNull(decide(tasks, proxyActive = true))
    }

    // ---- guard: one at a time / once per task+host ----

    @Test
    fun inFlightPreheatBlocksAnother() {
        val running = task(TaskStatus.RUNNING)
        val queued = task(TaskStatus.QUEUED)
        val other = task(TaskStatus.QUEUED, "https://b.example.com/y.bin")
        assertNull(
            decide(
                listOf(running, queued, other),
                inFlightKey = QueuePreheat.keyFor(queued.id, "a.example.com"),
            ),
        )
        // An in-flight warm for ANY task blocks: one at a time.
        assertNull(
            decide(
                listOf(running, queued, other),
                inFlightKey = "someone-else\nelsewhere.example.com",
            ),
        )
    }

    @Test
    fun alreadyWarmedTaskHostIsNotWarmedAgain() {
        val running = task(TaskStatus.RUNNING)
        val queued = task(TaskStatus.QUEUED)
        val key = QueuePreheat.keyFor(queued.id, "a.example.com")
        assertNull(decide(listOf(running, queued), alreadyWarmed = setOf(key)))
        // The mark is per task AND per host: a different host
        // (link edited) or a different task is a new warm.
        val edited = task(TaskStatus.QUEUED, "https://c.example.com/file.zip")
        assertEquals(
            edited.id,
            decide(listOf(running, edited), alreadyWarmed = setOf(key))!!.taskId,
        )
        val otherTaskSameHost = task(TaskStatus.QUEUED)
        assertEquals(
            otherTaskSameHost.id,
            decide(
                listOf(running, otherTaskSameHost),
                alreadyWarmed = setOf(key),
            )!!.taskId,
        )
    }

    @Test
    fun keyIsHostCaseInsensitive() {
        assertEquals(
            QueuePreheat.keyFor("id1", "EXAMPLE.com"),
            QueuePreheat.keyFor("id1", "example.COM"),
        )
    }

    // ---- guard: only warmable primaries ----

    @Test
    fun nonHttpPrimaryWarmsNothing() {
        val running = task(TaskStatus.RUNNING)
        val ftp = task(TaskStatus.QUEUED, "ftp://a.example.com/file.zip")
        assertNull(decide(listOf(running, ftp)))
        val blank = task(TaskStatus.QUEUED, "")
        assertNull(decide(listOf(running, blank)))
    }

    // ---- stale-target discard (stillTarget) ----

    @Test
    fun stillTargetHoldsForTheWaitingFirstTask() {
        val running = task(TaskStatus.RUNNING)
        val queued = task(TaskStatus.QUEUED)
        assertTrue(
            QueuePreheat.stillTarget(
                listOf(running, queued), 1_000, queued.id, "a.example.com",
            ),
        )
        // Host case does not matter.
        assertTrue(
            QueuePreheat.stillTarget(
                listOf(running, queued), 1_000, queued.id, "A.Example.COM",
            ),
        )
    }

    @Test
    fun stillTargetDropsDeletedPausedAndStartedTasks() {
        val running = task(TaskStatus.RUNNING)
        val queued = task(TaskStatus.QUEUED)
        // Deleted: no longer in the list at all.
        assertFalse(
            QueuePreheat.stillTarget(
                listOf(running), 1_000, queued.id, "a.example.com",
            ),
        )
        // Paused by the user mid-preheat.
        queued.status = TaskStatus.PAUSED
        assertFalse(
            QueuePreheat.stillTarget(
                listOf(running, queued), 1_000, queued.id, "a.example.com",
            ),
        )
        // Started for real: the engine's own warmup owns it now.
        queued.status = TaskStatus.RUNNING
        assertFalse(
            QueuePreheat.stillTarget(
                listOf(running, queued), 1_000, queued.id, "a.example.com",
            ),
        )
    }

    @Test
    fun stillTargetDropsQueueOrderChangesAndLinkEdits() {
        val running = task(TaskStatus.RUNNING)
        val queued = task(TaskStatus.QUEUED)
        val jumped = task(
            TaskStatus.QUEUED, "https://j.example.com/f", priority = 1,
        )
        // Someone jumped the queue: our target is no longer first.
        assertFalse(
            QueuePreheat.stillTarget(
                listOf(running, queued, jumped), 1_000,
                queued.id, "a.example.com",
            ),
        )
        // The link was edited to another host mid-preheat.
        queued.url = "https://other.example.com/file.zip"
        assertFalse(
            QueuePreheat.stillTarget(
                listOf(running, queued), 1_000, queued.id, "a.example.com",
            ),
        )
    }
}

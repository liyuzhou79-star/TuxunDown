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
        chargeParked: Boolean = false,
        proxyActive: Boolean = false,
        alreadyWarmed: Set<String> = emptySet(),
        inFlightKey: String? = null,
        now: Long = 1_000,
    ) = QueuePreheat.decide(
        tasks, now, onWifi, dualActive, trafficProtectionOn,
        chargeParked, proxyActive, alreadyWarmed, inFlightKey,
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

    // ---- guard: charge park vetoes outright (v5.0, round 35) ----

    @Test
    fun chargeParkedVetoesEvenOnWifi() {
        // While the 充电时才下载 gate is closed no queued task
        // can start at all, so a warmed connection could only
        // expire unused — the live service-side condition
        // vetoes, whatever the network, whatever the flags on
        // the individual tasks.
        val tasks = listOf(task(TaskStatus.RUNNING), task(TaskStatus.QUEUED))
        assertNull(decide(tasks, onWifi = true, chargeParked = true))
        assertNull(
            decide(tasks, onWifi = false, dualActive = true, chargeParked = true),
        )
        // Power back (gate open): preheat resumes judging the
        // queue exactly as before.
        assertTrue(decide(tasks, onWifi = true, chargeParked = false) != null)
    }

    @Test
    fun netAndPortalParkedTasksAreNotPreheatTargets() {
        // Round 35, other half of the park family: net /
        // portal parks are excluded by QueueOrder.nextEligible
        // itself, so the preheat target skips straight past a
        // parked task to the next one actually in line.
        val running = task(TaskStatus.RUNNING)
        val netParked = task(TaskStatus.QUEUED, "https://np.example.com/f")
            .apply { netDeferred = true }
        val portalParked = task(TaskStatus.QUEUED, "https://pp.example.com/f")
            .apply { portalDeferred = true }
        val waiting = task(TaskStatus.QUEUED, "https://w.example.com/f")
        assertEquals(
            waiting.id,
            decide(listOf(running, netParked, portalParked, waiting))!!.taskId,
        )
        // And when ONLY parked tasks wait, there is no target.
        assertNull(decide(listOf(running, netParked, portalParked)))
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

    // ---- v7.7 (Step B3): the creation-moment trigger ----
    // decideAtCreation is decide() plus "the target must be
    // the created task itself" — every v4.5 guard must veto at
    // the new trigger point exactly as on the drain's beat.

    private fun decideCreated(
        tasks: List<TaskRecord>,
        createdTaskId: String,
        onWifi: Boolean = true,
        dualActive: Boolean = false,
        trafficProtectionOn: Boolean = false,
        chargeParked: Boolean = false,
        proxyActive: Boolean = false,
        alreadyWarmed: Set<String> = emptySet(),
        inFlightKey: String? = null,
        now: Long = 1_000,
    ) = QueuePreheat.decideAtCreation(
        tasks, createdTaskId, now, onWifi, dualActive,
        trafficProtectionOn, chargeParked, proxyActive,
        alreadyWarmed, inFlightKey,
    )

    @Test
    fun creationTriggerWarmsTheNewcomerWhenItIsFirstInLine() {
        val running = task(TaskStatus.RUNNING, "https://r.example.com/x.bin")
        val created = task(TaskStatus.QUEUED)
        val target = decideCreated(listOf(running, created), created.id)!!
        assertEquals(created.id, target.taskId)
        assertEquals("a.example.com", target.host)
    }

    @Test
    fun creationTriggerIgnoresANewcomerThatIsNotFirstInLine() {
        // Another queued task is ahead (higher priority): the
        // creation moment earns the NEWCOMER nothing — warming
        // the task ahead stays the drain beat's standing job.
        val running = task(TaskStatus.RUNNING)
        val ahead = task(TaskStatus.QUEUED, "https://h.example.com/f", priority = 1)
        val created = task(TaskStatus.QUEUED, "https://a.example.com/f")
        assertNull(decideCreated(listOf(running, ahead, created), created.id))
        // ...while creating the task that IS first in line works.
        assertEquals(
            ahead.id,
            decideCreated(listOf(running, ahead, created), ahead.id)!!.taskId,
        )
    }

    @Test
    fun creationTriggerKeepsEveryGuard() {
        val running = task(TaskStatus.RUNNING)
        val created = task(TaskStatus.QUEUED)
        val tasks = listOf(running, created)
        // No RUNNING task (the created task alone) -> nothing.
        assertNull(decideCreated(listOf(created), created.id))
        // Pure cellular -> veto; dual channel still counts.
        assertNull(decideCreated(tasks, created.id, onWifi = false, dualActive = false))
        assertTrue(decideCreated(tasks, created.id, onWifi = false, dualActive = true) != null)
        // Traffic protection vetoes even on WiFi.
        assertNull(decideCreated(tasks, created.id, trafficProtectionOn = true))
        // Charge park vetoes.
        assertNull(decideCreated(tasks, created.id, chargeParked = true))
        // Proxy skips.
        assertNull(decideCreated(tasks, created.id, proxyActive = true))
        // One preheat already in flight -> no second one.
        assertNull(decideCreated(tasks, created.id, inFlightKey = "x\ny.example.com"))
        // This task+host already warmed while waiting -> no re-warm.
        assertNull(
            decideCreated(
                tasks, created.id,
                alreadyWarmed = setOf(QueuePreheat.keyFor(created.id, "a.example.com")),
            ),
        )
        // An FTP primary warms nothing (FTP excluded).
        val ftp = task(TaskStatus.QUEUED, "ftp://a.example.com/file.zip")
        assertNull(decideCreated(listOf(running, ftp), ftp.id))
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

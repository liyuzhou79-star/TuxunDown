package com.tuxundown.android

import com.tuxundown.android.core.GlobalSearch
import com.tuxundown.android.core.SiteGroups
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4 audit round 20 (big data): a heavy user can accumulate
 * 1,000+ task records. These tests pin the SHAPE of the data
 * paths at that scale — full round trips stay complete, the
 * serialized form stays within a sane size, import planning
 * dedupes in bulk, search/grouping stay correct. (Wall-clock
 * timing is deliberately NOT asserted: CI machines vary. The
 * complexity argument lives in CHECKPOINT round 20; these
 * tests pin correctness at scale so a future change that
 * breaks a path at 1,500 records fails loudly.)
 */
class ScaleAuditTest {

    /** A realistic fat record: chunk bitmap + full speed
     *  history + a couple of headers — the fields that dominate
     *  the serialized size. */
    private fun fatTask(i: Int): TaskRecord {
        val t = TaskRecord(
            id = "task-%04d".format(i),
            url = "https://host${i % 30}.example.com/dir/file-$i.bin",
            fileName = "file-$i.bin",
            totalSize = 260L * 1024 * 1024,
            chunkSize = 1024 * 1024,
            status = if (i % 3 == 0) TaskStatus.DONE else TaskStatus.PAUSED,
            createdAt = 1_700_000_000_000L + i * 1000L,
            finishedAt = if (i % 3 == 0) 1_700_000_100_000L + i * 1000L else 0,
        )
        t.chunkDone = BooleanArray(260) { it % 2 == 0 }
        t.headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 14)"
        t.headers["Referer"] = "https://host${i % 30}.example.com/page"
        synchronized(t.speedHistory) {
            repeat(90) { t.speedHistory.add(1_000_000L + it * 137L) }
        }
        t.peakBps = 8_388_608
        return t
    }

    @Test
    fun serializeRoundTripAt1500Tasks() {
        val tasks = (0 until 1500).map { fatTask(it) }
        val text = TaskStore.serialize(tasks)
        // The whole store at this scale must stay a small file:
        // ~1–2 KB per record (speed history dominates), so
        // 1,500 records belong well under 6 MB.
        assertTrue(
            "serialized store too big: ${text.length}",
            text.length < 6_000_000,
        )
        val back = TaskStore.deserialize(text)
        assertEquals(1500, back.size)
        val spot = back[777]
        assertEquals("task-0777", spot.id)
        assertEquals(260, spot.chunkDone.size)
        assertEquals(90, spot.speedHistory.size)
        assertEquals(8_388_608, spot.peakBps)
    }

    @Test
    fun planImportDedupesInBulk() {
        val existing = (0 until 1000).map {
            TaskRecord(
                id = "e-$it",
                url = "https://a.example.com/$it",
                status = TaskStatus.PAUSED,
            )
        }
        val incoming = ArrayList<TaskRecord>()
        // 400 records whose ids already exist -> skipped.
        for (i in 0 until 400) {
            incoming.add(TaskRecord(id = "e-$i", url = "https://b.example.com/$i"))
        }
        // 300 fresh ids but URLs already held by ACTIVE tasks -> skipped.
        for (i in 400 until 700) {
            incoming.add(
                TaskRecord(
                    id = "n-$i",
                    url = "https://a.example.com/${i - 400}",
                    status = TaskStatus.PAUSED,
                )
            )
        }
        // 300 genuinely new -> accepted.
        for (i in 700 until 1000) {
            incoming.add(
                TaskRecord(
                    id = "n-$i",
                    url = "https://c.example.com/$i",
                    status = TaskStatus.PAUSED,
                )
            )
        }
        val accepted = TaskStore.planImport(existing, incoming)
        assertEquals(300, accepted.size)
        assertTrue(accepted.all { it.id.startsWith("n-") && it.id.removePrefix("n-").toInt() >= 700 })
    }

    @Test
    fun planImportDedupesInsideTheIncomingFileItself() {
        val dup1 = TaskRecord(id = "x-1", url = "https://d.example.com/1", status = TaskStatus.PAUSED)
        val dup2 = TaskRecord(id = "x-1", url = "https://d.example.com/2", status = TaskStatus.PAUSED)
        val sameUrl = TaskRecord(id = "x-2", url = "https://d.example.com/1", status = TaskStatus.PAUSED)
        val doneSameUrl = TaskRecord(id = "x-3", url = "https://d.example.com/1", status = TaskStatus.DONE)
        val accepted = TaskStore.planImport(emptyList(), listOf(dup1, dup2, sameUrl, doneSameUrl))
        // dup2 loses on id; sameUrl loses on the active URL that
        // dup1 now holds; a DONE record is never blocked by URL.
        assertEquals(listOf("x-1", "x-3"), accepted.map { it.id })
    }

    @Test
    fun searchAndGroupingAt1500Tasks() {
        val tasks = (0 until 1500).map { fatTask(it) }
        tasks[42].fileName = "needle-unique-name.mkv"
        tasks[43].userNote = "needle 藏在备注里"
        val r = GlobalSearch.search(tasks, emptyList(), "needle")
        assertEquals(2, r.tasks.size)

        val groups = SiteGroups.group(tasks)
        assertEquals(30, groups.size)
        assertEquals(1500, groups.sumOf { it.tasks.size })
        assertTrue(groups.all { it.tasks.size == 50 })
    }
}

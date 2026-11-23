package com.tuxundown.android

import com.tuxundown.android.core.CreateDedup
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.4: the task-creation dedup verdict (see CreateDedup).
 * Pins the whole ruling: unfinished statuses block (FAILED
 * included), DONE does not, the mirror-set overlap rule, the
 * exact normalization boundary, and batch self-dedup.
 */
class CreateDedupTest {

    private fun existing(
        url: String,
        status: TaskStatus,
        mirrors: List<String> = emptyList(),
    ) = CreateDedup.Existing(url, status, mirrors)

    private fun candidate(
        url: String,
        mirrors: List<String> = emptyList(),
    ) = CreateDedup.Candidate(url, mirrors)

    private fun isDup(
        existing: List<CreateDedup.Existing>,
        candidate: CreateDedup.Candidate,
    ): Boolean = CreateDedup.judge(existing, candidate) is CreateDedup.Verdict.Duplicate

    // ---- statuses ----

    @Test
    fun queuedRunningPausedAllBlock() {
        val url = "https://a.example.com/f.zip"
        for (st in listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PAUSED)) {
            assertTrue("$st must block", isDup(listOf(existing(url, st)), candidate(url)))
        }
    }

    @Test
    fun failedBlocksToo() {
        // The ruling: a FAILED task is still in the list
        // waiting for a retry — a second record for the same
        // link would be a true duplicate.
        val url = "https://a.example.com/f.zip"
        assertTrue(isDup(listOf(existing(url, TaskStatus.FAILED)), candidate(url)))
    }

    @Test
    fun doneHistoryDoesNotBlock() {
        val url = "https://a.example.com/f.zip"
        assertFalse(isDup(listOf(existing(url, TaskStatus.DONE)), candidate(url)))
    }

    @Test
    fun doneAndUnfinishedForSameUrlStillBlocks() {
        // A DONE record in the history plus an unfinished one:
        // the unfinished one blocks (the verdict names it).
        val url = "https://a.example.com/f.zip"
        val v = CreateDedup.judge(
            listOf(existing(url, TaskStatus.DONE), existing(url, TaskStatus.PAUSED)),
            candidate(url),
        )
        assertTrue(v is CreateDedup.Verdict.Duplicate)
        assertEquals(
            TaskStatus.PAUSED,
            (v as CreateDedup.Verdict.Duplicate).blocker.status,
        )
    }

    @Test
    fun noExistingTasksAllows() {
        assertFalse(isDup(emptyList(), candidate("https://a.example.com/f.zip")))
    }

    @Test
    fun differentUrlAllows() {
        assertFalse(
            isDup(
                listOf(existing("https://a.example.com/a.zip", TaskStatus.RUNNING)),
                candidate("https://a.example.com/b.zip"),
            )
        )
    }

    @Test
    fun isUnfinishedTable() {
        assertTrue(CreateDedup.isUnfinished(TaskStatus.QUEUED))
        assertTrue(CreateDedup.isUnfinished(TaskStatus.RUNNING))
        assertTrue(CreateDedup.isUnfinished(TaskStatus.PAUSED))
        assertTrue(CreateDedup.isUnfinished(TaskStatus.FAILED))
        assertFalse(CreateDedup.isUnfinished(TaskStatus.DONE))
    }

    // ---- mirrors: any overlap of {primary + mirrors} is the same file ----

    @Test
    fun samePrimaryDifferentMirrorSetStillBlocks() {
        // The mirror set differing does NOT exempt: the second
        // download still produces the same file.
        assertTrue(
            isDup(
                listOf(
                    existing(
                        "https://a.example.com/f.zip", TaskStatus.QUEUED,
                        mirrors = listOf("https://m1.example.com/f.zip"),
                    )
                ),
                candidate(
                    "https://a.example.com/f.zip",
                    mirrors = listOf("https://m2.example.com/f.zip"),
                ),
            )
        )
    }

    @Test
    fun candidatePrimaryMatchingAnExistingMirrorBlocks() {
        assertTrue(
            isDup(
                listOf(
                    existing(
                        "https://a.example.com/f.zip", TaskStatus.RUNNING,
                        mirrors = listOf("https://m1.example.com/f.zip"),
                    )
                ),
                candidate("https://m1.example.com/f.zip"),
            )
        )
    }

    @Test
    fun candidateMirrorMatchingExistingPrimaryBlocks() {
        assertTrue(
            isDup(
                listOf(existing("https://a.example.com/f.zip", TaskStatus.PAUSED)),
                candidate(
                    "https://other.example.com/f.zip",
                    mirrors = listOf("https://a.example.com/f.zip"),
                ),
            )
        )
    }

    @Test
    fun candidateMirrorMatchingExistingMirrorBlocks() {
        assertTrue(
            isDup(
                listOf(
                    existing(
                        "https://a.example.com/f.zip", TaskStatus.QUEUED,
                        mirrors = listOf("https://m1.example.com/f.zip"),
                    )
                ),
                candidate(
                    "https://other.example.com/f.zip",
                    mirrors = listOf("https://m1.example.com/f.zip"),
                ),
            )
        )
    }

    @Test
    fun disjointMirrorSetsAllow() {
        assertFalse(
            isDup(
                listOf(
                    existing(
                        "https://a.example.com/f.zip", TaskStatus.QUEUED,
                        mirrors = listOf("https://m1.example.com/f.zip"),
                    )
                ),
                candidate(
                    "https://b.example.com/g.zip",
                    mirrors = listOf("https://m2.example.com/g.zip"),
                ),
            )
        )
    }

    @Test
    fun mirrorOfADoneTaskDoesNotBlock() {
        assertFalse(
            isDup(
                listOf(
                    existing(
                        "https://a.example.com/f.zip", TaskStatus.DONE,
                        mirrors = listOf("https://m1.example.com/f.zip"),
                    )
                ),
                candidate("https://m1.example.com/f.zip"),
            )
        )
    }

    // ---- normalization boundary ----

    @Test
    fun hostAndSchemeCaseAreInsensitive() {
        assertTrue(
            isDup(
                listOf(existing("HTTPS://A.Example.COM/f.zip", TaskStatus.QUEUED)),
                candidate("https://a.example.com/f.zip"),
            )
        )
    }

    @Test
    fun pathCaseIsSensitive() {
        assertFalse(
            isDup(
                listOf(existing("https://a.example.com/F.zip", TaskStatus.QUEUED)),
                candidate("https://a.example.com/f.zip"),
            )
        )
    }

    @Test
    fun queryIsComparedExactly() {
        assertFalse(
            isDup(
                listOf(existing("https://a.example.com/f.zip?tok=1", TaskStatus.QUEUED)),
                candidate("https://a.example.com/f.zip?tok=2"),
            )
        )
        assertTrue(
            isDup(
                listOf(existing("https://a.example.com/f.zip?tok=1", TaskStatus.QUEUED)),
                candidate("https://a.example.com/f.zip?tok=1"),
            )
        )
    }

    @Test
    fun trailingSlashIsNotStripped() {
        // "/dir" and "/dir/" can be different resources: the
        // normalization deliberately does not merge them.
        assertFalse(
            isDup(
                listOf(existing("https://a.example.com/dir/", TaskStatus.QUEUED)),
                candidate("https://a.example.com/dir"),
            )
        )
    }

    @Test
    fun emptyPathEqualsRootSlash() {
        assertTrue(
            isDup(
                listOf(existing("https://a.example.com", TaskStatus.QUEUED)),
                candidate("https://a.example.com/"),
            )
        )
    }

    @Test
    fun defaultPortIsDroppedNonDefaultKept() {
        assertTrue(
            isDup(
                listOf(existing("http://a.example.com:80/f.zip", TaskStatus.QUEUED)),
                candidate("http://a.example.com/f.zip"),
            )
        )
        assertTrue(
            isDup(
                listOf(existing("https://a.example.com:443/f.zip", TaskStatus.QUEUED)),
                candidate("https://a.example.com/f.zip"),
            )
        )
        assertFalse(
            isDup(
                listOf(existing("http://a.example.com:8080/f.zip", TaskStatus.QUEUED)),
                candidate("http://a.example.com/f.zip"),
            )
        )
    }

    @Test
    fun fragmentIsDropped() {
        assertTrue(
            isDup(
                listOf(existing("https://a.example.com/f.zip#part1", TaskStatus.QUEUED)),
                candidate("https://a.example.com/f.zip#part2"),
            )
        )
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertTrue(
            isDup(
                listOf(existing("  https://a.example.com/f.zip  ", TaskStatus.QUEUED)),
                candidate("https://a.example.com/f.zip"),
            )
        )
    }

    @Test
    fun normalizeRuleTable() {
        assertEquals(
            "https://a.example.com/f.zip",
            CreateDedup.normalize("HTTPS://A.Example.COM:443/f.zip#x"),
        )
        assertEquals(
            "http://a.example.com:8080/f.zip",
            CreateDedup.normalize("http://a.example.com:8080/f.zip"),
        )
        assertEquals(
            "https://a.example.com/",
            CreateDedup.normalize("https://a.example.com"),
        )
        assertEquals(
            "https://a.example.com/?q=1",
            CreateDedup.normalize("https://a.example.com?q=1"),
        )
        // No scheme:// prefix: trimmed verbatim, no merging.
        assertEquals("notaurl", CreateDedup.normalize("  notaurl "))
        // Userinfo stays verbatim (case-sensitive).
        assertEquals(
            "https://User@a.example.com/f.zip",
            CreateDedup.normalize("https://User@A.example.com/f.zip"),
        )
        // IPv6 host lowercases, default port drops.
        assertEquals(
            "http://[::1]/f.zip",
            CreateDedup.normalize("http://[::1]:80/f.zip"),
        )
    }

    // ---- batch self-dedup ----

    @Test
    fun batchSecondOccurrenceIsDuplicateOfFirst() {
        val url = "https://a.example.com/f.zip"
        val plan = CreateDedup.planBatch(
            emptyList(),
            listOf(candidate(url), candidate(url)),
        )
        assertEquals(1, plan.accepted.size)
        assertEquals(1, plan.duplicates.size)
        assertEquals(url, plan.duplicates[0].first.url)
        // The blocker is the accepted first candidate, as a
        // QUEUED snapshot.
        assertEquals(TaskStatus.QUEUED, plan.duplicates[0].second.status)
        assertEquals(url, plan.duplicates[0].second.url)
    }

    @Test
    fun batchAgainstExistingAndWithinBatch() {
        val plan = CreateDedup.planBatch(
            listOf(existing("https://a.example.com/a.zip", TaskStatus.RUNNING)),
            listOf(
                candidate("https://a.example.com/a.zip"), // vs existing
                candidate("https://a.example.com/b.zip"), // accepted
                candidate("https://a.example.com/b.zip"), // vs batch
                candidate("https://a.example.com/c.zip"), // accepted
            ),
        )
        assertEquals(
            listOf("https://a.example.com/b.zip", "https://a.example.com/c.zip"),
            plan.accepted.map { it.url },
        )
        assertEquals(2, plan.duplicates.size)
    }

    @Test
    fun batchCandidateAfterDoneHistoryIsAcceptedThenBlocks() {
        val url = "https://a.example.com/f.zip"
        val plan = CreateDedup.planBatch(
            listOf(existing(url, TaskStatus.DONE)),
            listOf(candidate(url), candidate(url)),
        )
        // DONE does not block the first; the first (accepted)
        // blocks the second.
        assertEquals(1, plan.accepted.size)
        assertEquals(1, plan.duplicates.size)
    }

    @Test
    fun batchMirrorOverlapWithinBatchBlocks() {
        val plan = CreateDedup.planBatch(
            emptyList(),
            listOf(
                candidate(
                    "https://a.example.com/f.zip",
                    mirrors = listOf("https://m1.example.com/f.zip"),
                ),
                candidate("https://m1.example.com/f.zip"),
            ),
        )
        assertEquals(1, plan.accepted.size)
        assertEquals(1, plan.duplicates.size)
    }

    // ---- the TaskRecord convenience path the UI funnels use ----

    @Test
    fun judgeTasksReadsUrlStatusAndSources() {
        val t = TaskRecord(url = "https://a.example.com/f.zip")
        t.status = TaskStatus.FAILED
        t.sources.add("https://m1.example.com/f.zip")
        assertTrue(
            CreateDedup.isDuplicate(listOf(t), "https://m1.example.com/f.zip")
        )
        assertTrue(
            CreateDedup.isDuplicate(listOf(t), "https://a.example.com/f.zip")
        )
        assertFalse(
            CreateDedup.isDuplicate(listOf(t), "https://a.example.com/other.zip")
        )
        t.status = TaskStatus.DONE
        assertFalse(
            CreateDedup.isDuplicate(listOf(t), "https://a.example.com/f.zip")
        )
    }

    @Test
    fun blankCandidateNeverDuplicates() {
        assertFalse(
            isDup(
                listOf(existing("https://a.example.com/f.zip", TaskStatus.QUEUED)),
                candidate(""),
            )
        )
    }
}

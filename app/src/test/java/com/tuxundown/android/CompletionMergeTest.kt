package com.tuxundown.android

import com.tuxundown.android.core.CompletionMerge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v7.5 (audit round 60, Step C): CompletionMerge.prune —
 * deleting a DONE record removes its entry from the merge
 * list, so a later merged completion summary never names a
 * task the user already removed. (The merge / window /
 * wording behaviour itself is pinned in QuietHoursTest.)
 */
class CompletionMergeTest {

    private fun done(name: String, taskId: String, at: Long = 0L) =
        CompletionMerge.Done(name, "content://x/$name", at, 100L, taskId)

    @Test
    fun pruneRemovesOnlyTheDeletedTasksEntry() {
        val list = listOf(
            done("a.zip", "id-a"),
            done("b.zip", "id-b"),
            done("c.zip", "id-c"),
        )
        val pruned = CompletionMerge.prune(list, setOf("id-b"))
        assertEquals(listOf("a.zip", "c.zip"), pruned.map { it.name })
    }

    @Test
    fun pruneCanEmptyTheList() {
        val list = listOf(done("a.zip", "id-a"))
        assertEquals(
            emptyList<CompletionMerge.Done>(),
            CompletionMerge.prune(list, setOf("id-a")),
        )
    }

    @Test
    fun pruneWithUnknownIdsIsANoOp() {
        val list = listOf(done("a.zip", "id-a"), done("b.zip", "id-b"))
        assertEquals(list, CompletionMerge.prune(list, setOf("id-zzz")))
        assertEquals(list, CompletionMerge.prune(list, emptySet()))
    }

    @Test
    fun pruneNeverRemovesEntriesWithoutATaskId() {
        // Entries built without an id (taskId = "") predate
        // the id; pruning them by a deleted record's id could
        // kill a different task's entry, so they stay.
        val legacy = CompletionMerge.Done("old.zip", "content://x/old.zip", 0L, 5L)
        val list = listOf(legacy, done("a.zip", "id-a"))
        assertEquals(
            listOf(legacy),
            CompletionMerge.prune(list, setOf("id-a", "")),
        )
    }
}

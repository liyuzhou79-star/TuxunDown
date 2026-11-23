package com.tuxundown.android

import com.tuxundown.android.core.SniffHistory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.7: sniff-history re-sniff rules — the target is the stored
 * URL verbatim, a successful (re-)sniff moves the entry to the
 * front under the model's existing dedup/cap rule, and a failed
 * one changes nothing (entry and order survive).
 */
class SniffHistoryTest {

    @Test
    fun resniffTargetIsTheStoredUrlVerbatim() {
        // No trimming, no case folding, no fragment/query surgery:
        // any rewrite would sniff a different page than the one
        // the history entry names.
        val entry = "https://EXAMPLE.com/Dir/Page.HTML?Token=Ab%2F1#frag"
        assertEquals(entry, SniffHistory.resniffTarget(entry))
    }

    @Test
    fun successfulResniffMovesEntryToFrontWithoutDuplicating() {
        val history = listOf(
            "https://a.example/1",
            "https://b.example/2",
            "https://c.example/3",
        )
        assertEquals(
            listOf(
                "https://c.example/3",
                "https://a.example/1",
                "https://b.example/2",
            ),
            SniffHistory.afterSuccessfulSniff(history, "https://c.example/3"),
        )
    }

    @Test
    fun successfulSniffOfNewPagePrependsAndCapsAtTen() {
        val history = (1..10).map { "https://h.example/$it" }
        val after = SniffHistory.afterSuccessfulSniff(history, "https://new.example/")
        assertEquals(SniffHistory.MAX_ENTRIES, after.size)
        assertEquals("https://new.example/", after.first())
        // The oldest entry falls off the end; nothing else moves.
        assertEquals(history.dropLast(1), after.drop(1))
    }

    @Test
    fun successfulSniffCollapsesStoredDuplicates() {
        // A stored list that somehow holds the URL twice (older
        // build, restored backup) heals on the next success.
        val history = listOf(
            "https://a.example/1",
            "https://b.example/2",
            "https://a.example/1",
        )
        assertEquals(
            listOf("https://a.example/1", "https://b.example/2"),
            SniffHistory.afterSuccessfulSniff(history, "https://a.example/1"),
        )
    }

    @Test
    fun failedResniffLeavesHistoryUntouched() {
        // Dead page / login wall / network down: the entry keeps
        // its place and the list is byte-for-byte the same, so
        // the user's way back in survives the failure.
        val history = listOf(
            "https://a.example/1",
            "https://b.example/2",
        )
        assertEquals(history, SniffHistory.afterFailedSniff(history, "https://b.example/2"))
        assertEquals(history, SniffHistory.afterFailedSniff(history, "https://gone.example/"))
        assertEquals(
            emptyList<String>(),
            SniffHistory.afterFailedSniff(emptyList(), "https://a.example/1"),
        )
    }

    @Test
    fun blankUrlNeverEntersHistory() {
        val history = listOf("https://a.example/1")
        assertEquals(history, SniffHistory.afterSuccessfulSniff(history, ""))
        assertEquals(history, SniffHistory.afterSuccessfulSniff(history, "   "))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.ClipboardPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.3: pins the clipboard-prompt frequency verdict — the
 * persisted single slot (pre-v6.3, cross-session) OR the new
 * session set (this process run). The harness mirrors
 * DownloadCenter's wiring: a mark (prompt shown / confirmed /
 * dismissed — all the same) writes BOTH layers.
 */
class ClipboardPromptTest {

    /** The two layers, shaped like DownloadCenter's. */
    private class Layers {
        var persisted: String = ""
        val session = mutableSetOf<String>()

        fun mark(url: String) {
            session.add(url)
            persisted = url
        }

        fun shouldPrompt(url: String): Boolean =
            ClipboardPrompt.shouldPrompt(url, persisted, session)
    }

    private val a = "https://ex.com/a.zip"
    private val b = "https://ex.com/b.zip"

    @Test
    fun firstSeenContentPrompts() {
        val l = Layers()
        assertTrue(l.shouldPrompt(a))
    }

    @Test
    fun promptedContentIsSuppressedAfterwards() {
        val l = Layers()
        assertTrue(l.shouldPrompt(a))
        l.mark(a) // shown and answered
        assertFalse(l.shouldPrompt(a))
    }

    @Test
    fun dismissedContentIsSuppressedAfterwards() {
        val l = Layers()
        assertTrue(l.shouldPrompt(a))
        l.mark(a) // dismissed — the mark is the same by design
        assertFalse(l.shouldPrompt(a))
    }

    @Test
    fun abaPatternDoesNotRepromptWithinTheSession() {
        // The pre-v6.3 hole: the persisted slot alone holds only
        // B after the second mark, so A prompted again. The
        // session set must hold A suppressed.
        val l = Layers()
        l.mark(a)
        l.mark(b)
        assertFalse(l.shouldPrompt(a))
        assertFalse(l.shouldPrompt(b))
    }

    @Test
    fun sessionHitSuppressesEvenWhenTheSlotMovedOn() {
        // The same verdict reached directly: slot holds B,
        // session holds A — A is suppressed by the session layer
        // alone.
        assertFalse(
            ClipboardPrompt.shouldPrompt(a, persistedPrompted = b, sessionSeen = setOf(a, b))
        )
    }

    @Test
    fun newContentStillPrompts() {
        val l = Layers()
        l.mark(a)
        assertTrue(l.shouldPrompt(b))
    }

    @Test
    fun persistedSlotHitSuppressesInAFreshSession() {
        // Cross-session behaviour must not regress: a fresh
        // process has an empty session set, and the slot from
        // the last session still suppresses its link.
        assertFalse(
            ClipboardPrompt.shouldPrompt(a, persistedPrompted = a, sessionSeen = emptySet())
        )
    }

    @Test
    fun blankNeverPrompts() {
        val l = Layers()
        assertFalse(l.shouldPrompt(""))
        assertFalse(l.shouldPrompt("   "))
    }
}

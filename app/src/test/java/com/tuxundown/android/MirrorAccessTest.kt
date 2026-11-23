package com.tuxundown.android

import com.tuxundown.android.core.MirrorAccess
import com.tuxundown.android.core.SourceCheck
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.4: the one mirror ruling — a mirror is usable when it is
 * proven the same file, or when it merely could not be confirmed
 * AND the user overrode it for this task. A proven-different
 * mirror is refused no matter what.
 */
class MirrorAccessTest {

    private val m = "https://mirror.example.com/file.zip"

    // ---- state mapping ----

    @Test
    fun stateOfMapsVerdictsAndAbsence() {
        assertEquals(
            MirrorAccess.State.CONFIRMED,
            MirrorAccess.stateOf(SourceCheck.Verdict.SAME),
        )
        assertEquals(
            MirrorAccess.State.REJECTED,
            MirrorAccess.stateOf(SourceCheck.Verdict.DIFFERENT),
        )
        assertEquals(
            MirrorAccess.State.UNCONFIRMED,
            MirrorAccess.stateOf(SourceCheck.Verdict.UNKNOWN),
        )
        assertEquals(
            MirrorAccess.State.UNCHECKED,
            MirrorAccess.stateOf(null),
        )
    }

    // ---- the full table: every state x override on/off ----

    @Test
    fun confirmedIsUsableWithOrWithoutOverride() {
        for (o in listOf(false, true)) {
            val d = MirrorAccess.decision(MirrorAccess.State.CONFIRMED, o)
            assertEquals(MirrorAccess.Decision.USABLE_CONFIRMED, d)
            assertTrue(MirrorAccess.usable(d))
        }
    }

    @Test
    fun differentIsBlockedWithOrWithoutOverride() {
        for (o in listOf(false, true)) {
            val d = MirrorAccess.decision(MirrorAccess.State.REJECTED, o)
            assertEquals(MirrorAccess.Decision.BLOCKED_DIFFERENT, d)
            assertFalse(MirrorAccess.usable(d))
        }
    }

    @Test
    fun unconfirmedNeedsTheOverride() {
        val without = MirrorAccess.decision(MirrorAccess.State.UNCONFIRMED, false)
        assertEquals(MirrorAccess.Decision.BLOCKED_UNCONFIRMED, without)
        assertFalse(MirrorAccess.usable(without))
        val with = MirrorAccess.decision(MirrorAccess.State.UNCONFIRMED, true)
        assertEquals(MirrorAccess.Decision.USABLE_OVERRIDDEN, with)
        assertTrue(MirrorAccess.usable(with))
    }

    @Test
    fun uncheckedIsBlockedWithOrWithoutOverride() {
        for (o in listOf(false, true)) {
            val d = MirrorAccess.decision(MirrorAccess.State.UNCHECKED, o)
            assertEquals(MirrorAccess.Decision.BLOCKED_UNCHECKED, d)
            assertFalse(MirrorAccess.usable(d))
        }
    }

    @Test
    fun verdictFormMatchesStateForm() {
        assertEquals(
            MirrorAccess.decision(MirrorAccess.State.UNCONFIRMED, true),
            MirrorAccess.decision(SourceCheck.Verdict.UNKNOWN, true),
        )
        assertEquals(
            MirrorAccess.Decision.BLOCKED_DIFFERENT,
            MirrorAccess.decision(SourceCheck.Verdict.DIFFERENT, true),
        )
        assertEquals(
            MirrorAccess.Decision.BLOCKED_UNCHECKED,
            MirrorAccess.decision(null, true),
        )
    }

    // ---- on a live record: override, then revoke ----

    @Test
    fun overrideThenRevokeOnRecord() {
        val t = TaskRecord(url = "https://example.com/file.zip")
        t.sources.add(m)
        t.sourceVerdicts[m] = SourceCheck.Verdict.UNKNOWN
        assertFalse(MirrorAccess.usableFor(t, m))
        t.sourceOverrides.add(m)
        assertEquals(
            MirrorAccess.Decision.USABLE_OVERRIDDEN,
            MirrorAccess.decisionFor(t, m),
        )
        assertTrue(MirrorAccess.usableFor(t, m))
        // Revoking returns it to blocked-unconfirmed.
        t.sourceOverrides.remove(m)
        assertEquals(
            MirrorAccess.Decision.BLOCKED_UNCONFIRMED,
            MirrorAccess.decisionFor(t, m),
        )
        assertFalse(MirrorAccess.usableFor(t, m))
    }

    @Test
    fun overrideNeverLiftsProvenDifferentOnRecord() {
        val t = TaskRecord(url = "https://example.com/file.zip")
        t.sources.add(m)
        t.sourceVerdicts[m] = SourceCheck.Verdict.DIFFERENT
        t.sourceOverrides.add(m)
        assertEquals(
            MirrorAccess.Decision.BLOCKED_DIFFERENT,
            MirrorAccess.decisionFor(t, m),
        )
        assertFalse(MirrorAccess.usableFor(t, m))
    }

    // ---- pruning: a removed mirror leaves no orphans ----

    @Test
    fun pruningDropsRemovedMirrorsOnly() {
        val keep = "https://keep.example.com/file.zip"
        val verdicts = mapOf(
            keep to SourceCheck.Verdict.SAME,
            m to SourceCheck.Verdict.UNKNOWN,
        )
        assertEquals(
            mapOf(keep to SourceCheck.Verdict.SAME),
            MirrorAccess.prunedVerdicts(verdicts, listOf(keep)),
        )
        assertEquals(
            setOf(keep),
            MirrorAccess.prunedOverrides(setOf(keep, m), listOf(keep)),
        )
        assertEquals(
            emptyMap<String, SourceCheck.Verdict>(),
            MirrorAccess.prunedVerdicts(verdicts, emptyList()),
        )
        assertEquals(
            emptySet<String>(),
            MirrorAccess.prunedOverrides(setOf(keep, m), emptyList()),
        )
    }

    // ---- persistence: round trip + legacy default safety ----

    @Test
    fun roundTripKeepsVerdictsAndOverrides() {
        val t = TaskRecord(url = "https://example.com/file.zip")
        t.sources.add(m)
        t.sourceVerdicts[m] = SourceCheck.Verdict.UNKNOWN
        t.sourceOverrides.add(m)
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        assertEquals(SourceCheck.Verdict.UNKNOWN, back[0].sourceVerdicts[m])
        assertEquals(setOf(m), back[0].sourceOverrides)
        assertTrue(MirrorAccess.usableFor(back[0], m))
    }

    @Test
    fun legacyRecordWithoutV54FieldsDefaultsEmpty() {
        // A pre-v5.4 record: no sourceVerdicts / sourceOverrides
        // keys at all — both must read as empty, and the mirror
        // is simply unchecked, never silently usable.
        val json = """
            [{"id":"x1","url":"https://example.com/file.zip",
              "sources":["$m"],"status":"PAUSED"}]
        """.trimIndent()
        val back = TaskStore.deserialize(json)
        assertEquals(1, back.size)
        assertTrue(back[0].sourceVerdicts.isEmpty())
        assertTrue(back[0].sourceOverrides.isEmpty())
        assertEquals(
            MirrorAccess.Decision.BLOCKED_UNCHECKED,
            MirrorAccess.decisionFor(back[0], m),
        )
    }

    @Test
    fun parseDropsOrphansAndUnknownVerdictNames() {
        val ghost = "https://ghost.example.com/file.zip"
        val json = """
            [{"id":"x2","url":"https://example.com/file.zip",
              "sources":["$m"],"status":"PAUSED",
              "sourceVerdicts":{"$m":"MAYBE","$ghost":"SAME"},
              "sourceOverrides":["$ghost"]}]
        """.trimIndent()
        val back = TaskStore.deserialize(json)
        assertEquals(1, back.size)
        // "MAYBE" is not a verdict; the ghost is not a mirror.
        assertTrue(back[0].sourceVerdicts.isEmpty())
        assertTrue(back[0].sourceOverrides.isEmpty())
    }
}

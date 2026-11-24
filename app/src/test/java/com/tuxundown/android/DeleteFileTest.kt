package com.tuxundown.android

import com.tuxundown.android.core.DeleteFile
import com.tuxundown.android.core.Reconcile
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v7.7.0 Step C2: 删除文件 — 可删判定 + 结果三态. */
class DeleteFileTest {

    private fun decide(
        status: TaskStatus = TaskStatus.DONE,
        savedUri: String = "content://x/1",
        finalizing: Boolean = false,
        verifying: Boolean = false,
        claimed: Boolean = false,
        verdict: Reconcile.Verdict? = Reconcile.Verdict.EXISTS,
    ) = DeleteFile.decide(status, savedUri, finalizing, verifying, claimed, verdict)

    @Test
    fun doneLiveFileIsAllowed() {
        val d = decide()
        assertTrue(d.allowed)
        assertNull(d.block)
    }

    @Test
    fun unknownVerdictStillAllowed() {
        // 查不到不等于不在: UNKNOWN / no verdict never block;
        // the delete attempt itself tells the truth.
        assertTrue(decide(verdict = Reconcile.Verdict.UNKNOWN).allowed)
        assertTrue(decide(verdict = null).allowed)
    }

    @Test
    fun nonDoneNeverAllowed() {
        for (s in listOf(
            TaskStatus.RUNNING, TaskStatus.PAUSED,
            TaskStatus.QUEUED, TaskStatus.FAILED,
        )) {
            val d = decide(status = s)
            assertFalse(d.allowed)
            assertEquals(DeleteFile.Block.NOT_DONE, d.block)
        }
    }

    @Test
    fun finalizingBlocksBeforeBlankUri() {
        // The finalize window IS "DONE + savedUri blank" — it
        // must report FINALIZING, not NO_FILE.
        val d = decide(savedUri = "", finalizing = true)
        assertEquals(DeleteFile.Block.FINALIZING, d.block)
    }

    @Test
    fun doneWithoutSavedUriIsNoFile() {
        assertEquals(DeleteFile.Block.NO_FILE, decide(savedUri = "").block)
        assertEquals(DeleteFile.Block.NO_FILE, decide(savedUri = "  ").block)
    }

    @Test
    fun verifyingBlocks() {
        assertEquals(DeleteFile.Block.VERIFYING, decide(verifying = true).block)
    }

    @Test
    fun claimedBlocks() {
        assertEquals(DeleteFile.Block.BUSY, decide(claimed = true).block)
    }

    @Test
    fun missingVerdictIsAlreadyGone() {
        val d = decide(verdict = Reconcile.Verdict.MISSING)
        assertFalse(d.allowed)
        assertEquals(DeleteFile.Block.ALREADY_GONE, d.block)
    }

    @Test
    fun occupancyBeatsAlreadyGone() {
        // Busy is the actionable reason; it is checked first.
        val d = decide(verifying = true, verdict = Reconcile.Verdict.MISSING)
        assertEquals(DeleteFile.Block.VERIFYING, d.block)
    }

    @Test
    fun everyBlockHasDistinctText() {
        val texts = DeleteFile.Block.entries.map { DeleteFile.blockText(it) }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() })
    }

    // ---- outcome three-state ----

    @Test
    fun alreadyGoneBeforeIsNeverReportedDeleted() {
        assertEquals(
            DeleteFile.Outcome.ALREADY_GONE,
            DeleteFile.outcome(Reconcile.Verdict.MISSING, true, Reconcile.Verdict.MISSING),
        )
        assertEquals(
            DeleteFile.Outcome.ALREADY_GONE,
            DeleteFile.outcome(Reconcile.Verdict.MISSING, false, null),
        )
    }

    @Test
    fun deletedWhenOurDeleteWorkedAndFileIsGone() {
        assertEquals(
            DeleteFile.Outcome.DELETED,
            DeleteFile.outcome(Reconcile.Verdict.EXISTS, true, Reconcile.Verdict.MISSING),
        )
    }

    @Test
    fun vanishedWithoutOurDeleteIsAlreadyGoneNotDeleted() {
        assertEquals(
            DeleteFile.Outcome.ALREADY_GONE,
            DeleteFile.outcome(Reconcile.Verdict.EXISTS, false, Reconcile.Verdict.MISSING),
        )
    }

    @Test
    fun stillExistsAfterIsFailed() {
        assertEquals(
            DeleteFile.Outcome.FAILED,
            DeleteFile.outcome(Reconcile.Verdict.EXISTS, true, Reconcile.Verdict.EXISTS),
        )
        assertEquals(
            DeleteFile.Outcome.FAILED,
            DeleteFile.outcome(Reconcile.Verdict.EXISTS, false, Reconcile.Verdict.EXISTS),
        )
    }

    @Test
    fun unconfirmableProbeFallsBackToDeleteAnswer() {
        assertEquals(
            DeleteFile.Outcome.DELETED,
            DeleteFile.outcome(Reconcile.Verdict.UNKNOWN, true, Reconcile.Verdict.UNKNOWN),
        )
        assertEquals(
            DeleteFile.Outcome.FAILED,
            DeleteFile.outcome(null, false, null),
        )
    }

    // ---- v8.7.0: system-confirmation route + verdict ----

    @Test
    fun directSuccessNeverEscalates() {
        assertEquals(
            DeleteFile.Route.DONE,
            DeleteFile.route(directDeleted = true, sdkInt = 35, requestAvailable = true),
        )
        assertEquals(
            DeleteFile.Route.DONE,
            DeleteFile.route(directDeleted = true, sdkInt = 29, requestAvailable = false),
        )
    }

    @Test
    fun failedDirectDeleteEscalatesOnlyWithRequestOnApi30Plus() {
        assertEquals(
            DeleteFile.Route.ASK_SYSTEM,
            DeleteFile.route(directDeleted = false, sdkInt = 30, requestAvailable = true),
        )
        assertEquals(
            DeleteFile.Route.ASK_SYSTEM,
            DeleteFile.route(directDeleted = false, sdkInt = 35, requestAvailable = true),
        )
        // API 29 has no createDeleteRequest: honest failure stands.
        assertEquals(
            DeleteFile.Route.GIVE_UP,
            DeleteFile.route(directDeleted = false, sdkInt = 29, requestAvailable = true),
        )
        // Request could not be built (e.g. not a MediaStore Uri).
        assertEquals(
            DeleteFile.Route.GIVE_UP,
            DeleteFile.route(directDeleted = false, sdkInt = 35, requestAvailable = false),
        )
    }

    @Test
    fun systemDialogDeclinedIsCancelledNotFailed() {
        assertEquals(
            DeleteFile.Outcome.CANCELLED,
            DeleteFile.afterSystemConfirm(false, Reconcile.Verdict.MISSING),
        )
        assertEquals(
            DeleteFile.Outcome.CANCELLED,
            DeleteFile.afterSystemConfirm(false, Reconcile.Verdict.EXISTS),
        )
        assertEquals(
            DeleteFile.Outcome.CANCELLED,
            DeleteFile.afterSystemConfirm(false, null),
        )
    }

    @Test
    fun systemDialogConfirmedFollowsTheReprobe() {
        assertEquals(
            DeleteFile.Outcome.DELETED,
            DeleteFile.afterSystemConfirm(true, Reconcile.Verdict.MISSING),
        )
        assertEquals(
            DeleteFile.Outcome.FAILED,
            DeleteFile.afterSystemConfirm(true, Reconcile.Verdict.EXISTS),
        )
        // Probe cannot tell: the system's own confirmed answer
        // decides, the same fallback as outcome().
        assertEquals(
            DeleteFile.Outcome.DELETED,
            DeleteFile.afterSystemConfirm(true, Reconcile.Verdict.UNKNOWN),
        )
        assertEquals(
            DeleteFile.Outcome.DELETED,
            DeleteFile.afterSystemConfirm(true, null),
        )
    }

    @Test
    fun cancelledTextIsDistinctAndSaysNothingMoved() {
        val cancelled = DeleteFile.outcomeText(DeleteFile.Outcome.CANCELLED, "a.bin")
        assertTrue(cancelled.contains("取消"))
        assertTrue(cancelled.contains("文件还在"))
        for (o in listOf(
            DeleteFile.Outcome.DELETED,
            DeleteFile.Outcome.ALREADY_GONE,
            DeleteFile.Outcome.FAILED,
        )) {
            assertNotEquals(cancelled, DeleteFile.outcomeText(o, "a.bin"))
        }
        // All four outcome texts stay pairwise distinct.
        val texts = DeleteFile.Outcome.entries.map {
            DeleteFile.outcomeText(it, "a.bin")
        }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun outcomeTextsAreDistinctAndHonest() {
        val deleted = DeleteFile.outcomeText(DeleteFile.Outcome.DELETED, "a.bin")
        val gone = DeleteFile.outcomeText(DeleteFile.Outcome.ALREADY_GONE, "a.bin")
        val failed = DeleteFile.outcomeText(DeleteFile.Outcome.FAILED, "a.bin")
        assertNotEquals(deleted, gone)
        assertNotEquals(deleted, failed)
        assertNotEquals(gone, failed)
        assertTrue(gone.contains("已经不在"))
        assertTrue(failed.contains("没删掉"))
        // Both non-failure outcomes state the record stays.
        assertTrue(deleted.contains("记录保留"))
        assertTrue(gone.contains("记录保留"))
    }
}

package com.tuxundown.android.core

/**
 * v7.7.0 Step C2: 「删除文件」 on a DONE task's detail area —
 * pure decision + outcome logic (the Android side lives in
 * PublicStorage.tryDeleteSavedFile / confirmDeleteSavedFile
 * and the card).
 *
 * The action deletes the FILE ONLY. The task record stays and
 * is ghost-marked afterwards (GhostSnapshot.markMissing);
 * deleting the record is the separate 删除任务 action and the
 * two are never merged.
 *
 * decide() answers "may we even try" from record facts the
 * caller reads live:
 * - status / savedUri / finalizing: Finalizing's caliber
 *   (DONE + savedUri blank = the finalize lane still owns
 *   the file — renaming / verifying / extracting it).
 * - verifying: ChecksumVerifier.isVerifying (the inFlight
 *   set) or the record's VERIFYING state.
 * - claimed: DownloadCenter.isClaimed — the engine claim is
 *   held across a whole runOne including its finalize lane.
 * - verdict: the latest ghost-probe verdict for savedUri
 *   (Reconcile three-state; only MISSING blocks as
 *   already-gone — UNKNOWN never blocks, 查不到不等于不在，
 *   the delete attempt itself then tells the truth).
 *
 * outcome() maps (probe before, delete call result, probe
 * after) to the three honest results. A file that was already
 * gone is ALREADY_GONE — never reported as deleted by us.
 */
object DeleteFile {

    enum class Block {
        NOT_DONE, NO_FILE, FINALIZING, VERIFYING, BUSY, ALREADY_GONE,
    }

    data class Decision(val allowed: Boolean, val block: Block?)

    fun decide(
        status: TaskStatus,
        savedUri: String,
        finalizing: Boolean,
        verifying: Boolean,
        claimed: Boolean,
        verdict: Reconcile.Verdict?,
    ): Decision {
        if (status != TaskStatus.DONE) return Decision(false, Block.NOT_DONE)
        if (finalizing) return Decision(false, Block.FINALIZING)
        if (savedUri.isBlank()) return Decision(false, Block.NO_FILE)
        if (verifying) return Decision(false, Block.VERIFYING)
        if (claimed) return Decision(false, Block.BUSY)
        if (verdict == Reconcile.Verdict.MISSING) {
            return Decision(false, Block.ALREADY_GONE)
        }
        return Decision(true, null)
    }

    /** Why a tap stopped, in plain words (toast text). */
    fun blockText(block: Block): String = when (block) {
        Block.NOT_DONE -> "只有下载完成的任务才能删文件"
        Block.NO_FILE -> "这条记录没有保存位置，找不到要删的文件"
        Block.FINALIZING -> "文件正在收尾（改名、校验或解压），等收尾完成后再删"
        Block.VERIFYING -> "文件正在校验，等校验结束后再删"
        Block.BUSY -> "文件正在被占用，等当前操作结束后再删"
        Block.ALREADY_GONE -> "文件已经不在了，不用再删；记录会标成「文件已不在」"
    }

    enum class Outcome { DELETED, ALREADY_GONE, FAILED, CANCELLED }

    fun outcome(
        before: Reconcile.Verdict?,
        deleteReturned: Boolean,
        after: Reconcile.Verdict?,
    ): Outcome {
        if (before == Reconcile.Verdict.MISSING) return Outcome.ALREADY_GONE
        if (after == Reconcile.Verdict.MISSING) {
            // Gone now. We deleted it only when our own delete
            // call said so; a false return means it vanished
            // some other way in between — say so honestly.
            return if (deleteReturned) Outcome.DELETED else Outcome.ALREADY_GONE
        }
        if (after == Reconcile.Verdict.EXISTS) return Outcome.FAILED
        // Probe could not confirm either way (UNKNOWN / no
        // verdict): the delete call's own answer decides.
        return if (deleteReturned) Outcome.DELETED else Outcome.FAILED
    }

    /** The three results, worded distinctly (toast text). */
    fun outcomeText(outcome: Outcome, fileName: String): String = when (outcome) {
        Outcome.DELETED ->
            "文件「$fileName」已删除（记录保留，已标成文件已不在）"
        Outcome.ALREADY_GONE ->
            "文件「$fileName」已经不在了，不用再删（记录保留，已标成文件已不在）"
        Outcome.FAILED ->
            "没删掉，系统没有允许删除；文件还在，记录也没有动"
        Outcome.CANCELLED ->
            "已取消删除，文件还在，记录也没有动"
    }

    // ---- v8.7.0: system delete confirmation route ----
    //
    // On Android 11+ a direct delete of a file in the public
    // Download folder can be refused by the system
    // (RecoverableSecurityException): the file is ours in
    // MediaStore terms only sometimes, and Flyme refuses the
    // plain delete outright. The official remedy is
    // MediaStore.createDeleteRequest — a system confirmation
    // dialog; the system deletes after the user confirms.
    // route() is the pure decision of whether a failed direct
    // delete should escalate to that dialog.

    enum class Route { DONE, ASK_SYSTEM, GIVE_UP }

    /**
     * DONE when the direct delete already worked; ASK_SYSTEM
     * when it failed, the platform has createDeleteRequest
     * (API 30+) and a request could actually be built;
     * GIVE_UP otherwise (the honest FAILED wording stands).
     */
    fun route(
        directDeleted: Boolean,
        sdkInt: Int,
        requestAvailable: Boolean,
    ): Route = when {
        directDeleted -> Route.DONE
        sdkInt >= 30 && requestAvailable -> Route.ASK_SYSTEM
        else -> Route.GIVE_UP
    }

    /**
     * The verdict after the system confirmation dialog comes
     * back. Not confirmed is its own outcome — the user said
     * no, nothing was deleted and nothing may be reported as
     * deleted or failed. Confirmed: the re-probe decides, and
     * an unconfirmable probe (UNKNOWN / none) falls back to
     * the system's own answer — the user confirmed and the
     * system performs the deletion itself, the same fallback
     * caliber as [outcome].
     */
    fun afterSystemConfirm(
        confirmed: Boolean,
        after: Reconcile.Verdict?,
    ): Outcome {
        if (!confirmed) return Outcome.CANCELLED
        return when (after) {
            Reconcile.Verdict.MISSING -> Outcome.DELETED
            Reconcile.Verdict.EXISTS -> Outcome.FAILED
            else -> Outcome.DELETED
        }
    }
}

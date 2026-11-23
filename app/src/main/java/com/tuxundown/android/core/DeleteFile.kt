package com.tuxundown.android.core

/**
 * v7.7.0 Step C2: 「删除文件」 on a DONE task's detail area —
 * pure decision + outcome logic (the Android side lives in
 * PublicStorage.deleteSavedFile and the card).
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

    enum class Outcome { DELETED, ALREADY_GONE, FAILED }

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
    }
}

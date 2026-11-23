package com.tuxundown.android.core

/**
 * v7.5 (audit round 60, Step B): the DISPLAY half of the
 * finalize window.
 *
 * DownloadService persists DONE BEFORE the finalize lane
 * renames the .part (DownloadService's hand-off: DONE at the
 * persist, submitFinalize right after, saveNow in the
 * finally); the record's savedUri lands only at the very end
 * of PublicStorage.finalizeTarget, after rename + checksum +
 * extract, and the completion notification fires after that.
 * So a DONE record whose savedUri is still blank is not
 * finished — it is being finalized (校验 / 改名 / 解压）, and
 * every surface that presents it as 已完成 with working
 * open / share / install buttons is lying: the file is not
 * reachable under its final name yet, so those taps can only
 * fail.
 *
 * This predicate is EXACTLY the record-side half of
 * [FinalizeRescue.needsRescue] — same condition family, by
 * design. needsRescue adds the proofs a RESCUE ACT needs
 * (a persisted target Uri, the entry provably exists, its
 * live name still ends in .part); a display decision needs
 * none of that, only the two record facts. Display layer
 * only: NO new TaskStatus, NO new persisted field, and the
 * engine / finalize logic itself is untouched — the moment
 * savedUri lands, every derivation here flips back to the
 * ordinary finished presentation on the surfaces' existing
 * refresh beats.
 *
 * Pure, unit-tested.
 */
object Finalizing {

    /** True while [task]'s bytes are downloaded but its
     *  finalize (rename / verify / extract) is still running:
     *  status DONE and savedUri not yet landed. Every other
     *  status, and a DONE record with a savedUri, is false. */
    fun isFinalizing(task: TaskRecord): Boolean =
        task.status == TaskStatus.DONE && task.savedUri.isBlank()

    /** How many of [tasks] are in the finalize window. */
    fun count(tasks: List<TaskRecord>): Int = tasks.count { isFinalizing(it) }

    /** Whether any of [tasks] is in the finalize window. */
    fun anyFinalizing(tasks: List<TaskRecord>): Boolean =
        tasks.any { isFinalizing(it) }
}

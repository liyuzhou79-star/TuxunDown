package com.tuxundown.android.core

/**
 * v7.4 (audit round 59, Step C): the diagnostics page's
 * 「任务状态分布」 section — a READ-ONLY snapshot of where the
 * task list stands right now, in plain words.
 *
 * Sibling of [SecurityStatus] (v7.2's 「安全现状」) and
 * [StorageHealth] (v7.3's 「存储健康」), built to the same
 * caliber: every number here comes from a RUNTIME read on the
 * device (the loaded task records in TaskStore, the live
 * EngineClaims set behind DownloadCenter, and Step B's
 * process-local [IllegalTransitions] counter). Nothing is
 * hard-coded: a figure the page cannot read is simply ABSENT,
 * never replaced by a made-up one. The screen gathers, this
 * object formats, and the absence rules are pinned by unit
 * tests. This object only READS state — it never moves a task.
 *
 * Absence rules (pinned):
 * - a null (unreadable) input drops exactly its own row: the
 *   per-status row and the park row both ride on the record
 *   snapshot, the claimed row rides on the claim count, and
 *   the illegal-transition row rides on the counter value;
 * - a negative claimed / illegal count is nonsense from a
 *   broken read and drops its row; a real 0 is shown as 0;
 * - when EVERY runtime read failed, the section is absent
 *   as a whole (an empty result), exactly like 安全现状 /
 *   存储健康.
 *
 * The illegal-transition figure is THIS PROCESS's count
 * since startup (the counter is process-local by design and
 * dies with the process), and the row says so out loud. The
 * optional last-illegal detail carries ONLY the task id and
 * the from / event / source triple the counter itself keeps
 * — never a URL or file name.
 */
object StatusDistribution {

    /** The per-record facts the counts are computed from:
     *  the record's status plus its three park flags. */
    data class RecordFact(
        val status: TaskStatus,
        val netDeferred: Boolean,
        val chargeDeferred: Boolean,
        val portalDeferred: Boolean,
    )

    /** Read the facts off one live record (read-only). */
    fun factOf(record: TaskRecord): RecordFact = RecordFact(
        status = record.status,
        netDeferred = record.netDeferred,
        chargeDeferred = record.chargeDeferred,
        portalDeferred = record.portalDeferred,
    )

    /** The runtime reads the screen managed to make; a null
     *  field means that read failed on this device. A null
     *  [lastIllegal] with a readable [illegalCount] simply
     *  means no refusal has been recorded this process. */
    data class Snapshot(
        val records: List<RecordFact>?,
        val claimedCount: Int?,
        val illegalCount: Int?,
        val lastIllegal: IllegalTransitions.LastIllegal?,
    )

    /** Assemble the section's display rows from a snapshot.
     *  An empty result means the section is absent. */
    fun lines(snapshot: Snapshot): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()

        // ---- per-status counts + park counts: both ride on
        // the one record snapshot read ----
        val records = snapshot.records
        if (records != null) {
            val statusParts = TaskStatus.values().map { status ->
                "${statusLabel(status)} ${records.count { it.status == status }} 个"
            }
            rows += "状态分布" to statusParts.joinToString("；")

            val netParked = records.count { it.netDeferred }
            val chargeParked = records.count { it.chargeDeferred }
            val portalParked = records.count { it.portalDeferred }
            rows += "停泊中" to
                "断网停泊 $netParked 个；充电停泊 $chargeParked 个；门户停泊 $portalParked 个"
        }

        // ---- engine claims currently held ----
        val claimed = snapshot.claimedCount
        if (claimed != null && claimed >= 0) {
            rows += "认领中" to "共 $claimed 个任务正被引擎认领"
        }

        // ---- Step B guard refusals, this process only ----
        val illegal = snapshot.illegalCount
        if (illegal != null && illegal >= 0) {
            var text = "本次运行（本进程内）累计 $illegal 次被状态机守卫拒绝"
            val last = snapshot.lastIllegal
            if (illegal > 0 && last != null) {
                text += "；最近一次：任务 ${last.taskId}，从 ${last.from} 经 ${last.event}（来源 ${last.source}）"
            }
            rows += "非法迁移" to text
        }

        return rows
    }

    private fun statusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.QUEUED -> "排队"
        TaskStatus.RUNNING -> "进行中"
        TaskStatus.PAUSED -> "已暂停"
        TaskStatus.DONE -> "已完成"
        TaskStatus.FAILED -> "失败"
    }
}

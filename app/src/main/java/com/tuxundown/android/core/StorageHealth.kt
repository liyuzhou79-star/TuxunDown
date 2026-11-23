package com.tuxundown.android.core

/**
 * v7.3 (audit round 58, Step C): the diagnostics page's
 * 「存储健康」 section — a READ-ONLY snapshot of how this
 * install's local data stores stand, in plain words.
 *
 * Sibling of [SecurityStatus] (v7.2's 「安全现状」) and built
 * to the same caliber: every number here comes from a
 * RUNTIME read on the device (the loaded task list, the
 * tasks.json file itself, the existing parse results behind
 * DownloadCenter's counters, and Step B's fallback traces).
 * Nothing is hard-coded: a figure the page cannot read is
 * simply ABSENT, never replaced by a made-up one. The
 * screen gathers, this object formats, and the absence
 * rules are pinned by unit tests.
 *
 * Absence rules (pinned):
 * - a null (unreadable) input drops exactly its own
 *   fragment; a row whose every fragment dropped is absent;
 * - a negative count / byte size is nonsense from a broken
 *   read and drops its fragment; a real 0 is shown as 0;
 * - a last-write time <= 0 drops only that fragment;
 * - the sub-stores that live as serialized text inside the
 *   settings prefs (host learning, day series, sniff
 *   history) have NO real last-write time to read — their
 *   rows carry counts only, never an invented timestamp;
 * - when EVERY runtime read failed, the section is absent
 *   as a whole (an empty result), exactly like 安全现状.
 *
 * The two Step B traces ride along honestly:
 * - 设置回退 lists only the NAMES of settings keys whose
 *   typed read fell back to its default in this process
 *   (never a value);
 * - 子存储丢弃 is the CROSS-STORE TOTAL of lines the
 *   loss-reporting parsers dropped in this process — the
 *   line says 合计 out loud and never dresses the total up
 *   as one store's figure.
 */
object StorageHealth {

    /** Health of the one file store, tasks.json, as the
     *  persisted recovery event reports it. */
    enum class TaskHealth {
        /** No recovery event is persisted: the last load
         *  needed no self-healing. */
        NORMAL,

        /** A recovery event exists: a past load fell back
         *  to the backup copy, merged records back, dropped
         *  broken records, or found both files bad. */
        RECOVERED,
    }

    /** The runtime reads the screen managed to make; a null
     *  field means that read failed on this device. */
    data class Snapshot(
        val taskCount: Int?,
        val taskFileBytes: Long?,
        val taskFileModifiedMs: Long?,
        val taskHealth: TaskHealth?,
        val taskRecoveryText: String?,
        val hostMemoryCount: Int?,
        val statsDaysWithData: Int?,
        val statsDoneCount: Int?,
        val statsTotalBytes: Long?,
        val sniffHistoryCount: Int?,
        val prefFallbackKeys: Set<String>?,
        val subStoreDroppedLines: Int?,
    )

    /** Assemble the section's display rows from a snapshot.
     *  An empty result means the section is absent. */
    fun lines(snapshot: Snapshot): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()

        // ---- tasks.json: the only file store ----
        val taskParts = mutableListOf<String>()
        val taskCount = snapshot.taskCount
        if (taskCount != null && taskCount >= 0) {
            taskParts += "共 $taskCount 条任务"
        }
        val taskBytes = snapshot.taskFileBytes
        if (taskBytes != null && taskBytes >= 0) {
            taskParts += "文件大小 ${Format.size(taskBytes)}"
        }
        val taskModified = snapshot.taskFileModifiedMs
        if (taskModified != null && taskModified > 0) {
            taskParts += "最近写入 ${Format.dateTime(taskModified)}"
        }
        when (snapshot.taskHealth) {
            TaskHealth.NORMAL -> taskParts += "状态正常"
            TaskHealth.RECOVERED -> {
                val text = snapshot.taskRecoveryText?.trim().orEmpty()
                taskParts += if (text.isNotEmpty()) {
                    "已自愈过：$text"
                } else {
                    "已自愈过"
                }
            }
            null -> Unit // unreadable: the fragment is absent
        }
        if (taskParts.isNotEmpty()) {
            rows += "任务存储" to taskParts.joinToString("；")
        }

        // ---- per-host learning (serialized text sub-store:
        // count only — no real last-write time exists) ----
        val hostCount = snapshot.hostMemoryCount
        if (hostCount != null && hostCount >= 0) {
            rows += "站点学习" to "共 $hostCount 条站点记录"
        }

        // ---- download statistics (counters + day series) ----
        val statsParts = mutableListOf<String>()
        val statsDays = snapshot.statsDaysWithData
        if (statsDays != null && statsDays >= 0) {
            statsParts += "最近 7 天里有下载记录 $statsDays 天"
        }
        val statsDone = snapshot.statsDoneCount
        if (statsDone != null && statsDone >= 0) {
            statsParts += "完成任务共 $statsDone 个"
        }
        val statsBytes = snapshot.statsTotalBytes
        if (statsBytes != null && statsBytes >= 0) {
            statsParts += "累计下载 ${Format.size(statsBytes)}"
        }
        if (statsParts.isNotEmpty()) {
            rows += "下载统计" to statsParts.joinToString("；")
        }

        // ---- sniff history (serialized text sub-store) ----
        val sniffCount = snapshot.sniffHistoryCount
        if (sniffCount != null && sniffCount >= 0) {
            rows += "嗅探历史" to "共 $sniffCount 条"
        }

        // ---- Step B trace: settings keys that fell back ----
        val fallbackKeys = snapshot.prefFallbackKeys
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.sorted()
            .orEmpty()
        if (fallbackKeys.isNotEmpty()) {
            rows += "设置回退" to
                "这些设置项这次没读出来、已先用默认值：${fallbackKeys.joinToString("、")}（只列名字，不含内容）"
        }

        // ---- Step B trace: sub-store lines dropped, TOTAL ----
        val dropped = snapshot.subStoreDroppedLines
        if (dropped != null && dropped > 0) {
            rows += "子存储解析" to
                "本次运行合计丢弃了 $dropped 行损坏数据（站点学习、每日统计等几个子存储的合计，不是单个存储的数字）"
        }

        return rows
    }
}

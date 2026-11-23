package com.tuxundown.android.core

/**
 * v7.7 (audit round 62, Step C): the diagnostics page's
 * 「资源」 section — three read-only runtime figures,
 * assembled here as pure logic in the same pattern as
 * [PowerStats] / [SecurityStatus]. Nothing here changes
 * any behaviour; the page reads each fact once per visit
 * and this object only decides which rows exist.
 *
 * The three facts and their pinned calibers:
 * - Current thread count: read live by the page with
 *   [Thread.activeCount]. That is the platform's own
 *   estimate of the active threads in the current thread
 *   group and its subgroups — the cheapest real read
 *   there is (no enumeration, no stack traces), and
 *   APPROXIMATE by definition: threads come and go while
 *   it counts, so the figure is "at the read instant",
 *   never an exact census. It is never a constant.
 * - Memory tier: the process's CURRENT tier, read from
 *   [DownloadCenter.memoryTier] and shown through
 *   [MemoryTiers.tierLabel] exactly as decided — this
 *   section never re-derives or re-decides the tier.
 * - Active samplers: the SAME caliber the 功耗 section's
 *   poll-source count already uses (see [PowerStats]) —
 *   one sampler per running engine by construction, read
 *   as the size of DownloadCenter's registered-engine
 *   set. No second counter is kept anywhere.
 *
 * Absence rules (same caliber as [PowerStats]): a null
 * (unreadable) input drops exactly its own row; a
 * negative figure is nonsense from a broken read and
 * drops its row; a real 0 is shown as 0; when EVERY
 * read failed, the section is absent as a whole (an
 * empty result). Wording is neutral statement only —
 * what was read, nothing inferred from it.
 */
object ResourceStatus {

    /** The runtime reads the screen managed to make; a
     *  null field means that read failed on this device. */
    data class Snapshot(
        val threadCount: Int?,
        val memoryTier: MemoryTier?,
        val activeSamplers: Int?,
    )

    /** Assemble the section's display rows from a
     *  snapshot. An empty result means the section is
     *  absent. */
    fun lines(snapshot: Snapshot): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val threads = snapshot.threadCount
        if (threads != null && threads >= 0) {
            rows += "当前线程数" to
                "本进程当前约 $threads 个（读取瞬间的近似值）"
        }
        val tier = snapshot.memoryTier
        if (tier != null) {
            rows += "内存档位" to MemoryTiers.tierLabel(tier)
        }
        val samplers = snapshot.activeSamplers
        if (samplers != null && samplers >= 0) {
            rows += "采样器活跃数" to
                "当前共 $samplers 个（按正在运行的引擎计）"
        }
        return rows
    }
}

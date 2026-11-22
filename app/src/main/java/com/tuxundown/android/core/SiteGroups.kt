package com.tuxundown.android.core

/**
 * Group the task list by site (v3.3) — the second home-list
 * view next to the flat list.
 *
 * Coexistence rules (the UI follows them exactly):
 * - Tabs filter FIRST: a group only ever contains tasks the
 *   current tab already shows (archived stays under its own
 *   tab, and so on).
 * - Sort applies WITHIN each group only: the caller hands in
 *   the already tab-filtered + sorted list, grouping keeps
 *   that order inside every group, and groups themselves are
 *   ordered by first appearance in the handed-in list.
 * - Global search (a non-blank query) replaces both views
 *   with its own three groups; site grouping does not apply
 *   there.
 * - The wide two-column layout is a flat-view feature;
 *   grouped mode renders single-column so a group header
 *   always spans the full width.
 *
 * The group header shows the site, the task count and the
 * group's aggregate progress: bytes downloaded over bytes
 * total, counting only tasks whose total size is known
 * (a task of unknown size would poison the ratio either
 * way it was guessed, so it simply does not vote).
 *
 * Pure — unit-tested.
 */
object SiteGroups {

    /** Host label for tasks whose URL has no parseable host. */
    const val UNKNOWN_HOST = "未知站点"

    data class Group(
        val host: String,
        val tasks: List<TaskRecord>,
        /** Sum of total sizes over tasks with a known size. */
        val totalBytes: Long,
        /** Sum of downloaded bytes over those same tasks. */
        val doneBytes: Long,
    ) {
        /** Aggregate progress 0..100, or null when no task in
         *  the group has a known total size. */
        val progressPct: Int?
            get() = if (totalBytes > 0) {
                (doneBytes.coerceIn(0, totalBytes) * 100 / totalBytes).toInt()
            } else {
                null
            }
    }

    fun hostOf(task: TaskRecord): String =
        HostMemory.hostOf(task.url).ifBlank { UNKNOWN_HOST }

    /** Group [tasks] by host, preserving input order both
     *  between groups (first appearance) and inside them. */
    fun group(tasks: List<TaskRecord>): List<Group> {
        val byHost = LinkedHashMap<String, MutableList<TaskRecord>>()
        for (t in tasks) {
            byHost.getOrPut(hostOf(t)) { mutableListOf() }.add(t)
        }
        return byHost.map { (host, members) ->
            var total = 0L
            var done = 0L
            for (t in members) {
                if (t.totalSize > 0) {
                    total += t.totalSize
                    done += t.downloaded.coerceIn(0, t.totalSize)
                }
            }
            Group(host, members.toList(), total, done)
        }
    }
}

package com.tuxundown.android.core

/**
 * Global search (v3.3): the home search box used to filter
 * only the task list. It now searches three things at once —
 *
 * - TASKS: file name, URL, the user's own note (v2.8) and the
 *   site (host) — across ALL tabs and including archived
 *   records, because "find it" should not depend on which
 *   tab the record happens to live under;
 * - DOWNLOADED FILES: the files behind completed task
 *   records (the same record-backed set the 已下载文件 page
 *   starts from). Files that sit in the download folder
 *   WITHOUT a task record are found by that page's own
 *   folder scan, not from here — the home screen does not
 *   rescan the folder on every keystroke;
 * - SNIFF HISTORY: the recently sniffed page URLs. History
 *   stores URLs only (no page titles were ever recorded),
 *   so matching is on the URL and its host.
 *
 * Pure matching — unit-tested. The UI renders the three
 * groups; a task hit renders the full task card (that IS
 * the location), a file hit jumps to the files page, a
 * history hit re-opens the add dialog with the URL filled
 * in (the same thing tapping a history entry there does).
 */
object GlobalSearch {

    const val MAX_TASK_HITS = 50
    const val MAX_FILE_HITS = 20

    /** One downloaded-file hit: display name + size + the
     *  task record it came from. */
    data class FileHit(val name: String, val size: Long, val taskId: String)

    data class Result(
        val tasks: List<TaskRecord>,
        val files: List<FileHit>,
        val history: List<String>,
    ) {
        val isEmpty: Boolean
            get() = tasks.isEmpty() && files.isEmpty() && history.isEmpty()
    }

    /** Does [task] match [query] (already trimmed, non-blank)? */
    fun taskMatches(task: TaskRecord, query: String): Boolean {
        if (query.isBlank()) return false
        return task.fileName.contains(query, ignoreCase = true) ||
            task.url.contains(query, ignoreCase = true) ||
            task.userNote.contains(query, ignoreCase = true) ||
            HostMemory.hostOf(task.url).contains(query, ignoreCase = true)
    }

    /** The display name a completed task's file is known by. */
    fun fileNameOf(task: TaskRecord): String =
        task.savedName.ifBlank { task.fileName }

    fun search(
        tasks: List<TaskRecord>,
        history: List<String>,
        rawQuery: String,
    ): Result {
        val q = rawQuery.trim()
        if (q.isBlank()) return Result(emptyList(), emptyList(), emptyList())
        val taskHits = tasks.filter { taskMatches(it, q) }.take(MAX_TASK_HITS)
        val fileHits = tasks
            .filter {
                it.status == TaskStatus.DONE && it.savedUri.isNotBlank() &&
                    fileNameOf(it).contains(q, ignoreCase = true)
            }
            .map {
                FileHit(
                    name = fileNameOf(it),
                    size = if (it.totalSize > 0) it.totalSize else it.downloaded,
                    taskId = it.id,
                )
            }
            .take(MAX_FILE_HITS)
        val historyHits = history.filter {
            it.contains(q, ignoreCase = true) ||
                HostMemory.hostOf(it).contains(q, ignoreCase = true)
        }
        return Result(taskHits, fileHits, historyHits)
    }
}

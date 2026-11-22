package com.tuxundown.android.core

/**
 * v3.2: batch rename of PLANNED file names for multi-selected
 * tasks — find/replace (literal text, never regex) plus an
 * optional prefix and suffix.
 *
 * Scope rules, all deliberate:
 * - Only the task's planned save name changes. A finished file
 *   on disk is never renamed by this (its name is history), and
 *   a running task's name is never changed under its workers.
 *   Eligible = QUEUED / PAUSED / FAILED: nothing is writing for
 *   those, and a part file is reached through the persisted
 *   target Uri, not through the name, so a paused task keeps its
 *   bytes and simply lands under the new name at finalize.
 * - The suffix goes BEFORE the extension ("movie.mp4" +
 *   "_hd" -> "movie_hd.mp4"); the prefix goes at the very start.
 *   Find/replace runs over the whole name first, extension
 *   included — replacing ".zip" is a legitimate use.
 * - A rule that would empty the name (or reduce it to a dot
 *   name) is refused per task: the old name stays. Names are
 *   passed through the engine's sanitizer, like every other
 *   name the app adopts.
 * - Two tasks may plan the same new name; that is not resolved
 *   here — finalize has always picked the first free
 *   "name (n).ext" instead of overwriting (audit A1).
 *
 * Pure — unit-tested (BatchRenameTest).
 */
object BatchRename {

    data class Planned(
        val id: String,
        val oldName: String,
        val newName: String,
        val eligible: Boolean,
    ) {
        val changed: Boolean get() = eligible && newName != oldName
    }

    fun eligible(status: TaskStatus): Boolean =
        status == TaskStatus.QUEUED ||
            status == TaskStatus.PAUSED ||
            status == TaskStatus.FAILED

    /** The renamed form of [name] under one rule set. */
    fun apply(
        name: String,
        find: String,
        replace: String,
        prefix: String,
        suffix: String,
    ): String {
        var out = name
        if (find.isNotEmpty()) out = out.replace(find, replace)
        if (prefix.isNotEmpty() || suffix.isNotEmpty()) {
            val dot = out.lastIndexOf('.')
            // A leading dot (".zip" as a whole name) is not an
            // extension separator.
            val hasExt = dot > 0
            val base = if (hasExt) out.substring(0, dot) else out
            val ext = if (hasExt) out.substring(dot) else ""
            out = prefix + base + suffix + ext
        }
        // Never let a rule erase a name entirely — checked
        // BEFORE the sanitizer, whose own blank fallback
        // ("download.bin") would otherwise make every erased
        // name collide on the same placeholder.
        val trimmed = out.trim()
        if (trimmed.isBlank() || trimmed == "." || trimmed == "..") return name
        return DownloadEngine.sanitizeFileName(trimmed)
    }

    /** The preview/apply plan for a selection, in list order. */
    fun plan(
        tasks: List<TaskRecord>,
        find: String,
        replace: String,
        prefix: String,
        suffix: String,
    ): List<Planned> = tasks.map { t ->
        val old = t.fileName
        Planned(
            id = t.id,
            oldName = old,
            newName = apply(old, find, replace, prefix, suffix),
            eligible = eligible(t.status),
        )
    }
}

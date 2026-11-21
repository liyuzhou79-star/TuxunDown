package com.tuxundown.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Full backup (v1.8): settings + task list in ONE JSON document.
 * This replaces the v1.4 tasks-only export as the single backup
 * entry point — and old v1.4 files still import (see [parse]).
 *
 * The download DIRECTORY is deliberately NOT in the backup: a SAF
 * tree Uri names a folder on one specific device and its access
 * permission does not transfer to another phone.
 *
 * Pure org.json logic, unit-tested; which settings exist is the
 * caller's business (DownloadCenter snapshots/applies them).
 */
object Backup {

    const val FORMAT = "tuxundown-backup"
    const val FORMAT_VERSION = 1

    data class Parsed(
        /** Task list in TaskStore's own array format, or null. */
        val tasksJson: String?,
        /** Settings object to apply, or null when the file has none. */
        val settings: JSONObject?,
    )

    /** Wrap a TaskStore array string + a settings object into one backup text. */
    fun build(tasksJson: String, settings: JSONObject, exportedAtMs: Long): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", FORMAT_VERSION)
        root.put("exportedAt", exportedAtMs)
        root.put("settings", settings)
        root.put("tasks", JSONArray(tasksJson))
        return root.toString()
    }

    /**
     * Read a backup text. Two shapes are accepted:
     *  - the v1.8 object format produced by [build];
     *  - a bare JSON array — a v1.4 tasks-only export (no settings).
     * Anything else yields Parsed(null, null), never a crash.
     */
    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            return if (runCatching { JSONArray(trimmed) }.isSuccess) {
                Parsed(trimmed, null)
            } else {
                Parsed(null, null)
            }
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return Parsed(null, null)
        return Parsed(
            root.optJSONArray("tasks")?.toString(),
            root.optJSONObject("settings"),
        )
    }
}

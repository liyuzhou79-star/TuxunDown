package com.tuxundown.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Full backup (v1.8): settings + task list in ONE JSON document.
 * This replaces the v1.4 tasks-only export as the single backup
 * entry point — and old v1.4 files still import (see [parse]).
 *
 * v3.6 adds the MIGRATION PACK (format version 2): the v1.8
 * sections plus two more — the download statistics and the
 * per-host learning data (best thread counts / rate-limit
 * ceilings) — so moving to a new phone carries EVERYTHING in
 * one file. Sniff history was already part of the settings
 * section since v1.8 and rides along in both shapes.
 *
 * Sensitive data stays out of BOTH shapes, by the same rules as
 * always: task Cookie / Authorization / Proxy-Authorization
 * headers are stripped by the caller's export serializer
 * (TaskStore.exportJson), and the settings snapshot never
 * contains the proxy password (see DownloadCenter). The two new
 * sections hold no credentials at all: statistics are byte
 * counts and day keys; host learning is host names plus thread
 * numbers.
 *
 * The download DIRECTORY is deliberately NOT in either shape:
 * a SAF tree Uri names a folder on one specific device and its
 * access permission does not transfer to another phone.
 *
 * Pure org.json logic, unit-tested; which settings/statistics
 * exist is the caller's business (DownloadCenter
 * snapshots/applies them).
 */
object Backup {

    const val FORMAT = "tuxundown-backup"

    /** v1.8 shape: settings + tasks. */
    const val FORMAT_VERSION = 1

    /** v3.6 migration pack: the v1 sections + stats + hostMemory. */
    const val MIGRATION_VERSION = 2

    data class Parsed(
        /** Task list in TaskStore's own array format, or null. */
        val tasksJson: String?,
        /** Settings object to apply, or null when the file has none. */
        val settings: JSONObject?,
        /** v3.6: statistics object to apply, or null (v1 files,
         *  legacy arrays — the importer then leaves the current
         *  statistics untouched). */
        val stats: JSONObject? = null,
        /** v3.6: per-host learning data in HostMemory's serialized
         *  form, or null when the file has none. */
        val hostMemory: String? = null,
        /** Shape marker: 0 = bare v1.4 task array, 1 = v1.8
         *  backup, 2 = v3.6 migration pack. */
        val formatVersion: Int = 0,
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
     * v3.6: the full migration pack — everything [build] carries,
     * plus the statistics object and the serialized per-host
     * learning data. Either extra section may be null/blank (it
     * is then simply absent, and importing leaves that part of
     * the current device state untouched).
     */
    fun buildMigration(
        tasksJson: String,
        settings: JSONObject,
        stats: JSONObject?,
        hostMemory: String?,
        exportedAtMs: Long,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", MIGRATION_VERSION)
        root.put("exportedAt", exportedAtMs)
        root.put("settings", settings)
        root.put("tasks", JSONArray(tasksJson))
        if (stats != null) root.put("stats", stats)
        if (!hostMemory.isNullOrBlank()) root.put("hostMemory", hostMemory)
        return root.toString()
    }

    /**
     * Read a backup text. Three shapes are accepted:
     *  - the v3.6 migration pack produced by [buildMigration];
     *  - the v1.8 object format produced by [build] (no stats /
     *    hostMemory sections — those parse as null);
     *  - a bare JSON array — a v1.4 tasks-only export (no settings).
     * Anything else yields an all-null Parsed, never a crash.
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
            tasksJson = root.optJSONArray("tasks")?.toString(),
            settings = root.optJSONObject("settings"),
            stats = root.optJSONObject("stats"),
            hostMemory = root.optString("hostMemory").ifBlank { null },
            formatVersion = root.optInt("version", FORMAT_VERSION),
        )
    }
}

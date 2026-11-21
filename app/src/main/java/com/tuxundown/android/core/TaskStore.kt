package com.tuxundown.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class TaskStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

class TaskRecord(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var fileName: String = "",
    var headers: MutableMap<String, String> = mutableMapOf(),
    var totalSize: Long = -1,
    var chunkSize: Long = 0,
    var chunkDone: BooleanArray = BooleanArray(0),
    var singleThreadOnly: Boolean = false,
    var threads: Int = 4,
    var status: TaskStatus = TaskStatus.QUEUED,
    /** Plain-language note shown in the UI (e.g. why we fell back to 1 thread). */
    var note: String = "",
    var error: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    /** Where the finished file was exported to (content Uri string), "" if not yet. */
    var savedUri: String = "",
    /** Display name of the exported file (real file name, not the internal id). */
    var savedName: String = "",
    /**
     * Uri of the in-progress ".part" file in the PUBLIC download
     * folder (v1.2: downloads are written there directly, never
     * staged in the app's private directory).
     */
    var targetUri: String = "",
    /** Server validators captured at probe time (v1.3, audit A4): if
     *  they change before a resume, the old partial bytes are stale
     *  and the chunk state is reset instead of silently mixing two
     *  different files. */
    var etag: String = "",
    var lastModified: String = "",
    // Live-only fields (not persisted):
    @Transient var downloaded: Long = 0,
    @Transient var speedBps: Long = 0,
    @Transient var peakBps: Long = 0,
    @Transient val speedHistory: MutableList<Long> = mutableListOf(),
) {
    val progress: Float
        get() = if (totalSize > 0) (downloaded.toFloat() / totalSize).coerceIn(0f, 1f) else 0f

    val etaSeconds: Long
        get() = if (totalSize > 0 && speedBps > 0 && downloaded < totalSize)
            (totalSize - downloaded) / speedBps else -1
}

/**
 * JSON-file persistence for tasks. The task metadata (incl. chunk
 * bitmaps) lives in the app's data area; the downloaded BYTES live
 * in the public Download folder as ".part" files (see PublicStorage).
 * [fileOf] is only the legacy private scratch path from <= v1.1,
 * kept so old partials can be migrated and cleaned up.
 */
class TaskStore(context: Context) {
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val metaFile = File(context.filesDir, "tasks.json")
    val tasks: MutableList<TaskRecord> = mutableListOf()

    fun fileOf(task: TaskRecord): File = File(dir, task.id + ".bin")
    fun finalName(task: TaskRecord): String = task.fileName.ifBlank { "download" }

    @Synchronized
    fun load() {
        tasks.clear()
        // v1.3 (audit A11): if tasks.json is corrupt (crash mid-save
        // under the old direct-write code), fall back to the .bak copy
        // instead of silently losing every task.
        val text = listOf(metaFile, File(metaFile.path + ".bak"))
            .firstOrNull { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?: return
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = TaskRecord(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    fileName = o.optString("fileName"),
                    totalSize = o.optLong("totalSize", -1),
                    chunkSize = o.optLong("chunkSize", 0),
                    singleThreadOnly = o.optBoolean("singleThreadOnly"),
                    threads = o.optInt("threads", 4),
                    status = runCatching { TaskStatus.valueOf(o.optString("status")) }
                        .getOrDefault(TaskStatus.PAUSED),
                    note = o.optString("note"),
                    error = o.optString("error"),
                    createdAt = o.optLong("createdAt", 0),
                    savedUri = o.optString("savedUri"),
                    savedName = o.optString("savedName"),
                    targetUri = o.optString("targetUri"),
                    etag = o.optString("etag"),
                    lastModified = o.optString("lastModified"),
                )
                val h = o.optJSONObject("headers")
                if (h != null) {
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        t.headers[k] = h.getString(k)
                    }
                }
                if (t.chunkSize > 0 && t.totalSize > 0) {
                    val count = ChunkPlanner.chunkCount(t.totalSize, t.chunkSize)
                    t.chunkDone = ChunkPlanner.decodeBitset(o.optString("chunkDone"), count)
                    t.downloaded = ChunkPlanner.downloadedOf(t.chunkDone, t.totalSize, t.chunkSize)
                }
                if (t.status == TaskStatus.RUNNING) t.status = TaskStatus.PAUSED
                tasks.add(t)
            }
        }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        for (t in tasks) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("url", t.url)
            o.put("fileName", t.fileName)
            o.put("totalSize", t.totalSize)
            o.put("chunkSize", t.chunkSize)
            o.put("chunkDone", ChunkPlanner.encodeBitset(t.chunkDone))
            o.put("singleThreadOnly", t.singleThreadOnly)
            o.put("threads", t.threads)
            o.put("status", t.status.name)
            o.put("note", t.note)
            o.put("error", t.error)
            o.put("createdAt", t.createdAt)
            o.put("savedUri", t.savedUri)
            o.put("savedName", t.savedName)
            o.put("targetUri", t.targetUri)
            o.put("etag", t.etag)
            o.put("lastModified", t.lastModified)
            val h = JSONObject()
            for ((k, v) in t.headers) h.put(k, v)
            o.put("headers", h)
            arr.put(o)
        }
        // v1.3 (audit A11): atomic save — write a temp file, keep the
        // previous good file as .bak, then rename over. A crash or
        // power loss mid-save can no longer corrupt the only copy.
        val tmp = File(metaFile.path + ".tmp")
        tmp.writeText(arr.toString())
        if (metaFile.exists()) {
            runCatching { metaFile.copyTo(File(metaFile.path + ".bak"), overwrite = true) }
        }
        if (!tmp.renameTo(metaFile)) {
            // renameTo can fail across filesystems; same dir, so this
            // is only a paranoia fallback.
            tmp.copyTo(metaFile, overwrite = true)
            tmp.delete()
        }
    }

    fun add(t: TaskRecord): TaskRecord {
        tasks.add(0, t)
        save()
        return t
    }

    fun remove(t: TaskRecord) {
        tasks.remove(t)
        fileOf(t).delete()
        save()
    }

    fun byId(id: String): TaskRecord? = tasks.firstOrNull { it.id == id }
}

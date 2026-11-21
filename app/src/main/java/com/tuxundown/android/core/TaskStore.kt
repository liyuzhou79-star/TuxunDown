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
    // ---- v1.4 additions ----
    /** Scheduled start time (epoch ms); 0 = start immediately. */
    var scheduledAt: Long = 0,
    /** First time the engine actually started transferring (epoch ms). */
    var startedAt: Long = 0,
    /** When the task finished (epoch ms), 0 while unfinished. */
    var finishedAt: Long = 0,
    /** Accumulated transfer time across runs, for the average speed. */
    var elapsedMs: Long = 0,
    /** Chunk-level retry attempts the engine had to make. */
    var retryCount: Int = 0,
    /** Whole-task automatic retries already spent (reset on manual resume). */
    var autoRetriesUsed: Int = 0,
    /** The user explicitly accepted downloading this task on mobile data. */
    var mobileConfirmed: Boolean = false,
    // ---- v1.6 additions ----
    /** Extra mirror URLs of the SAME file (multi-source download).
     *  Validated against the primary before use (see SourceCheck). */
    var sources: MutableList<String> = mutableListOf(),
    /** Bandwidth-budget weight: 0 = normal, 1 = boosted （插队）. */
    var priority: Int = 0,
    /** HLS (m3u8) stream task: segments merge into one .ts file. */
    var isHls: Boolean = false,
    /** HLS: segment count of the playlist the state below belongs to. */
    var hlsTotalSegments: Int = 0,
    /**
     * HLS: segments already written to the target file. Writes are
     * strictly in playlist order, so the done set is always a prefix.
     */
    var hlsDone: BooleanArray = BooleanArray(0),
    // Live-only fields (speedHistory IS persisted since v1.4 so a
    // finished task keeps its curve; the rest stay transient):
    @Transient var downloaded: Long = 0,
    /** v1.5: parked by the service after a network-outage failure;
     *  the queue skips it until connectivity returns (see NetRetry). */
    @Transient var netDeferred: Boolean = false,
    @Transient var speedBps: Long = 0,
    @Transient var peakBps: Long = 0,
    @Transient val speedHistory: MutableList<Long> = mutableListOf(),
    /**
     * HLS: byte size of each segment whose size is known (-1 =
     * unknown), aligned with the playlist order. Persisted as CSV.
     */
    @Transient val hlsSizes: MutableList<Long> = mutableListOf(),
) {
    val progress: Float
        get() = if (isHls && hlsTotalSegments > 0) {
            // HLS totals are unknown until the last segment lands;
            // the honest progress is the merged-segment fraction.
            (hlsDone.count { it }.toFloat() / hlsTotalSegments).coerceIn(0f, 1f)
        } else if (totalSize > 0) {
            (downloaded.toFloat() / totalSize).coerceIn(0f, 1f)
        } else 0f

    val etaSeconds: Long
        get() = if (totalSize > 0 && speedBps > 0 && downloaded < totalSize)
            (totalSize - downloaded) / speedBps else -1

    /** Average transfer speed over the accumulated running time. */
    val avgBps: Long
        get() = if (elapsedMs > 0 && downloaded > 0) downloaded * 1000 / elapsedMs else 0
}

/**
 * JSON-file persistence for tasks. The task metadata (incl. chunk
 * bitmaps) lives in the app's data area; the downloaded BYTES live
 * in the public Download folder as ".part" files (see PublicStorage).
 * [fileOf] is only the legacy private scratch path from <= v1.1,
 * kept so old partials can be migrated and cleaned up.
 *
 * The (de)serialization lives in the companion as pure functions so
 * the settings-page import/export (v1.4) reuses exactly the same
 * format as the on-disk store — and so it is unit-testable on the JVM.
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
        // v1.3 (audit A11): if tasks.json is corrupt, fall back to
        // the .bak copy instead of silently losing every task.
        // v1.4: a file that EXISTS but does not parse as a JSON array
        // also falls through to .bak (v1.3 only fell back when the
        // main file was missing/unreadable); a valid "[]" is a
        // legitimately empty list and is accepted as-is.
        for (f in listOf(metaFile, File(metaFile.path + ".bak"))) {
            if (!f.exists()) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (runCatching { JSONArray(text) }.isFailure) continue
            tasks.addAll(deserialize(text))
            return
        }
    }

    @Synchronized
    fun save() {
        val text = serialize(tasks)
        // v1.3 (audit A11): atomic save — write a temp file, keep the
        // previous good file as .bak, then rename over. A crash or
        // power loss mid-save can no longer corrupt the only copy.
        val tmp = File(metaFile.path + ".tmp")
        tmp.writeText(text)
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

    /** v1.4: export the whole task list as JSON text (no file bytes). */
    fun exportJson(): String = serialize(tasks)

    /**
     * v1.4: import tasks from an exported JSON text. Records whose id
     * already exists, or whose URL is already an active task, are
     * skipped. Returns how many were actually added.
     */
    @Synchronized
    fun importJson(text: String): Int {
        val incoming = deserialize(text)
        var added = 0
        for (t in incoming) {
            if (tasks.any { it.id == t.id }) continue
            if (t.status != TaskStatus.DONE && tasks.any {
                    it.url == t.url && it.status != TaskStatus.DONE && it.status != TaskStatus.FAILED
                }
            ) continue
            // Imported unfinished tasks must not pretend to be running.
            if (t.status == TaskStatus.QUEUED) t.status = TaskStatus.PAUSED
            tasks.add(t)
            added++
        }
        if (added > 0) save()
        return added
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

    companion object {
        /** Pure serialization — same format on disk and for export. */
        fun serialize(list: List<TaskRecord>): String {
            val arr = JSONArray()
            for (t in list) {
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
                o.put("scheduledAt", t.scheduledAt)
                o.put("startedAt", t.startedAt)
                o.put("finishedAt", t.finishedAt)
                o.put("elapsedMs", t.elapsedMs)
                o.put("retryCount", t.retryCount)
                o.put("autoRetriesUsed", t.autoRetriesUsed)
                o.put("mobileConfirmed", t.mobileConfirmed)
                o.put("sources", JSONArray(t.sources))
                o.put("priority", t.priority)
                o.put("isHls", t.isHls)
                o.put("hlsTotalSegments", t.hlsTotalSegments)
                o.put("hlsDone", ChunkPlanner.encodeBitset(t.hlsDone))
                synchronized(t.hlsSizes) {
                    o.put("hlsSizesCsv", t.hlsSizes.joinToString(","))
                }
                o.put("peakBps", t.peakBps)
                val hist = JSONArray()
                synchronized(t.speedHistory) { for (v in t.speedHistory) hist.put(v) }
                o.put("speedHistory", hist)
                val h = JSONObject()
                for ((k, v) in t.headers) h.put(k, v)
                o.put("headers", h)
                arr.put(o)
            }
            return arr.toString()
        }

        /** Pure parsing; a corrupt text yields an empty list, never a crash. */
        fun deserialize(text: String): MutableList<TaskRecord> {
            val out = mutableListOf<TaskRecord>()
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
                        scheduledAt = o.optLong("scheduledAt", 0),
                        startedAt = o.optLong("startedAt", 0),
                        finishedAt = o.optLong("finishedAt", 0),
                        elapsedMs = o.optLong("elapsedMs", 0),
                        retryCount = o.optInt("retryCount", 0),
                        autoRetriesUsed = o.optInt("autoRetriesUsed", 0),
                        mobileConfirmed = o.optBoolean("mobileConfirmed", false),
                        priority = o.optInt("priority", 0),
                        isHls = o.optBoolean("isHls", false),
                        hlsTotalSegments = o.optInt("hlsTotalSegments", 0),
                    )
                    val srcArr = o.optJSONArray("sources")
                    if (srcArr != null) {
                        for (j in 0 until srcArr.length()) {
                            val s = srcArr.optString(j)
                            if (s.isNotBlank() && s != t.url && s !in t.sources) {
                                t.sources.add(s)
                            }
                        }
                    }
                    if (t.hlsTotalSegments > 0) {
                        t.hlsDone = ChunkPlanner.decodeBitset(
                            o.optString("hlsDone"), t.hlsTotalSegments
                        )
                        val csv = o.optString("hlsSizesCsv")
                        if (csv.isNotBlank()) {
                            synchronized(t.hlsSizes) {
                                csv.split(',').forEach {
                                    t.hlsSizes.add(it.trim().toLongOrNull() ?: -1)
                                }
                            }
                        }
                        // Downloaded bytes = the sizes of the written
                        // (prefix) segments whose size was recorded.
                        var sum = 0L
                        for (j in t.hlsDone.indices) {
                            if (t.hlsDone[j]) {
                                val s = t.hlsSizes.getOrElse(j) { -1 }
                                if (s > 0) sum += s
                            }
                        }
                        t.downloaded = sum
                    }
                    t.peakBps = o.optLong("peakBps", 0)
                    val h = o.optJSONObject("headers")
                    if (h != null) {
                        val keys = h.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            t.headers[k] = h.getString(k)
                        }
                    }
                    val hist = o.optJSONArray("speedHistory")
                    if (hist != null) {
                        synchronized(t.speedHistory) {
                            for (j in 0 until hist.length()) t.speedHistory.add(hist.optLong(j, 0))
                            while (t.speedHistory.size > 90) t.speedHistory.removeAt(0)
                        }
                    }
                    if (t.chunkSize > 0 && t.totalSize > 0) {
                        val count = ChunkPlanner.chunkCount(t.totalSize, t.chunkSize)
                        t.chunkDone = ChunkPlanner.decodeBitset(o.optString("chunkDone"), count)
                        t.downloaded = ChunkPlanner.downloadedOf(t.chunkDone, t.totalSize, t.chunkSize)
                    }
                    if (t.status == TaskStatus.RUNNING) t.status = TaskStatus.PAUSED
                    out.add(t)
                }
            }
            return out
        }
    }
}

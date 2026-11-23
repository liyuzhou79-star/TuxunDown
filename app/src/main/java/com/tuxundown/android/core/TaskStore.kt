package com.tuxundown.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class TaskStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/**
 * v3.5: one live connection of a running task, as published
 * by the engine's 1 s sampler (see DownloadEngine) for the
 * details view's connection list and the chunk map's ACTIVE
 * cells. [chunkIndex] is the chunk this connection is
 * working on, or -1 for a whole-file stream (single-stream
 * / FTP — one connection, no chunks). [downloaded] and
 * [total] are THIS connection's part of the file; when the
 * endgame splits a chunk, the new part re-bases both.
 */
data class ConnInfo(
    val chunkIndex: Int,
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
)

class TaskRecord(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var fileName: String = "",
    var headers: MutableMap<String, String> = mutableMapOf(),
    var totalSize: Long = -1,
    var chunkSize: Long = 0,
    /** v4.2 two-zone chunk grid (see ChunkPlanner.ChunkGrid):
     *  the first [fineChunkCount] chunks are [fineChunkSize] each,
     *  the rest are [chunkSize] each. 0 / 0 = the pre-v4.2
     *  single-granularity grid; records and backups written before
     *  v4.2 parse as 0 and resume on their original uniform grid. */
    var fineChunkSize: Long = 0,
    var fineChunkCount: Int = 0,
    var chunkDone: BooleanArray = BooleanArray(0),
    var singleThreadOnly: Boolean = false,
    var threads: Int = 4,
    /** v2.1 audit (C5): written by engine/service threads, read by
     *  the UI thread and the drain loop — volatile so a pause or a
     *  completion written on one thread is the value every other
     *  thread acts on, not a stale cached copy. */
    @Volatile var status: TaskStatus = TaskStatus.QUEUED,
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
    // ---- v5.4 additions ----
    /** The per-mirror verdict of the engine's LAST validation
     *  pass (see DownloadEngine.validateSources), keyed by mirror
     *  URL. This is a RECORD of a check that already happened —
     *  nothing re-probes to fill it. A mirror with no entry has
     *  simply never been checked. Persisted like [sourceBytes]
     *  (a JSON object); records written before v5.4 parse as
     *  empty = nothing checked yet. Entries for mirrors no
     *  longer in [sources] are dropped at parse and at save of
     *  the mirror list (see MirrorAccess.prunedVerdicts). */
    val sourceVerdicts: MutableMap<String, SourceCheck.Verdict> = mutableMapOf(),
    /** Mirrors the user manually chose to use anyway although
     *  validation could not confirm them (verdict UNKNOWN) —
     *  a TASK-level decision: persisted on this record only,
     *  revocable from the mirror dialog. It can never lift a
     *  DIFFERENT verdict (proven another file — see
     *  MirrorAccess). Records written before v5.4 parse as an
     *  empty set = nothing overridden. */
    val sourceOverrides: MutableSet<String> = mutableSetOf(),
    /** Priority tier (see TaskPriority): 1 = 高 (this is also
     *  exactly what 插队 has written since v1.6 — legacy jumped
     *  tasks ARE high tier), 0 = 中 (the default), -1 = 低 (v3.1).
     *  Drives both queue order and the bandwidth-budget split. */
    var priority: Int = 0,
    /** HLS (m3u8) stream task: segments merge into one .ts file. */
    var isHls: Boolean = false,
    /** HLS: segment count of the playlist the state below belongs to. */
    var hlsTotalSegments: Int = 0,
    /** v2.1 audit (C6): fingerprint of the segment URL list the HLS
     *  state belongs to (see Hls.fingerprint). The count alone does
     *  not identify a playlist — same-count content changes must
     *  also invalidate the written prefix. "" = unknown (state saved
     *  before v2.1; adopted on the next run, not judged). */
    var hlsFingerprint: String = "",
    // ---- v1.7 additions ----
    /** Expected SHA-256 (hex) the finished file must match; "" = none. */
    var expectedSha256: String = "",
    /** Expected MD5 (hex); used only when no SHA-256 is set. */
    var expectedMd5: String = "",
    /** Checksum verdict: see Checksum.STATE_* (0 none, 1 verifying,
     *  2 matched 对上， 3 mismatched 对不上）. */
    var checksumState: Int = 0,
    // ---- v5.8 additions ----
    /** True when the expected hash above came from a website
     *  sidecar file (see SidecarChecksum), not from the user's
     *  hand in the hash dialog — the card says which, and a
     *  hand edit clears it. Records written before v5.8 parse
     *  as false = no website mark. */
    var checksumFromSidecar: Boolean = false,
    // ---- v4.5 additions (audit round 30) ----
    /** The STRUCTURED failure verdict, recorded by the engine
     *  at the failure site where the raw exception / HTTP code
     *  still exists (see FailureClassifier). Persisted on
     *  purpose — unlike the transient netOutage/portalHit
     *  flags: FailureAdvice renders for FAILED records after
     *  a process restart, when only serialized fields
     *  survive. Records written before v4.5 parse as UNKNOWN
     *  and consumers fall back to the legacy text matching
     *  for those (see FailureClassifier.verdictFor). Reset
     *  by the engine at every run start. */
    var failureKind: FailureKind = FailureKind.UNKNOWN,
    /** The raw HTTP status code of the failure, 0 = none /
     *  not an HTTP failure. Diagnostic value; the kind above
     *  is what consumers judge by. */
    var failureHttpCode: Int = 0,
    // ---- v4.8 additions ----
    /** When the user last jumped this task to the front of the
     *  queue (插队, epoch ms); 0 = never jumped. Persisted so a
     *  jump survives a restart until the task actually starts:
     *  QueueOrder gives jumped tasks first pick inside their
     *  tier, and DownloadEngine.run() clears it at start (the
     *  jump is spent once the task really starts). Records
     *  written before v4.8 parse as 0 = never jumped. */
    var jumpedAt: Long = 0,
    // ---- v2.1 additions ----
    /** Per-task speed cap in KB/s (0 = no cap of its own). Composes
     *  with the global budget via RateCap — the smaller limit wins.
     *  Read live by the engine's limiter on every tick. */
    var taskLimitKBps: Int = 0,
    // ---- v2.4 additions ----
    /** Auto-archived completed record: hidden from the normal
     *  tabs, shown under the 已归档 filter. The downloaded FILE is
     *  never touched by archiving — only this record's visibility
     *  changes, and it can be restored at any time. Set by
     *  ArchivePolicy's sweep, never by hand in the UI. */
    var archived: Boolean = false,
    // ---- v2.8 additions ----
    /** The USER's own note for this task ("给娃下的课件" style),
     *  edited from the card's details. Deliberately separate
     *  from [note], which is the SYSTEM's plain-language status
     *  line written by the engine — neither may clobber the
     *  other. Travels with the record (disk + backup export use
     *  the one serializer) and is searchable from the home list.
     *  Sanitized by LinkSchemes.sanitizeUserNote at write time. */
    var userNote: String = "",
    // ---- v1.8 additions ----
    /** Daily recurrence: when this task reaches a terminal state,
     *  the service re-queues it for the same wall-clock time next
     *  day (scheduledAt carries the NEXT slot). */
    var scheduleDaily: Boolean = false,
    /**
     * HLS: segments already written to the target file. Writes are
     * strictly in playlist order, so the done set is always a prefix.
     */
    var hlsDone: BooleanArray = BooleanArray(0),
    // Live-only fields (speedHistory IS persisted since v1.4 so a
    // finished task keeps its curve; the rest stay transient):
    @Transient @Volatile var downloaded: Long = 0,
    /** v2.6: set by deserialize when the PERSISTED status was
     *  RUNNING — i.e. this task was mid-transfer when the process
     *  died or the phone rebooted, and load() parked it as PAUSED
     *  by the standing rule. It is the one marker that separates
     *  "the system stopped this" from "the user paused this"
     *  (see Recovery). Transient: the marker only has meaning in
     *  the process that just loaded the store, and the next save
     *  persists the PAUSED status, retiring it. */
    @Transient var wasRunning: Boolean = false,
    /** v1.5: parked by the service after a network-outage failure;
     *  the queue skips it until connectivity returns (see NetRetry). */
    @Transient var netDeferred: Boolean = false,
    /** v3.9: parked by the service's charge gate (see
     *  ChargeGuard) — the 充电时才下载 switch is on and power
     *  is disconnected. The service's idle loop keeps polling
     *  the battery state and the task starts on its own when
     *  power returns. Transient like netDeferred: the battery
     *  state is re-read live, never remembered. */
    @Transient var chargeDeferred: Boolean = false,
    /** v4.4 (audit round 29): the RAW-failure verdict for the
     *  outage deferral — true when this run's failure was a
     *  connectivity loss, judged by NetRetry.isNetworkError on
     *  the original Throwable at the engine's failure sites.
     *  It exists because task.error has carried only
     *  Errors.human's plain Chinese since v2.2, which the old
     *  string classifier could never match (the deferral was
     *  silently dead for the HTTP paths). Transient like
     *  netDeferred: reset at every run start and by enqueue,
     *  never serialized. */
    @Transient @Volatile var netOutage: Boolean = false,
    /** v4.4 (audit round 29): the last run was stopped by an
     *  HTML page where file bytes were expected (see
     *  PortalDetect) — a captive portal until proven
     *  otherwise. The service maps this to a park while the
     *  network is unvalidated. Transient, same rules as
     *  [netOutage]. */
    @Transient @Volatile var portalHit: Boolean = false,
    /** v4.4 (audit round 29): parked by the service after a
     *  portal hit; the queue skips the task until the network
     *  reports NET_CAPABILITY_VALIDATED (the login actually
     *  completed) or the user acts. Transient like
     *  netDeferred — a restart simply re-runs the task, whose
     *  engine re-detects the portal if it is still there. */
    @Transient var portalDeferred: Boolean = false,
    /** v2.8: queue pre-check (see LinkPrecheck) — the service's
     *  background HEAD check found this QUEUED task's link
     *  already dead (404/410). A warning on the card, never a
     *  verdict: the task still starts on its turn and the
     *  engine's real probe decides. Transient on purpose: the
     *  check is cheap and re-runs after a restart. */
    @Transient var precheckDead: Boolean = false,
    /** v2.8: when the pre-check last looked (its cache stamp). */
    @Transient var precheckAtMs: Long = 0,
    @Transient var speedBps: Long = 0,
    @Transient var peakBps: Long = 0,
    @Transient val speedHistory: MutableList<Long> = mutableListOf(),
    /** v3.5: live per-connection snapshot, republished by
     *  the engine's sampler every second while this task
     *  runs and cleared in run()'s finally when the run
     *  ends. Transient on purpose: a snapshot left over
     *  from a dead process would be a lie. */
    @Transient @Volatile var connInfos: List<ConnInfo> = emptyList(),
    /** v3.9: true while the global connection cap is holding
     *  this task below the connection count it would open on
     *  its own (see ConnectionBudget). Set by the segmented
     *  engine's sampler on its scaler tick, cleared in run()'s
     *  finally next to connInfos. Transient for the same
     *  reason: it describes a live contention, not the task. */
    @Transient @Volatile var connCapHeld: Boolean = false,
    /** v4.5: live checksum progress percent (0..100) while a
     *  verification of this task streams its finished file;
     *  -1 = no live figure (not verifying, or the file's size
     *  is unknown so no honest percent exists — the card then
     *  shows a bare 校验中）. Written ONLY by the one running
     *  ChecksumVerifier.verify for this task (its in-flight
     *  guard makes that singular) and reset to -1 in that
     *  verify's finally for EVERY outcome — matched,
     *  mismatched, cancelled, unreadable — so a stale percent
     *  can never outlive its run. Transient like connInfos:
     *  it describes a live pass, not the task. */
    @Transient @Volatile var checksumProgressPct: Int = -1,
    /**
     * HLS: byte size of each segment whose size is known (-1 =
     * unknown), aligned with the playlist order. Persisted as CSV.
     */
    @Transient val hlsSizes: MutableList<Long> = mutableListOf(),
) {
    // ---- v4.6 additions (server profile, audit round 31 C) ----
    /** Peak number of segmented workers the sampler actually
     *  saw running at once, across all runs of this task (see
     *  DownloadEngine's sampler). Persisted additively like
     *  [peakBps] (manually serialized below): the profile's
     *  peak rows must survive a restart for DONE tasks, which
     *  is exactly when the user reads them. Records written
     *  before v4.6 parse as 0 = "no data" and the rows stay
     *  absent. Follows peakBps in the daily-reschedule reset:
     *  the existing reset deliberately keeps both peak stats
     *  (and the speed history), so this one is kept too. */
    var peakConns: Int = 0
    /** Bytes ACTUALLY WRITTEN to the file per source URL (the
     *  primary is a key like any mirror), counted by the
     *  segmented engine from each attempt's ActiveChunk.pos
     *  advance — reads an attempt discarded never move pos, so
     *  they never count. Persisted additively (a JSON object,
     *  so URLs with special characters are safe); records
     *  written before v4.6 parse as empty = no contribution
     *  rows. Guarded by synchronized(this map) like hlsSizes:
     *  worker threads add to it concurrently. Unlike the peak
     *  stats this map describes THE CURRENT FILE's bytes, so
     *  it IS cleared wherever those bytes are discarded: the
     *  daily reschedule, a validator-change reset and a
     *  part-integrity reset. */
    val sourceBytes: MutableMap<String, Long> = mutableMapOf()

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

    /**
     * v5.3 (audit round 38): the connection count a surface may
     * print as a present-tense fact ("N 条连接"). [threads] is
     * the count the engine is AIMING for (the scaler target —
     * the connCapHeld card line says exactly that: 想要
     * threads 条）, so printing it bare as the live count
     * contradicted that same line whenever the global cap held
     * the task down, and overstates during the v5.3 GrowthRamp
     * climb. While RUNNING, the sampler's per-second snapshot
     * ([connInfos]) IS the live count — use it. In every other
     * state there is no live snapshot (it is cleared when a run
     * ends), so the target is the only honest number left and
     * is what surfaces printed before; callers that need the
     * distinction (the cap line) still read [threads] and
     * [connInfos] directly.
     */
    val displayConnections: Int
        get() = if (status == TaskStatus.RUNNING && connInfos.isNotEmpty()) {
            connInfos.size
        } else {
            threads
        }

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
    /**
     * v2.1 audit (round 7, C5): this list is iterated and mutated
     * from the UI thread (add / remove / 插队 / composition filters),
     * service threads (drain picks, the connectivity callback, the
     * cancel path in runOne) and the save executor (serialize) —
     * a plain ArrayList under that access pattern can throw
     * ConcurrentModificationException mid-iteration (a dead drain
     * loop silently stalls the whole queue; in composition it is a
     * crash) or hand out torn reads. Mutations are rare and
     * iterations are constant, so a copy-on-write list is the right
     * trade: every iterator sees one consistent snapshot.
     */
    val tasks: MutableList<TaskRecord> =
        java.util.concurrent.CopyOnWriteArrayList()

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

    // ---- v1.7 perf: coalesced saves ----
    // save() used to serialize EVERY task + write tmp + copy .bak on
    // every call — and callers call it constantly (engine sampler
    // ticks, pause/delete taps, progress milestones). The write side,
    // not the load side, was the real first-frame/jank cost with a
    // long task list. Now save() only marks the store dirty and
    // schedules ONE flush up to SAVE_DELAY_MS later; further calls
    // inside the window are no-ops, so a burst collapses into a
    // single write (at most one flush per window). Structural
    // changes — add / remove / import — flush IMMEDIATELY via
    // saveNow(): losing a whole task record to a crash inside the
    // window would be far worse than losing 1.5 s of progress.
    private val saveExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "taskstore-save").apply { isDaemon = true }
        }
    @Volatile private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    /** Coalesced save: at most one disk flush per [SAVE_DELAY_MS]. */
    fun save() {
        val p = pendingSave
        if (p != null && !p.isDone) return
        synchronized(this) {
            val p2 = pendingSave
            if (p2 != null && !p2.isDone) return
            pendingSave = saveExecutor.schedule(
                { runCatching { writeToDisk() } },
                SAVE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Flush right now, cancelling any scheduled coalesced flush. */
    fun saveNow() {
        pendingSave?.cancel(false)
        pendingSave = null
        writeToDisk()
    }

    @Synchronized
    private fun writeToDisk() {
        val text = serialize(tasks)
        // v1.3 (audit A11): atomic save — write a temp file, keep the
        // previous good file as .bak, then rename over. A crash or
        // power loss mid-save can no longer corrupt the only copy.
        val tmp = File(metaFile.path + ".tmp")
        // v1.8 audit: fsync the tmp file BEFORE the rename. Without
        // it, a power cut in the rename window can leave the new
        // tasks.json zero-length or torn on delayed-allocation
        // filesystems (the .bak fallback then costs one flush of
        // state). Worst case after this change: the rename itself
        // is atomic, so readers see either the old or the new
        // complete file — never a half one.
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            runCatching { out.fd.sync() }
        }
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

    /**
     * v1.4: export the whole task list as JSON text (no file
     * bytes). v1.9: exports STRIP each task's Cookie header —
     * it is a login credential and a backup file may end up
     * anywhere; the on-disk store keeps it (resume needs it).
     */
    fun exportJson(): String = serialize(tasks, stripSensitiveHeaders = true)

    /**
     * v1.4: import tasks from an exported JSON text. Records whose id
     * already exists, or whose URL is already an active task, are
     * skipped. Returns how many were actually added.
     *
     * v3.4 (audit round 20): the accept/skip decision is planned by
     * the pure [planImport] in O(existing + incoming). The old loop
     * ran two `any {}` scans of the live list PER incoming record —
     * quadratic: importing a 1,000-record backup into a 1,000-task
     * store was ~2M string comparisons, and every accepted record
     * also paid a full copy-on-write array copy on `add`. Now the
     * accepted records land with ONE addAll.
     */
    @Synchronized
    fun importJson(text: String): Int {
        val incoming = deserialize(text)
        val accepted = planImport(tasks, incoming)
        for (t in accepted) {
            // Imported unfinished tasks must not pretend to be running.
            if (t.status == TaskStatus.QUEUED) t.status = TaskStatus.PAUSED
        }
        if (accepted.isNotEmpty()) {
            tasks.addAll(accepted)
            saveNow()
        }
        return accepted.size
    }

    /**
     * v5.8: creation hook, invoked by [add] only — the ONE
     * funnel every task-creation entry passes through
     * (home create / sniff / scan / in-app update all end in
     * store.add). Import deliberately bypasses it (importJson
     * uses addAll): an imported record must not trigger fresh
     * network work. DownloadCenter installs the sidecar
     * checksum fetch here; the hook itself must be cheap and
     * non-blocking (it runs on the caller's thread).
     */
    @Volatile
    var onTaskAdded: ((TaskRecord) -> Unit)? = null

    fun add(t: TaskRecord): TaskRecord {
        tasks.add(0, t)
        saveNow()
        runCatching { onTaskAdded?.invoke(t) }
        return t
    }

    fun remove(t: TaskRecord) {
        tasks.remove(t)
        fileOf(t).delete()
        saveNow()
    }

    /**
     * v3.4: bulk removal with ONE flush. [remove] saves
     * immediately per record — right for a single user tap,
     * wrong for the old-record cleanup: hundreds of victims
     * would mean hundreds of full-store serializations and
     * writes (quadratic IO — exactly what audit round 20
     * exists to catch). Only the records go; downloaded files
     * and .part files are never touched here.
     */
    fun removeAll(victims: Collection<TaskRecord>) {
        if (victims.isEmpty()) return
        val ids = victims.mapTo(HashSet()) { it.id }
        val doomed = tasks.filter { it.id in ids }
        if (doomed.isEmpty()) return
        // CopyOnWriteArrayList supports removeAll(Collection)
        // (it rebuilds the array under its lock); the predicate
        // form would go through the iterator and throw.
        tasks.removeAll(doomed)
        for (v in doomed) fileOf(v).delete()
        saveNow()
    }

    fun byId(id: String): TaskRecord? = tasks.firstOrNull { it.id == id }

    companion object {
        /** v1.7: coalesced-save window — at most one flush per 1.5 s. */
        const val SAVE_DELAY_MS = 1500L

        /** v1.9 audit: sanity ceilings for count-shaped fields in
         *  parsed records (real HLS playlists have at most a few
         *  thousand segments; real tasks a handful of mirrors and
         *  headers). Anything above is corrupt or hostile input. */
        const val MAX_HLS_SEGMENTS = 200_000
        const val MAX_SOURCES = 20
        const val MAX_HEADERS = 50

        /**
         * v3.4 (audit round 20): the pure import plan — which of
         * [incoming] would be added on top of [existing], under
         * exactly the rules [importJson] has always applied:
         * an id that already exists loses; a non-DONE record whose
         * URL is already held by an ACTIVE (not DONE, not FAILED)
         * task loses. Records accepted earlier in the same plan
         * count as existing for both checks, so duplicates inside
         * one backup file dedupe the same way they did when the
         * live list grew record by record. Hash sets keep the
         * whole plan linear instead of quadratic.
         */
        fun planImport(
            existing: List<TaskRecord>,
            incoming: List<TaskRecord>,
        ): List<TaskRecord> {
            val seenIds = HashSet<String>(existing.size + incoming.size)
            val activeUrls = HashSet<String>()
            for (t in existing) {
                seenIds.add(t.id)
                if (t.status != TaskStatus.DONE && t.status != TaskStatus.FAILED) {
                    activeUrls.add(t.url)
                }
            }
            val accepted = ArrayList<TaskRecord>()
            for (t in incoming) {
                if (t.id in seenIds) continue
                if (t.status != TaskStatus.DONE && t.url in activeUrls) continue
                accepted.add(t)
                seenIds.add(t.id)
                if (t.status != TaskStatus.DONE && t.status != TaskStatus.FAILED) {
                    activeUrls.add(t.url)
                }
            }
            return accepted
        }

        /** Pure serialization — same format on disk and for export.
         *  [stripSensitiveHeaders] is for EXPORTS only (v1.9): the
         *  Cookie header is removed so a backup file never carries
         *  a session credential. */
        fun serialize(
            list: List<TaskRecord>,
            stripSensitiveHeaders: Boolean = false,
        ): String {
            val arr = JSONArray()
            for (t in list) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("url", t.url)
                o.put("fileName", t.fileName)
                o.put("totalSize", t.totalSize)
                o.put("chunkSize", t.chunkSize)
                o.put("fineChunkSize", t.fineChunkSize)
                o.put("fineChunkCount", t.fineChunkCount)
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
                val sv = JSONObject()
                synchronized(t.sourceVerdicts) {
                    for ((k, v) in t.sourceVerdicts) sv.put(k, v.name)
                }
                o.put("sourceVerdicts", sv)
                synchronized(t.sourceOverrides) {
                    o.put("sourceOverrides", JSONArray(t.sourceOverrides.toList()))
                }
                o.put("priority", t.priority)
                o.put("isHls", t.isHls)
                o.put("hlsTotalSegments", t.hlsTotalSegments)
                o.put("hlsFingerprint", t.hlsFingerprint)
                o.put("expectedSha256", t.expectedSha256)
                o.put("expectedMd5", t.expectedMd5)
                o.put("checksumState", t.checksumState)
                o.put("checksumFromSidecar", t.checksumFromSidecar)
                o.put("failureKind", t.failureKind.name)
                o.put("failureHttpCode", t.failureHttpCode)
                o.put("jumpedAt", t.jumpedAt)
                o.put("scheduleDaily", t.scheduleDaily)
                o.put("taskLimitKBps", t.taskLimitKBps)
                o.put("archived", t.archived)
                o.put("userNote", t.userNote)
                o.put("hlsDone", ChunkPlanner.encodeBitset(t.hlsDone))
                synchronized(t.hlsSizes) {
                    o.put("hlsSizesCsv", t.hlsSizes.joinToString(","))
                }
                o.put("peakBps", t.peakBps)
                o.put("peakConns", t.peakConns)
                val sb = JSONObject()
                synchronized(t.sourceBytes) {
                    for ((k, v) in t.sourceBytes) sb.put(k, v)
                }
                o.put("sourceBytes", sb)
                val hist = JSONArray()
                synchronized(t.speedHistory) { for (v in t.speedHistory) hist.put(v) }
                o.put("speedHistory", hist)
                val h = JSONObject()
                val headersOut = if (stripSensitiveHeaders) {
                    TaskHeaders.strippedForExport(t.headers)
                } else {
                    t.headers
                }
                for ((k, v) in headersOut) h.put(k, v)
                o.put("headers", h)
                arr.put(o)
            }
            return arr.toString()
        }

        /** Pure parsing; a corrupt text yields an empty list, never a crash. */
        fun deserialize(text: String): MutableList<TaskRecord> {
            val out = mutableListOf<TaskRecord>()
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return out
            for (i in 0 until arr.length()) {
                // v1.9 audit (round 5): the guard is PER RECORD now.
                // This parser also reads imported backups — fully
                // untrusted input — and one poisoned record (bad
                // types, absurd counts) used to discard the whole
                // list; now it only discards itself. Count-shaped
                // fields are additionally clamped below so a record
                // cannot size a giant allocation (see ChunkPlanner).
                runCatching {
                    val o = arr.getJSONObject(i)
                    val t = TaskRecord(
                        id = o.getString("id"),
                        url = o.getString("url"),
                        fileName = o.optString("fileName"),
                        totalSize = o.optLong("totalSize", -1),
                        chunkSize = o.optLong("chunkSize", 0),
                        fineChunkSize = o.optLong("fineChunkSize", 0),
                        fineChunkCount = o.optInt("fineChunkCount", 0),
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
                        // v4.8 audit (round 33): a negative count
                        // from a corrupt/hostile record is nonsense
                        // — worse, a negative autoRetriesUsed makes
                        // RetryPolicy.shouldRetry (used < limit)
                        // grant MORE whole-task retries than the
                        // user configured. Clamp at the parse door.
                        retryCount = o.optInt("retryCount", 0).coerceAtLeast(0),
                        autoRetriesUsed = o.optInt("autoRetriesUsed", 0)
                            .coerceAtLeast(0),
                        mobileConfirmed = o.optBoolean("mobileConfirmed", false),
                        priority = o.optInt("priority", 0),
                        isHls = o.optBoolean("isHls", false),
                        hlsTotalSegments = o.optInt("hlsTotalSegments", 0)
                            .coerceIn(0, MAX_HLS_SEGMENTS),
                        hlsFingerprint = o.optString("hlsFingerprint"),
                        expectedSha256 = o.optString("expectedSha256"),
                        expectedMd5 = o.optString("expectedMd5"),
                        checksumState = o.optInt("checksumState", 0),
                        checksumFromSidecar = o.optBoolean("checksumFromSidecar", false),
                        failureKind = runCatching {
                            FailureKind.valueOf(o.optString("failureKind"))
                        }.getOrDefault(FailureKind.UNKNOWN),
                        failureHttpCode = o.optInt("failureHttpCode", 0),
                        jumpedAt = o.optLong("jumpedAt", 0).coerceAtLeast(0),
                        scheduleDaily = o.optBoolean("scheduleDaily", false),
                        taskLimitKBps = o.optInt("taskLimitKBps", 0).coerceAtLeast(0),
                        archived = o.optBoolean("archived", false),
                        userNote = o.optString("userNote"),
                    )
                    // A verify never survives a restart: a persisted
                    // "verifying" just means it was interrupted.
                    // v4.8 audit (round 33): any value outside the
                    // known 0..3 range is normalized the same way —
                    // consumers judge by equality today so it was
                    // inert, but the persisted field should never
                    // carry a state no writer can produce.
                    if (t.checksumState == Checksum.STATE_VERIFYING ||
                        t.checksumState !in Checksum.STATE_NONE..Checksum.STATE_MISMATCHED
                    ) {
                        t.checksumState = Checksum.STATE_NONE
                    }
                    val srcArr = o.optJSONArray("sources")
                    if (srcArr != null) {
                        for (j in 0 until srcArr.length()) {
                            val s = srcArr.optString(j)
                            if (s.isNotBlank() && s != t.url && s !in t.sources &&
                                t.sources.size < MAX_SOURCES
                            ) {
                                t.sources.add(s)
                            }
                        }
                    }
                    // v5.4: per-mirror verdicts + manual overrides.
                    // Only entries for mirrors still in `sources`
                    // are read (orphans from an edited list die
                    // here), unknown verdict names drop themselves,
                    // and both collections are capped like sources.
                    val svObj = o.optJSONObject("sourceVerdicts")
                    if (svObj != null) {
                        val vkeys = svObj.keys()
                        synchronized(t.sourceVerdicts) {
                            while (vkeys.hasNext()) {
                                val k = vkeys.next()
                                if (k !in t.sources) continue
                                if (t.sourceVerdicts.size >= MAX_SOURCES) break
                                val v = runCatching {
                                    SourceCheck.Verdict.valueOf(svObj.optString(k))
                                }.getOrNull() ?: continue
                                t.sourceVerdicts[k] = v
                            }
                        }
                    }
                    val soArr = o.optJSONArray("sourceOverrides")
                    if (soArr != null) {
                        synchronized(t.sourceOverrides) {
                            for (j in 0 until soArr.length()) {
                                val s = soArr.optString(j)
                                if (s in t.sources &&
                                    t.sourceOverrides.size < MAX_SOURCES
                                ) {
                                    t.sourceOverrides.add(s)
                                }
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
                                // v1.9 audit: never more entries
                                // than the (clamped) segment count.
                                csv.split(',').take(t.hlsTotalSegments).forEach {
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
                    // v4.8 audit (round 33): the only writer is
                    // the segmented sampler, whose worker pool can
                    // never exceed MAX_THREADS — clamp the upper
                    // end too, so a corrupt Int.MAX cannot make
                    // the server profile display nonsense.
                    t.peakConns = o.optInt("peakConns", 0)
                        .coerceIn(0, DownloadEngine.MAX_THREADS)
                    val sbObj = o.optJSONObject("sourceBytes")
                    if (sbObj != null) {
                        val skeys = sbObj.keys()
                        var sn = 0
                        synchronized(t.sourceBytes) {
                            while (skeys.hasNext()) {
                                // Same hostile-input discipline
                                // as headers: the primary plus at
                                // most MAX_SOURCES mirrors can ever
                                // be real keys.
                                if (sn++ > MAX_SOURCES) break
                                val k = skeys.next()
                                val v = sbObj.optLong(k, 0)
                                if (k.isNotBlank() && v > 0) t.sourceBytes[k] = v
                            }
                        }
                    }
                    val h = o.optJSONObject("headers")
                    if (h != null) {
                        val keys = h.keys()
                        var hn = 0
                        while (keys.hasNext()) {
                            // v1.9 audit: a backup cannot stuff a
                            // record with thousands of headers.
                            if (hn++ >= MAX_HEADERS) break
                            val k = keys.next()
                            // v4.8 audit (round 33): this was the
                            // one getString in a parser where every
                            // sibling field uses opt* — ONE
                            // non-string header value threw and the
                            // per-record guard discarded the WHOLE
                            // record. Skip just the bad entry (and
                            // blank keys) and keep the record.
                            // optString alone would STRINGIFY a
                            // number/object value, so the type is
                            // checked first, then read via optString.
                            if (k.isBlank()) continue
                            if (h.opt(k) !is String) continue
                            t.headers[k] = h.optString(k)
                        }
                    }
                    val hist = o.optJSONArray("speedHistory")
                    if (hist != null) {
                        synchronized(t.speedHistory) {
                            // v4.8 audit (round 33): only the
                            // last 90 samples are ever kept, so
                            // read only that window — the old
                            // append-all-then-trim paid one
                            // optLong per entry of a hostile
                            // million-entry array for nothing.
                            val from = maxOf(0, hist.length() - 90)
                            for (j in from until hist.length()) {
                                t.speedHistory.add(hist.optLong(j, 0))
                            }
                        }
                    }
                    if (t.chunkSize > 0 && t.totalSize > 0) {
                        // v4.2: recompute against the task's own
                        // grid (uniform for every pre-v4.2 record).
                        val grid = ChunkPlanner.gridOf(
                            t.totalSize, t.chunkSize, t.fineChunkSize, t.fineChunkCount
                        )
                        val count = ChunkPlanner.chunkCount(grid)
                        t.chunkDone = ChunkPlanner.decodeBitset(o.optString("chunkDone"), count)
                        t.downloaded = ChunkPlanner.downloadedOf(grid, t.chunkDone)
                    }
                    if (t.status == TaskStatus.RUNNING) {
                        // v2.6: park it (the standing rule), but
                        // remember WHO stopped it — the system did,
                        // mid-transfer. Recovery keys off this.
                        t.status = TaskStatus.PAUSED
                        t.wasRunning = true
                    }
                    out.add(t)
                }
            }
            return out
        }
    }
}

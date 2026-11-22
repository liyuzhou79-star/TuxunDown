package com.tuxundown.android.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Segmented download engine.
 *
 * - Probes the server with a 1-byte Range request to learn the total size
 *   and whether Range is supported.
 * - Splits the file into small chunks; workers pull chunk indexes from a
 *   shared atomic queue, so a worker that finishes early automatically
 *   takes the next unfinished chunk (dynamic chunking / work stealing).
 *   On top of that, the v2.2 endgame: once every chunk has been
 *   started, idle workers cut the largest in-flight remainder into
 *   equal pieces and take them over (see [Endgame] for the plan and
 *   for how split parts share the chunk's single bitset bit).
 * - Auto thread probing: starts low, then doubles 4 -> 8 -> 16 -> 32
 *   -> 64 every 2 s while the measured speed keeps growing by more
 *   than 10%;
 *   stops at the first plateau. v2.3 adds mid-download DYNAMIC
 *   SCALING on top (see [ThreadScaler]): the settled count is not
 *   final — the scaler keeps growing it while the speed responds
 *   and reclaims connections the flat/declining line no longer
 *   rewards, with verdicts and learned bounds against flapping.
 *   Probing decides the start, scaling manages the middle, and
 *   HostMemory stores the scaler's final count as the optimum.
 * - Honest fallback: if the server does not support Range, the task is
 *   downloaded in a single stream and [TaskRecord.note] says so. If more
 *   threads stop helping (speed plateau at the first step), the note
 *   explains the server is likely throttling per IP.
 *
 * v1.3 changes:
 * - A failed range part RESUMES from its current offset on retry
 *   instead of restarting the part and re-downloading its bytes.
 * - A worker whose connection stalls (no byte for 12 s while the task
 *   as a whole is moving) is evicted: its connection is dropped and
 *   the part is retried on a fresh connection from the same offset.
 * - Redirects are followed manually, so http <-> https cross-protocol
 *   hops work and session headers are re-applied on every hop.
 * - ETag / Last-Modified captured at probe time are re-validated
 *   before a resume; a changed server file resets the chunk state
 *   instead of silently mixing two versions' bytes.
 * - The WiFi-only guard is consulted DURING the transfer, not just
 *   before it starts; losing WiFi pauses the task with a clear note.
 * - Optional user speed limit (token bucket) and 128 KB IO buffers.
 */
class DownloadEngine(
    private val task: TaskRecord,
    private val targetProvider: () -> DownloadTarget,
    private val onProgress: (TaskRecord) -> Unit,
    private val onSave: () -> Unit,
    private val limiter: SpeedLimiter? = null,
    private val networkGuard: () -> Boolean = { true },
    /** v1.4 traffic protection: consulted after the probe (when the
     *  size is known) and once a second during the transfer; returning
     *  false pauses the task instead of burning mobile data. */
    private val trafficGuard: () -> Boolean = { true },
    /** v2.3 space pre-check: consulted once the size is known
     *  (after the probe / revalidation; in FTP after SIZE) —
     *  returns the plain-language block message, or null to
     *  proceed. A guard that itself fails never blocks (the
     *  caller wraps it in runCatching): an unmeasurable volume
     *  is not a full one. */
    private val spaceGuard: () -> String? = { null },
    /** v1.5: remembered optimal thread count for this host (0 = none).
     *  A fresh segmented start begins AT this count and skips probing. */
    private val initialThreadsHint: Int = 0,
    /** v1.5: called once when a segmented download COMPLETES, with the
     *  thread count it settled at, so the caller can remember it. */
    private val onThreadsSettled: (Int) -> Unit = {},
    /** v1.5: called once when a segmented download FAILS, so the
     *  caller can age out the remembered count for this host. */
    private val onHostFailed: () -> Unit = {},
    /** v2.8: remembered rate-limit ceiling for this host (0 =
     *  none, see HostMemory). Bounds the fresh-start hint AND
     *  the scaler's max for the whole run, so a host that
     *  throttled parallel load last time is not hammered again. */
    private val initialThreadsCap: Int = 0,
    /** v2.8: called once when a segmented download FAILS with a
     *  rate-limit signature (see RateLimit), with the connection
     *  count the host was rejecting load at, so the caller can
     *  remember a ceiling for next time. */
    private val onRateLimited: (Int) -> Unit = {},
    /** v3.9: this task's live allowance under the user's global
     *  connection cap (see ConnectionBudget), re-read on every
     *  scaler tick; Int.MAX_VALUE (the default) = no global cap.
     *  The scaler's target stays the task's unconstrained
     *  appetite — the allowance only gates how many workers may
     *  actually run at once, so when the cap lifts (another
     *  task finished) the run grows back with no re-probe, and
     *  the count HostMemory remembers is never the cap's. */
    private val connectionAllowance: () -> Int = { Int.MAX_VALUE },
    /** v1.6 dual channel: an alternate connection opener (e.g.
     *  bound to the cellular network). When present, new
     *  connections alternate between the default network and this
     *  one, so WiFi and mobile data carry the download together. */
    private val extraOpener: ((URL) -> HttpURLConnection)? = null,
    /** v1.7: the user's custom User-Agent setting ("" = default).
     *  A UA captured for THIS task (sniffer/browser) still wins. */
    private val customUserAgent: String = "",
    /** v1.9: the app-wide download proxy. When active, EVERY
     *  connection this engine opens goes through it; a proxy
     *  that cannot be reached fails the run with a plain message
     *  (see openFollowingRedirects) — never a silent direct
     *  connection. FTP runs ignore it (documented in settings). */
    private val proxySettings: ProxySettings = ProxySettings(),
) {
    /** v2.3: owns the worker count for the segmented run — the
     *  opening ladder, then steady-state dynamic scaling (see
     *  [ThreadScaler]). Created in runSegmented, read by the
     *  sampler (drives it) and by workers (surplus exit). */
    @Volatile private var scaler: ThreadScaler? = null
    /** v3.9: the allowance most recently read from
     *  [connectionAllowance]; workers consult it alongside the
     *  scaler target when deciding whether they are surplus. */
    @Volatile private var connAllowance: Int = Int.MAX_VALUE
    /** Seekable destination in the public download folder, opened after probing. */
    private lateinit var target: DownloadTarget
    @Volatile var paused = false
        private set
    @Volatile private var running = false
    /** Set when the WiFi-only guard stops the transfer mid-flight. */
    @Volatile private var netBlocked = false
    /** Set when the traffic-protection guard stops the transfer. */
    @Volatile private var trafficBlocked = false

    private fun stopped(): Boolean = paused || netBlocked || trafficBlocked

    /** v1.6 dual channel: flips each new connection between the
     *  default network and [extraOpener] when one was supplied. */
    private val openerFlip = AtomicInteger(0)

    /** v1.6 multi-source: primary + validated mirrors for this run. */
    @Volatile private var activeSources: List<String> = listOf(task.url)

    /** v2.8: this run's rate-limit evidence (primary source
     *  only), judged by RateLimit if the run fails. */
    private val rejections =
        java.util.Collections.synchronizedList(mutableListOf<RateLimit.Rejection>())

    private val downloadedBytes = AtomicLong(task.downloaded)
    private val nextChunk = AtomicInteger(0)
    private val activeWorkers = AtomicInteger(0)

    private class ActiveChunk(val index: Int, start: Long, end: Long) {
        @Volatile var pos: Long = start // next byte to write
        @Volatile var end: Long = end   // exclusive; may shrink when split
        val start: Long = start
        /** v3.5: pos at the sampler's previous tick — the
         *  per-connection speed in the details view is this
         *  tick's delta over it (see the sampler). */
        @Volatile var lastSamplePos: Long = start
        /** Live connection, so the sampler can evict a stalled worker. */
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var lastAdvanceMs: Long = System.currentTimeMillis()
    }

    private val active = mutableListOf<ActiveChunk>()
    private val activeLock = Object()
    /**
     * Outstanding parts per chunk index (a chunk split by stealing has 2).
     *
     * v4.7 CORE INVARIANT: chunkParts[idx] always equals the number
     * of LIVE parts whose CURRENT span covers idx (an absent entry
     * means 1 — the one part a fresh claim created). Every mutation
     * preserves it: a fresh merged claim creates ONE part covering
     * several indexes (each still counts 1, so no entry is needed);
     * an endgame split rebalances every overlapped index in one
     * locked delta (ChunkPlanner.splitRebalancedCounts); a
     * completion drains exactly one from every index its part's
     * FINAL span overlaps (ChunkPlanner.indexesOverlapping). An
     * index is marked done ONLY when that completion drain takes
     * its count to 0 — a split can never do it.
     */
    private val chunkParts = mutableMapOf<Int, Int>()

    /** v4.8: one in-flight chunk of an FTP segmented run — the
     *  FTP counterpart of [ActiveChunk] (which is HTTP-shaped:
     *  its eviction handle is an HttpURLConnection). The eviction
     *  handle here is the worker's [FtpClient], whose data socket
     *  the sampler closes via abortTransfer. */
    private class FtpPart(val index: Int, start: Long, end: Long) {
        @Volatile var pos: Long = start // next byte to write
        val start: Long = start
        val end: Long = end // exclusive
        @Volatile var lastSamplePos: Long = start
        @Volatile var lastAdvanceMs: Long = System.currentTimeMillis()
        /** The session currently transferring this part. */
        @Volatile var client: FtpClient? = null
    }

    fun pause() {
        paused = true
    }

    /** Append one per-second sample for the speed curve (keeps ~90 s). */
    private fun recordSpeed(bps: Long) {
        if (bps > task.peakBps) task.peakBps = bps
        synchronized(task.speedHistory) {
            task.speedHistory.add(bps)
            while (task.speedHistory.size > 90) task.speedHistory.removeAt(0)
        }
    }

    /** Blocking run on the calling thread. Returns final status. */
    fun run(): TaskStatus {
        if (running) return task.status
        running = true
        paused = false
        netBlocked = false
        trafficBlocked = false
        // v1.4 stats: first-start stamp + accumulated transfer time.
        if (task.startedAt == 0L) task.startedAt = System.currentTimeMillis()
        // v3.4: a fresh run is by definition unfinished — clear
        // any terminal stamp an earlier run left, so a failure
        // stamp from before a resume can never survive into a
        // later success (the DONE branch in the finally only
        // stamps when this is 0).
        task.finishedAt = 0
        // v4.4 (audit round 29): the raw-failure verdicts belong
        // to THIS run only — a stale one must never park (or
        // fail to park) a later run.
        task.netOutage = false
        task.portalHit = false
        // v4.5 (audit round 30): same for the structured
        // failure verdict — it belongs to THIS run's failure,
        // and a stale one must never classify a later run.
        task.failureKind = FailureKind.UNKNOWN
        task.failureHttpCode = 0
        val runStartMs = System.currentTimeMillis()
        try {
            // v1.7: FTP links take their own path (hand-written
            // FtpClient: PASV, SIZE/REST probe, single stream with
            // REST resume) — HTTP probing would be meaningless.
            if (FtpClient.isFtpUrl(task.url)) {
                return runFtp()
            }
            // v4.2 connection warmup (knife 4, see Warmup +
            // warmUp below): fire a daemon side thread NOW —
            // after the FTP early return (FTP has no
            // HttpURLConnection path to warm) and before the
            // HLS branch, the probe / resume revalidation and
            // every later branch, so the HLS, single-stream and
            // segmented paths are all covered. The main thread
            // never waits for it: warmup runs in parallel with
            // the target open, the guard checks and the probe,
            // and the probe / first chunk requests reuse the
            // connections it pooled. When a proxy is active
            // Warmup.sourcesToWarm returns an empty list and no
            // warmup happens at all (see Warmup's doc for the
            // v1.9 never-go-direct rule behind that).
            Thread { runCatching { warmUp() } }
                .apply { isDaemon = true; name = "tuxundown-warmup"; start() }
            // v1.6: HLS (m3u8) tasks take their own path — playlist,
            // parallel segment downloads, in-order merge into one .ts.
            if (task.isHls || isHlsUrl(task.url)) {
                task.isHls = true
                return runHls()
            }
            // v1.8 start-up speed pass — count the round trips a
            // start used to pay before the first chunk could move:
            //   fresh:  probe (1) — unavoidable, the total size and
            //           Range support are genuinely unknown.
            //   resume: validateResume's own 1-byte request (1),
            //           THEN the .part target open (a MediaStore /
            //           SAF binder round trip), strictly serial.
            //   mirrors: 1 + 2N sequential requests in
            //           validateSources (fixed there, see its doc).
            // Now a resume makes ONE pre-flight request (the
            // revalidation IS the probe for a known-size task) and
            // the target opens CONCURRENTLY with it on a side
            // thread — the file name is already known on a resume,
            // which is the only thing the open ever waited for.
            val resuming = task.chunkDone.any { it } ||
                (task.singleThreadOnly && task.downloaded > 0)
            val preTarget = java.util.concurrent.atomic.AtomicReference<DownloadTarget?>()
            val preTargetError = java.util.concurrent.atomic.AtomicReference<Exception?>()
            val preThread = if (resuming && task.fileName.isNotBlank()) {
                Thread {
                    try {
                        preTarget.set(targetProvider())
                    } catch (e: Exception) {
                        preTargetError.set(e)
                    }
                }.apply { isDaemon = true; start() }
            } else null
            fun takePreTarget(): DownloadTarget? {
                if (preThread == null) return null
                preThread.join()
                preTargetError.get()?.let { throw it }
                return preTarget.get()
            }
            fun dropPreTarget() {
                if (preThread == null) return
                runCatching { preThread.join(5_000) }
                preTarget.get()?.let { runCatching { it.close() } }
            }
            try {
                if (task.totalSize <= 0 || task.fileName.isBlank()) probe()
                else if (resuming) revalidateResume()
            } catch (e: Exception) {
                dropPreTarget()
                throw e
            }
            // Traffic protection can only judge once the size is known.
            if (!trafficGuard()) {
                dropPreTarget()
                trafficBlocked = true
                task.status = TaskStatus.PAUSED
                task.note = Notes.trafficGuardAtStart(task.totalSize)
                return task.status
            }
            // v2.3 space pre-check: the size is certain only now —
            // stop BEFORE the first byte when the volume cannot
            // hold what is left to write, instead of dying halfway
            // through a multi-GB file (see SpaceCheck).
            if (task.totalSize > 0) {
                val spaceMsg = runCatching { spaceGuard() }.getOrNull()
                if (spaceMsg != null) {
                    dropPreTarget()
                    task.status = TaskStatus.PAUSED
                    task.note = spaceMsg
                    return task.status
                }
            }
            if (task.totalSize == 0L) {
                // A genuinely empty file: nothing to transfer.
                target = takePreTarget() ?: targetProvider()
                try { target.truncate(0) } finally { runCatching { target.close() } }
                task.downloaded = 0
                task.status = TaskStatus.DONE
                return task.status
            }
            target = takePreTarget() ?: targetProvider()
            try {
                if (task.singleThreadOnly || task.totalSize <= 0) {
                    // Mirrors can only help a segmented download; say
                    // so instead of silently ignoring them (v1.6).
                    if (task.sources.isNotEmpty() &&
                        !task.note.contains("镜像源需要服务器支持分片")
                    ) {
                        appendNote(
                            "镜像源需要服务器支持分片（Range）才能一起用，" +
                                "这个服务器不支持，已只用主地址下载。"
                        )
                    }
                    return runSingleStream()
                }
                val result = runSegmented()
                // v1.5 host memory: only a COMPLETED segmented run
                // proves a thread count; a failure ages the memory.
                if (result == TaskStatus.DONE) {
                    runCatching { onThreadsSettled(task.threads) }
                } else if (result == TaskStatus.FAILED) {
                    runCatching { onHostFailed() }
                    // v2.8: if the failure carries a rate-limit
                    // signature, hand the caller the load level
                    // so it can remember a ceiling for this host
                    // (the evidence rules live in RateLimit — a
                    // lone 403 never convicts).
                    val atLimit = RateLimit.assess(rejections)
                    if (atLimit > 0) runCatching { onRateLimited(atLimit) }
                }
                return result
            } finally {
                runCatching { target.close() }
            }
        } catch (e: Exception) {
            // v2.2 audit round 8: never show a raw class name or an
            // English library message on the card — Errors.human
            // keeps our own plain sentences and maps the rest.
            // v3.2 audit round 18: a full disk reaches this catch
            // from the HLS / single-stream / FTP paths as a bare
            // IOException, which Errors.human would call a network
            // problem ("换个网络") — wrong advice that sends the
            // user debugging their router while the disk is full.
            // The segmented path already special-cases it per
            // chunk (downloadActive); give the other paths the
            // same sentence here.
            // v4.4 (audit round 29): record the RAW-failure
            // verdicts BEFORE the text is humanized — after
            // Errors.human the English evidence is gone by
            // design, and the service's outage / portal parks
            // judge these flags, never the card text.
            recordFailureVerdict(e)
            task.error = if (isStorageFull(e)) {
                Notes.storageFull()
            } else if (isStorageGone(e)) {
                Notes.storageDeviceGone()
            } else {
                Errors.human(e)
            }
            task.status = if (paused) TaskStatus.PAUSED else TaskStatus.FAILED
            return task.status
        } finally {
            // v3.5: the live connection snapshot dies with
            // the run — a stale one would show connections
            // that no longer exist.
            task.connInfos = emptyList()
            // v3.9: same for the global-cap contention flag.
            task.connCapHeld = false
            task.elapsedMs += System.currentTimeMillis() - runStartMs
            if (task.status == TaskStatus.DONE && task.finishedAt == 0L) {
                task.finishedAt = System.currentTimeMillis()
            }
            if (task.status == TaskStatus.FAILED) {
                // v3.4: a failure is a terminal state too — stamp
                // when THIS run failed, so the old-record cleanup
                // (RecordCleanup) and the download report can date
                // a failure instead of falling back to the
                // creation time. run() cleared the stamp at the
                // start of this run, so this can only ever be the
                // current failure's time.
                task.finishedAt = System.currentTimeMillis()
            }
            running = false
            onSave()
            onProgress(task)
        }
    }

    /**
     * v4.2 connection warmup (knife 4): for each source [Warmup]
     * selects, resolve the host (warming the DNS cache) and issue
     * ONE Range bytes=0-0 request through the normal open path,
     * so the socket lands in the platform keep-alive pool before
     * the probe / first chunks need it. Runs on a daemon side
     * thread fired at run() start; every failure is silent —
     * warmup is pure optimisation and must never affect the run.
     *
     * Discipline:
     * - A 206 answer has a 1-byte body: [releaseConnection]
     *   drains it and the connection returns to the pool (the
     *   same handling probe() uses). ANY other answer is
     *   disconnect()ed — a 200's body is the whole file and must
     *   never be drained.
     * - NO task fields are written here: the task's state belongs
     *   to probe() / revalidateResume(). (openFollowingRedirects
     *   only READS task.headers — verified: it never writes the
     *   task — so sharing it from this thread is safe.)
     * - Proxy: when a proxy is active, [Warmup.sourcesToWarm]
     *   returns an empty list, so this loop never runs at all —
     *   warmup never opens a direct connection behind the
     *   proxy's back (v1.9: a configured proxy is used or the
     *   run fails honestly, never a silent direct fall-back).
     * - Dual channel: openFollowingRedirects alternates via
     *   openerFlip when an extraOpener exists, so warmup calls
     *   advance that counter — harmless. But it means warmup
     *   only guarantees the DEFAULT network's path is warm; the
     *   alternate (extraOpener) network's connections warm
     *   themselves on their first real use.
     * - Happy Eyeballs: it only affects openDefault's HTTPS
     *   path; warmup goes through that same open path and
     *   benefits automatically — nothing extra to do here.
     */
    private fun warmUp() {
        val targets = Warmup.sourcesToWarm(task.url, task.sources, proxySettings.active)
        for (src in targets) {
            // v4.5: claim the host before warming it (see
            // WarmHosts) — if the queue preheat is warming this
            // very host right now (the task started while its
            // preheat was in flight), skip: the connection it
            // pools serves this run too, and two concurrent
            // warms of one host are pure waste.
            val host = runCatching { URL(src).host }.getOrDefault("")
            if (host.isBlank() || !WarmHosts.tryClaim(host)) continue
            try {
                runCatching {
                    // Warm the DNS cache first (failures ignored):
                    // the pooled connection is worthless if the next
                    // lookup still has to pay the DNS round trip.
                    runCatching { InetAddress.getAllByName(host) }
                    val c = openFollowingRedirects(src) {
                        it.setRequestProperty("Range", "bytes=0-0")
                    }
                    if (c.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        releaseConnection(c)
                    } else {
                        runCatching { c.disconnect() }
                    }
                }
            } finally {
                WarmHosts.release(host)
            }
        }
    }

    /**
     * v2.2 connection warm reuse: release a connection whose
     * response body is tiny / fully consumed so the platform
     * keep-alive pool can hand the SAME socket (TLS handshake
     * already paid) to the next request to this host. The pool
     * itself is the platform's (Android's HttpURLConnection keeps
     * up to 5 idle connections per host) — there is deliberately
     * no home-grown pool here; the only thing v2.2 changes is
     * that the probe / validation / sample paths no longer defeat
     * the pool with disconnect(): they drain the (1-byte) body
     * and close the stream instead. If the body turns out larger
     * than [drainCap] the connection is NOT reusable and is
     * disconnected as before. Every call is best-effort.
     */
    private fun releaseConnection(c: HttpURLConnection, drainCap: Int = 4096) {
        val reusable = runCatching {
            val input = c.inputStream
            val buf = ByteArray(1024)
            var drained = 0
            var eof = false
            while (drained <= drainCap) {
                val n = input.read(buf, 0, minOf(buf.size, drainCap + 1 - drained))
                if (n < 0) {
                    eof = true
                    break
                }
                drained += n
            }
            input.close()
            eof
        }.getOrDefault(false)
        if (!reusable) runCatching { c.disconnect() }
    }

    /**
     * v1.9: open on the default network — through the configured
     * proxy when one is active. openConnection(proxy) is the ONLY
     * path then; there is no direct fall-back anywhere.
     */
    private fun openDefault(url: URL): HttpURLConnection {
        val p = proxySettings.javaProxy()
        if (p != null) {
            return url.openConnection(p) as HttpURLConnection
        }
        val c = url.openConnection() as HttpURLConnection
        // v2.6 Happy Eyeballs: on the direct HTTPS path, race
        // the TCP connect across IPv6/IPv4 inside the TLS
        // socket factory (see HappyEyeballs for the honest
        // scope — plain HTTP has no public socket hook and
        // keeps the platform path; a proxy or the dual-channel
        // opener never comes through here).
        if (c is javax.net.ssl.HttpsURLConnection) {
            runCatching { c.sslSocketFactory = RacingSocketFactory.shared }
        }
        return c
    }

    /**
     * Open a connection and follow redirects MANUALLY (audit A5):
     * HttpURLConnection's built-in follower refuses http <-> https
     * hops, which real download servers use all the time. Session
     * headers (UA / Referer) and the caller's own headers
     * (Range) are re-applied on every hop. Returns the connection
     * after the final response code has been read.
     *
     * v4.7 audit round 32: the Cookie is the ONE header that does
     * NOT ride every hop unconditionally. It is a credential for
     * the host of task.url only, so each hop asks [SessionScope]
     * with THIS hop's URL (`current`): a request to a mirror the
     * user pasted for speed, a mirror validation probe / sample,
     * a warmup over task.sources, or a redirect hop to a
     * different host receives NO cookie, exactly like a browser
     * scopes cookies by domain. Referer still rides every hop —
     * it is the anti-hotlink signal servers check cross-host by
     * design, a different sensitivity class from a credential.
     */
    private fun openFollowingRedirects(
        startUrl: String,
        apply: (HttpURLConnection) -> Unit,
    ): HttpURLConnection {
        var current = startUrl
        // v1.7 compat: detect redirect LOOPS (the same URL twice)
        // instead of burning the whole hop budget on them.
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            if (!visited.add(current)) {
                throw IllegalStateException("服务器跳转成了一个圈（来回跳），已停下")
            }
            val hopUrl = URL(current)
            val extra = extraOpener
            val c = if (extra != null && openerFlip.getAndIncrement() % 2 == 1) {
                // Dual channel: odd connections ride the alternate
                // network; if it refuses, fall back to the default.
                runCatching { extra(hopUrl) }
                    .getOrElse { openDefault(hopUrl) }
            } else {
                openDefault(hopUrl)
            }
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty(
                "User-Agent",
                UserAgents.resolve(task.headers["User-Agent"], customUserAgent),
            )
            // v2.4 audit round 10 (byte integrity): byte ranges are
            // defined over the IDENTITY representation of a file.
            // Android's HttpURLConnection otherwise adds
            // "Accept-Encoding: gzip" by itself and transparently
            // decompresses the response — a server that ranges over
            // a gzip representation, or wraps range bodies in one,
            // would hand us bytes that do not belong at the
            // requested offsets, silently. "identity" pins every
            // response on every engine path (probe, chunks, single
            // stream, HLS playlists/segments, mirror samples) to
            // the raw file bytes. The SNIFFER is different: it asks
            // for gzip explicitly and decompresses HTML itself.
            c.setRequestProperty("Accept-Encoding", "identity")
            // v1.9: HTTP-proxy credentials ride every hop.
            proxySettings.proxyAuthHeader()?.let {
                c.setRequestProperty("Proxy-Authorization", it)
            }
            // v1.7 compat: Basic authentication embedded in the URL
            // itself (http://user:pass@host/file). HttpURLConnection
            // never turns userInfo into an Authorization header on
            // its own, so credentialed links used to die with a 401.
            // Taken from THIS hop's URL only — a redirect target
            // without userInfo never receives the credentials. An
            // explicit Authorization header (e.g. captured by the
            // sniffer) always wins.
            val userInfo = hopUrl.userInfo
            if (!userInfo.isNullOrEmpty() && task.headers["Authorization"] == null) {
                runCatching {
                    val decoded = java.net.URLDecoder.decode(userInfo, "UTF-8")
                    val b64 = java.util.Base64.getEncoder()
                        .encodeToString(decoded.toByteArray(Charsets.UTF_8))
                    c.setRequestProperty("Authorization", "Basic $b64")
                }
            }
            // v4.7 audit round 32: host-scoped cookie (see the
            // doc above and SessionScope) — consulted per hop
            // with this hop's URL, never re-sent blindly.
            SessionScope.cookieFor(task.headers["Cookie"], task.url, current)
                ?.let { c.setRequestProperty("Cookie", it) }
            task.headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
            apply(c)
            // v1.9: reading the response code is what actually
            // connects. With a proxy configured, a connect failure
            // here means the PROXY is unreachable — say exactly
            // that and stop; the one thing we must never do is
            // quietly retry without the proxy.
            val code = try {
                c.responseCode
            } catch (e: java.io.IOException) {
                runCatching { c.disconnect() }
                if (proxySettings.active) {
                    throw IllegalStateException(
                        ProxySettings.unreachableMessage(proxySettings, e)
                    )
                }
                throw e
            }
            if (code in 301..303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) {
                    throw IllegalStateException("服务器跳转但没给新地址，已停下")
                }
                current = runCatching { URL(URL(current), loc).toString() }
                    .getOrElse { throw IllegalStateException("服务器给的跳转地址无效") }
                return@repeat
            }
            return c
        }
        throw IllegalStateException("跳转次数太多（超过 $MAX_REDIRECTS 次），已停下")
    }

    private fun probe() {
        val c = openFollowingRedirects(task.url) {
            it.setRequestProperty("Range", "bytes=0-0")
        }
        val code = c.responseCode
        val contentType = c.getHeaderField("Content-Type") ?: ""
        if (PortalDetect.isHtmlContentType(contentType)) {
            c.disconnect()
            // v4.4 (audit round 29): the shared portal signal —
            // on an unvalidated network the service parks this
            // as a WiFi-login wait; on a validated one it fails
            // with the web-page wording below.
            throw PortalBlockedException(
                "这个链接打开的是网页，不是文件直链。请用「嗅探网页」或内置浏览器找到真正的下载地址。"
            )
        }
        val cd = c.getHeaderField("Content-Disposition")
        if (task.fileName.isBlank()) {
            task.fileName = fileNameFrom(cd, task.url)
        }
        // Validators for the resume check (audit A4).
        c.getHeaderField("ETag")?.let { task.etag = it }
        c.getHeaderField("Last-Modified")?.let { task.lastModified = it }
        when (code) {
            HttpURLConnection.HTTP_PARTIAL -> {
                val range = c.getHeaderField("Content-Range") ?: ""
                val total = contentRangeTotal(range)
                task.totalSize = total
                // v4.1 audit round 26: a 206 whose range does not
                // START at byte 0 (missing / garbled / shifted
                // Content-Range) is not evidence the server can
                // serve arbitrary ranges at all — its total may
                // still bind the single-stream path's own checks,
                // but the segmented path must not trust it.
                if (total < 0 || contentRangeStart(range) != 0L) {
                    task.singleThreadOnly = true
                }
                // v2.2: the body is the single probed byte — drain
                // it so the first chunk request reuses this warm
                // connection instead of paying a new handshake.
                releaseConnection(c)
            }
            HttpURLConnection.HTTP_OK -> {
                task.totalSize = c.contentLengthLong
                task.singleThreadOnly = true
                task.note = "服务器不支持分片下载（Range），已用单线程下载，无法加速。"
                // The body is the whole file; not drainable.
                c.disconnect()
            }
            else -> {
                c.disconnect()
                throw HttpFailureException(code)
            }
        }
        onSave()
    }

    /**
     * Audit A4 + v1.8: before resuming a partially downloaded file,
     * ask the server whether the file is still the same one. If a
     * stored validator (ETag / Last-Modified) or the total size
     * disagrees, the old partial bytes belong to a different file
     * version — drop the chunk state and start the byte ranges over
     * (the .part file itself is truncated by the segmented run that
     * follows). When the server offers no validators either time,
     * resume as-is.
     *
     * v1.8: this is now the resume path's ONLY pre-flight request
     * (run() calls it instead of probe() when the size is known),
     * and the .part target opens concurrently with it — one round
     * trip saved on every resume versus the old probe-then-validate
     * arrangement.
     */
    private fun revalidateResume() {
        val resuming = task.chunkDone.any { it } ||
            (task.singleThreadOnly && task.downloaded > 0)
        if (!resuming) return
        if (task.etag.isBlank() && task.lastModified.isBlank()) return
        val c = runCatching {
            openFollowingRedirects(task.url) { it.setRequestProperty("Range", "bytes=0-0") }
        }.getOrNull() ?: return
        try {
            // v4.4 (audit round 29): a portal answers this
            // revalidation with its login page too. The blank
            // validators would otherwise change nothing and the
            // run would walk on into the single-stream /
            // segmented paths — let the shared detector speak
            // here as well (those paths still re-check).
            if (PortalDetect.isHtmlContentType(c.getHeaderField("Content-Type"))) {
                throw PortalBlockedException()
            }
            if (c.responseCode != HttpURLConnection.HTTP_PARTIAL &&
                c.responseCode != HttpURLConnection.HTTP_OK
            ) return
            val newEtag = c.getHeaderField("ETag") ?: ""
            val newModified = c.getHeaderField("Last-Modified") ?: ""
            val range = c.getHeaderField("Content-Range") ?: ""
            val newTotal = range.substringAfter('/', "").toLongOrNull() ?: -1
            val validatorChanged =
                (task.etag.isNotBlank() && newEtag.isNotBlank() && task.etag != newEtag) ||
                    (task.lastModified.isNotBlank() && newModified.isNotBlank() &&
                        task.lastModified != newModified)
            val sizeChanged = newTotal > 0 && task.totalSize > 0 && newTotal != task.totalSize
            if (validatorChanged || sizeChanged) {
                task.chunkDone = BooleanArray(0)
                task.chunkSize = 0
                // v4.2: the fine zone belongs to the old grid —
                // it must never be applied to the next one.
                task.fineChunkSize = 0
                task.fineChunkCount = 0
                // v4.6: the discarded bytes take their per-source
                // accounting with them — the map describes the
                // current file's bytes only (see TaskRecord).
                synchronized(task.sourceBytes) { task.sourceBytes.clear() }
                task.downloaded = 0
                downloadedBytes.set(0)
                if (newTotal > 0) task.totalSize = newTotal
                task.etag = newEtag
                task.lastModified = newModified
                task.note = "服务器上的文件已更新，旧进度作废，已重新开始下载。"
                onSave()
            }
        } finally {
            // v2.2: a 206 reply carries the same 1-byte body as
            // the probe — release it warm; anything else (a 200
            // body is the whole file) is disconnected as before.
            if (c.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                releaseConnection(c)
            } else {
                c.disconnect()
            }
        }
    }

    // ---------- segmented path ----------

    private fun runSegmented(): TaskStatus {
        val total = task.totalSize
        // v3.2 audit round 18: the bitmap is only half the resume
        // state — the other half is the .part file itself. This
        // path pre-sizes the part to the full length (see the
        // truncate below), so a part that measures SHORTER than
        // the total while chunks are marked done has lost bytes
        // behind our back (content deleted / truncated in place
        // under the same Uri — the vanished-Uri case is already
        // reset inside obtainTarget). Trusting the bitmap would
        // seal a file whose "done" regions are zeros. Reset and
        // start the byte ranges over; an unmeasurable part never
        // condemns (see PartIntegrity).
        if (!PartIntegrity.segmentedTrustworthy(
                hasProgress = task.chunkDone.any { it },
                partSize = runCatching { target.size() }.getOrDefault(-1),
                totalSize = total,
            )
        ) {
            task.chunkDone = BooleanArray(0)
            task.chunkSize = 0
            // v4.2: same rule as the validator reset above — a
            // discarded grid takes its fine zone with it.
            task.fineChunkSize = 0
            task.fineChunkCount = 0
            // v4.6: same rule for the per-source accounting —
            // the lost bytes are no longer the file's bytes.
            synchronized(task.sourceBytes) { task.sourceBytes.clear() }
            task.downloaded = 0
            downloadedBytes.set(0)
            task.note = "半成品文件对不上（字节丢了），旧进度作废，已重新开始下载。"
            onSave()
        }
        if (task.chunkSize <= 0) {
            // The grid is decided HERE, once, and persisted on
            // the task — a resume reuses it verbatim (the resume
            // invariant). v4.2: the coarse size still comes from
            // chunkSizeFor; gridFor may carve a fine zone out of
            // the head. The fine zone is sized for the burst
            // opening (StartPlan.BURST_START connections): this
            // branch runs BEFORE the scaler exists, so the real
            // start count is unknowable here — a slightly small
            // fine zone is harmless (it only shifts where the
            // granularity changes, never correctness).
            val coarse = ChunkPlanner.chunkSizeFor(total, 16)
            val grid = ChunkPlanner.gridFor(total, coarse, StartPlan.BURST_START)
            task.chunkSize = grid.coarseSize
            task.fineChunkSize = grid.fineSize
            task.fineChunkCount = grid.fineCount
            task.chunkDone = BooleanArray(ChunkPlanner.chunkCount(grid))
        }
        val count = task.chunkDone.size
        // v1.6 multi-source: validate the mirrors ONCE per run
        // (same total size + a mid-file byte sample) before any
        // worker may take bytes from them.
        activeSources = validateSources()
        // Resume cursor: first unfinished chunk.
        var firstOpen = task.chunkDone.indexOfFirst { !it }
        if (firstOpen < 0) firstOpen = count
        nextChunk.set(firstOpen)
        downloadedBytes.set(
            ChunkPlanner.downloadedOf(
                ChunkPlanner.gridOf(total, task.chunkSize, task.fineChunkSize, task.fineChunkCount),
                task.chunkDone,
            )
        )
        task.downloaded = downloadedBytes.get()

        target.truncate(total)

        // Speed sampler + auto thread probing controller + stall eviction.
        val stopSampler = AtomicInteger(0)
        val sampler = Thread {
            var lastBytes = downloadedBytes.get()
            var lastTime = System.currentTimeMillis()
            var ticks = 0
            // v1.5: fixed-period ticks. The old sleep(1000)+work loop
            // drifted long, so the chart's "90 samples = 90 seconds"
            // axis quietly lied and the displayed speed lagged the
            // samples. Now each tick is scheduled against a deadline,
            // keeping samples, chart and the big speed number on the
            // exact same 1 s grid (single source: this sampler's EMA).
            var nextTick = System.currentTimeMillis() + 1000
            while (stopSampler.get() == 0 && !stopped()) {
                val waitMs = nextTick - System.currentTimeMillis()
                try {
                    Thread.sleep(waitMs.coerceIn(50, 1000))
                } catch (e: InterruptedException) { break }
                nextTick += 1000
                if (nextTick < System.currentTimeMillis()) {
                    nextTick = System.currentTimeMillis() + 1000
                }
                if (!networkGuard()) {
                    netBlocked = true
                    break
                }
                if (!trafficGuard()) {
                    trafficBlocked = true
                    break
                }
                val now = System.currentTimeMillis()
                val bytes = downloadedBytes.get()
                val dt = max(1L, now - lastTime)
                val speed = (bytes - lastBytes) * 1000 / dt
                task.speedBps = if (task.speedBps == 0L) speed else (task.speedBps * 3 + speed) / 4
                recordSpeed(task.speedBps)
                // v4.6 (server profile): the peak worker count
                // this task ever ran at once. The sampler is the
                // one thread that already looks at the pool every
                // tick — reading the atomic here costs no lock and
                // no extra polling, and the write is a plain max
                // on a sampler-owned field.
                val aw = activeWorkers.get()
                if (aw > task.peakConns) task.peakConns = aw
                task.downloaded = bytes
                ticks++
                // v3.5: publish the per-connection snapshot
                // the details view reads (the connection list
                // and the chunk map's ACTIVE cells). Same 1 s
                // tick — no new polling anywhere: each live
                // part's speed is its position delta since the
                // last tick, computed here where the sampler
                // already walks the active set.
                synchronized(activeLock) {
                    task.connInfos = active.map { ac ->
                        val sp = (ac.pos - ac.lastSamplePos) * 1000 / dt
                        ac.lastSamplePos = ac.pos
                        ConnInfo(
                            chunkIndex = ac.index,
                            downloaded = (ac.pos - ac.start).coerceAtLeast(0),
                            total = (ac.end - ac.start).coerceAtLeast(0),
                            speedBps = sp.coerceAtLeast(0),
                        )
                    }.sortedBy { it.chunkIndex }
                }
                // Evict stalled connections: a part that has not
                // advanced while the task as a whole IS moving
                // gets its connection dropped; the worker retries
                // the part on a fresh connection, resuming from
                // the part's current offset.
                // v4.6: the patience is speed-aware (StallPolicy):
                // it shrinks from the old flat 12 s toward a 4 s
                // floor as the task's aggregate speed rises, and
                // the verdict is judged on task.speedBps — the
                // EMA this same tick just updated above (and the
                // number ThreadScaler reads), NOT the tick's
                // instantaneous byte delta, whose single-tick
                // spikes would thrash slow-starting connections.
                // StallPolicy also makes the old gate's implied
                // rules explicit: a lone active connection is
                // never evicted, and a task that is not moving at
                // all evicts nobody.
                run {
                    // v4.0: collect the stalled connections inside
                    // the lock but disconnect AFTER releasing it —
                    // disconnect() is a blocking socket operation
                    // and activeLock is the engine's hottest lock
                    // (every worker passes it per chunk), so doing
                    // it in-lock stalled all workers behind one
                    // slow socket teardown. The lastAdvanceMs
                    // stamp stays in-lock so a part is never
                    // evicted twice for the same stall.
                    val stalled = ArrayList<HttpURLConnection>()
                    synchronized(activeLock) {
                        val activeCount = active.size
                        for (ac in active) {
                            if (StallPolicy.shouldEvict(
                                    task.speedBps,
                                    now - ac.lastAdvanceMs,
                                    activeCount,
                                )
                            ) {
                                ac.lastAdvanceMs = now // don't spam
                                ac.connection?.let { stalled.add(it) }
                            }
                        }
                    }
                    for (conn in stalled) runCatching { conn.disconnect() }
                }
                // Every 2 s the ThreadScaler re-decides the worker
                // count (v2.3): opening ladder first, then steady
                // grow/shrink with verdicts and learned bounds.
                if (ticks % 2 == 0) {
                    val sc = scaler
                    if (sc != null) {
                        // v3.9: re-read the global-cap allowance
                        // on the same tick. While the CAP is what
                        // binds (allowance below the scaler's
                        // target), the scaler is NOT evaluated:
                        // its verdicts judge speed deltas, and a
                        // delta the cap caused — another task
                        // starting or finishing — would be misread
                        // as this host speeding up or slowing
                        // down. The target simply holds until the
                        // cap lifts, then normal scaling resumes
                        // from it; surplus workers shed through
                        // the workerLoop check, which reads the
                        // same allowance.
                        connAllowance = connectionAllowance()
                        val capped = connAllowance < sc.target
                        task.connCapHeld = capped
                        if (capped) {
                            task.threads = sc.target
                            // Still top the pool up TO the
                            // allowance — being capped is a
                            // ceiling, not a freeze at whatever
                            // count the run happened to start
                            // with.
                            val chunksLeft = nextChunk.get() < count
                            val deficit = connAllowance - activeWorkers.get()
                            if (deficit > 0 && chunksLeft) {
                                repeat(deficit) { spawnWorker(count) }
                            }
                        } else {
                            val wasProbing = sc.probing
                            val chunksLeft = nextChunk.get() < count
                            val anyWork = chunksLeft ||
                                synchronized(activeLock) { active.isNotEmpty() }
                            val newTarget = sc.onEval(task.speedBps, chunksLeft, anyWork)
                            // The ladder just settled at a low count:
                            // more threads stopped helping (the honest
                            // per-IP-throttling note, as before v2.3).
                            if (wasProbing && !sc.probing && chunksLeft &&
                                newTarget <= 8 && task.note.isBlank()
                            ) {
                                task.note = "加连接后速度不再提升，服务器可能按 IP 总限速，已用最优连接数。"
                            }
                            // task.threads IS the card's live thread
                            // display and, at DONE, the value HostMemory
                            // remembers — keep it equal to the target.
                            task.threads = newTarget
                            // A higher target spawns at once; a lower
                            // one is honoured by surplus workers exiting
                            // at their next chunk boundary (workerLoop).
                            val deficit = newTarget - activeWorkers.get()
                            if (deficit > 0 && chunksLeft) {
                                repeat(deficit) { spawnWorker(count) }
                            }
                        }
                    }
                    onSave()
                }
                lastBytes = bytes
                lastTime = now
                onProgress(task)
            }
        }
        sampler.isDaemon = true
        sampler.start()

        val resuming = firstOpen > 0 || downloadedBytes.get() > 0
        // v2.8: the remembered rate-limit ceiling for this host
        // (0 = none). It bounds EVERYTHING below — the hint
        // start, a resume's restart count and the scaler's max —
        // because a ceiling the ladder could climb past would be
        // no ceiling at all. Only values in HostMemory's own
        // legal band are honoured (MIN_CAP..MAX_THREADS), so a
        // corrupt value can never squeeze the scaler's range
        // shut. (StartPlan applies the same band rule to its own
        // bound; this validated copy still drives the scaler's
        // max and the note below.)
        val cap = if (initialThreadsCap in HostMemory.MIN_CAP..MAX_THREADS) {
            initialThreadsCap
        } else {
            0
        }
        // v4.2: the start count is decided by the pure StartPlan
        // (see StartPlan for the full rule table). The v1.5 hint
        // gate stays HERE: the caller passes the remembered
        // optimum unconditionally, and only a FRESH start may use
        // it — a resume never sees the hint today. StartPlan's
        // hint branch itself does not check `resuming`, so if a
        // future caller passes a hint for a resume it will be
        // honoured there too.
        val plan = StartPlan.decide(
            hint = if (!resuming) initialThreadsHint else 0,
            cap = initialThreadsCap,
            resuming = resuming,
            persistedThreads = task.threads,
        )
        // The hint actually used (after StartPlan's bound), for
        // the note below: a valid fresh hint always lands in the
        // plan, pressed down by the cap when one is remembered.
        val hint = if (!resuming && initialThreadsHint in 2..MAX_THREADS) {
            if (cap > 0) minOf(initialThreadsHint, cap) else initialThreadsHint
        } else {
            0
        }
        // v2.3: the scaler owns the count from here. A hint start
        // skips the ladder (steady state from the first tick); any
        // other start ladders first — a resume included, since the
        // network may have changed since the count was persisted.
        // v2.8: the scaler's max IS the cap when one is remembered.
        val sc = ThreadScaler(
            plan.start,
            probing = plan.probing,
            maxThreads = if (cap > 0) cap else MAX_THREADS,
        )
        scaler = sc
        if (hint > 0 && task.note.isBlank()) {
            task.note = "这个服务器上次的最佳连接数是 $hint，已直接用它下载，跳过试探。"
        }
        if (cap > 0 && cap < MAX_THREADS) {
            appendNote("这个服务器之前在多线程下载时限流过，这次最多用 $cap 条连接，免得又被拒绝。")
        }
        task.threads = sc.target
        // v3.9: the global connection cap gates the INITIAL
        // spawn too — read the allowance before the first
        // worker exists (the sampler re-reads it every scaler
        // tick from here on).
        connAllowance = connectionAllowance()
        repeat(minOf(sc.target, connAllowance)) { spawnWorker(count) }

        // Wait until all workers exit (done or paused). v2.2
        // power pass: JOIN the worker threads instead of polling
        // the counter every 200 ms — a join parks this thread
        // until a worker actually finishes, so a long download
        // costs zero wakeups here. Workers the prober spawns
        // mid-run join the same list; the loop re-scans until
        // none are alive.
        while (activeWorkers.get() > 0) {
            val t = synchronized(workerThreads) {
                workerThreads.lastOrNull { it.isAlive }
            }
            if (t != null) {
                try { t.join() } catch (e: InterruptedException) { break }
            } else {
                // Counter says workers exist but none is in the
                // list (cannot happen — spawn registers before
                // starting); never busy-spin on it regardless.
                try { Thread.sleep(50) } catch (e: InterruptedException) { break }
            }
        }
        stopSampler.set(1)
        sampler.join(1500)

        task.speedBps = 0
        task.downloaded = downloadedBytes.get()
        val doneCount = task.chunkDone.count { it }
        if (doneCount == count) {
            // v1.8: one fsync seals the file before it may call
            // itself DONE (see DownloadTarget.sync). A failed sync
            // must not fail a byte-complete download — the bytes
            // are in the OS's hands either way.
            runCatching { target.sync() }
        }
        return when {
            doneCount == count -> {
                task.status = TaskStatus.DONE
                task.status
            }
            trafficBlocked -> {
                task.note = Notes.trafficGuardSwitched(task.totalSize)
                task.status = TaskStatus.PAUSED
                task.status
            }
            netBlocked -> {
                task.note = Notes.wifiOnlyPaused()
                task.status = TaskStatus.PAUSED
                task.status
            }
            paused -> {
                task.status = TaskStatus.PAUSED
                task.status
            }
            else -> {
                task.status = TaskStatus.FAILED
                if (task.error.isBlank()) task.error = "部分分片下载失败"
                task.status
            }
        }
    }

    // ---------- multi-source validation (v1.6) ----------

    /**
     * Check every user-supplied mirror against the primary file:
     * same total size AND a matching mid-file byte sample. Mirrors
     * that disagree are refused — loudly, in the task note — because
     * mixing two different files' chunks would corrupt the result.
     * Returns the usable source list (primary first).
     */
    private fun validateSources(): List<String> {
        val extras = task.sources.map { it.trim() }
            .filter { it.isNotBlank() && it != task.url }
            .distinct()
        if (extras.isEmpty() || task.totalSize <= 0) return listOf(task.url)
        val sampleOffset = task.totalSize / 2
        val sampleLen = minOf(64L * 1024, task.totalSize - sampleOffset).toInt()
        // v1.8: the checks run CONCURRENTLY — the primary's sample
        // and every mirror's (total, then sample) fetch overlap on
        // a small pool. The old code paid two SEQUENTIAL round
        // trips per mirror before the first chunk could start
        // (3 mirrors ≈ 7 round trips); now the whole validation
        // costs about two round trips of wall time.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(4, extras.size + 1)
        ) { r -> Thread(r).apply { isDaemon = true } }
        val valid = mutableListOf(task.url)
        var rejected = 0
        try {
            val primarySampleF = pool.submit(java.util.concurrent.Callable {
                if (sampleLen > 0) fetchSample(task.url, sampleOffset, sampleLen) else null
            })
            val mirrorFs = extras.map { src ->
                src to pool.submit(java.util.concurrent.Callable {
                    val total = probeTotal(src)
                    val sample = if (total == task.totalSize && sampleLen > 0) {
                        fetchSample(src, sampleOffset, sampleLen)
                    } else null
                    total to sample
                })
            }
            val primarySample = runCatching { primarySampleF.get() }.getOrNull()
            for ((src, f) in mirrorFs) {
                val (total, sample) = runCatching { f.get() }
                    .getOrDefault(-1L to null)
                if (SourceCheck.isSameFile(task.totalSize, total, primarySample, sample)) {
                    valid.add(src)
                } else {
                    rejected++
                }
            }
        } finally {
            pool.shutdown()
        }
        if (rejected > 0) {
            appendNote(
                "有 $rejected 个镜像源和主地址的文件对不上（大小或内容不一致），" +
                    "已拒绝使用，只用对得上的源下载。"
            )
        }
        if (valid.size > 1 && !task.note.contains("多源下载")) {
            appendNote("多源下载：${valid.size} 个地址一起下同一个文件。")
        }
        if (rejected > 0 || valid.size > 1) onSave()
        return valid
    }

    private fun appendNote(text: String) {
        task.note = if (task.note.isBlank()) text else task.note + " " + text
    }

    /** Total size of [url] via a 1-byte Range probe; -1 if unknown. */
    private fun probeTotal(url: String): Long {
        val c = runCatching {
            openFollowingRedirects(url) { it.setRequestProperty("Range", "bytes=0-0") }
        }.getOrNull() ?: return -1
        try {
            if (c.responseCode != HttpURLConnection.HTTP_PARTIAL) return -1
            val range = c.getHeaderField("Content-Range") ?: return -1
            // v4.1 audit round 26: a total quoted on a range that
            // does not start at byte 0 (or carries no parseable
            // range at all) is not this file's total — refuse it
            // (-1 rejects the mirror in validateSources, the safe
            // direction) instead of binding validation to it.
            if (contentRangeStart(range) != 0L) return -1
            return contentRangeTotal(range)
        } finally {
            // v4.1 (step A): release warm like probe() does — a 206
            // here carries the same 1-byte body, and the old
            // unconditional disconnect() threw that socket out of
            // the keep-alive pool on EVERY mirror validation. Any
            // other reply (200 whole-file body, error) fails the
            // drain inside releaseConnection and is disconnected
            // there, exactly as before.
            releaseConnection(c)
        }
    }

    /** Up to [len] bytes of [url] starting at [offset]; null on any failure. */
    private fun fetchSample(url: String, offset: Long, len: Int): ByteArray? {
        val c = runCatching {
            openFollowingRedirects(url) {
                it.setRequestProperty("Range", "bytes=$offset-${offset + len - 1}")
            }
        }.getOrNull() ?: return null
        // v2.2: the sample body is read to its end and the stream
        // closed by use{} — that already returns the connection
        // to the keep-alive pool, so only failure paths (body not
        // fully consumed) still disconnect.
        var consumed = false
        try {
            if (c.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            val data = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < len) {
                    val n = input.read(buf, 0, minOf(buf.size, len - total))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
            consumed = true
            return data
        } catch (e: Exception) {
            return null
        } finally {
            if (!consumed) runCatching { c.disconnect() }
        }
    }

    /**
     * Which source serves this chunk attempt: chunk indexes rotate
     * across the validated sources, and a retry moves to the NEXT
     * source — so a source that dies mid-download hands its chunks
     * over to the others automatically.
     */
    private fun sourceFor(ac: ActiveChunk, attempt: Int): String {
        val srcs = activeSources
        if (srcs.size <= 1) return task.url
        return srcs[(ac.index + attempt - 1).mod(srcs.size)]
    }

    /** Worker threads of this run still alive or not yet
     *  reaped, so the waiter can join them (v2.2) instead of
     *  polling a counter. v2.7 audit round 13: a worker now
     *  REMOVES itself when it exits — the list used to keep
     *  every thread ever spawned, so a multi-hour download
     *  whose scaler grew and shrank the pool for hours
     *  accumulated dead Thread objects for the whole run.
     *  The waiter only ever looks for a LIVE thread, so
     *  self-removal cannot disturb the join. */
    private val workerThreads = mutableListOf<Thread>()

    private fun spawnWorker(chunkCount: Int) {
        activeWorkers.incrementAndGet()
        val t = Thread {
            try {
                workerLoop(chunkCount)
            } finally {
                activeWorkers.decrementAndGet()
                synchronized(workerThreads) {
                    workerThreads.remove(Thread.currentThread())
                }
            }
        }
        t.isDaemon = true
        synchronized(workerThreads) { workerThreads.add(t) }
        t.start()
    }

    /** v2.2 endgame: tail pieces already cut by a split plan,
     *  waiting for an idle worker to pick them up. Guarded by
     *  [activeLock]; pieces are also in [active] from the moment
     *  they are cut, so later plans can see (and re-split) them. */
    private val pendingTails = ArrayDeque<ActiveChunk>()

    /** Take one already-cut endgame tail piece, if any wait. */
    private fun pollPendingTail(): ActiveChunk? =
        synchronized(activeLock) {
            if (pendingTails.isEmpty()) null else pendingTails.removeFirst()
        }

    private fun workerLoop(chunkCount: Int) {
        // v4.2: this run's grid, rebuilt from the task's persisted
        // fields (fixed at creation; never re-decided mid-run).
        val grid = ChunkPlanner.gridOf(
            task.totalSize, task.chunkSize, task.fineChunkSize, task.fineChunkCount
        )
        while (!stopped()) {
            // v2.3 dynamic scaling: when the scaler has lowered the
            // target below the live worker count, surplus workers
            // leave HERE — between chunks, never mid-chunk — so a
            // shrink can never tear a part in half or orphan its
            // bytes. Two workers racing the check can dip the count
            // one below target; the sampler's top-up corrects that
            // within one evaluation (2 s).
            // v3.3 audit round 19 (combination): the top-up only
            // respawns while FRESH chunks remain, so in the
            // endgame a surplus exit taken while cut tail pieces
            // were still queued could strand them for good — the
            // run would end FAILED with pieces nobody ever came
            // back for. A worker is surplus only when the tail
            // queue is empty (see Endgame.shouldExitSurplus);
            // otherwise it drains a tail first.
            val sc = scaler
            // v3.9: the surplus bar is the LOWER of the scaler's
            // target and the live global-cap allowance — a cap
            // that dropped mid-run (another task started) sheds
            // workers at chunk boundaries exactly like a scaler
            // shrink does, never mid-chunk.
            val effTarget = if (sc != null) minOf(sc.target, connAllowance) else 0
            if (sc != null && activeWorkers.get() > effTarget) {
                val tails = synchronized(activeLock) { pendingTails.size }
                if (Endgame.shouldExitSurplus(activeWorkers.get(), effTarget, tails)) {
                    return
                }
                val tail = pollPendingTail()
                if (tail != null) {
                    downloadActive(tail)
                    continue
                }
            }
            var index = nextChunk.getAndIncrement()
            if (index >= chunkCount) {
                // No fresh chunks: endgame. Take a pre-cut tail if
                // one is waiting, else plan a fresh split of the
                // largest in-flight remainder (see Endgame).
                val stolen = stealTail() ?: return
                downloadActive(stolen)
                continue
            }
            if (index < 0 || index >= chunkCount) return
            if (task.chunkDone[index]) continue
            // v4.7 step B: adjacent-chunk merging on the FRESH
            // claim path only (tails and stolen pieces above keep
            // their exact spans). mergeCount decides how many
            // following chunks this claim MAY cover; each one is
            // then actually taken with a CAS on nextChunk — a lost
            // race (another worker advanced the cursor) or a chunk
            // that turned done stops the extension, so an index is
            // never claimed twice and never skipped: an index the
            // CAS failed on stays ahead of the cursor for its own
            // claimant. The result is ONE ActiveChunk spanning
            // [start of index, end of lastIndex) streamed by ONE
            // Range request (downloadRange needs no change);
            // per-index completion stays deferred to
            // downloadActive, which drains every index the final
            // span overlaps.
            val target = ChunkPlanner.mergeCount(grid, index, chunkCount) { idx ->
                synchronized(task.chunkDone) { task.chunkDone[idx] }
            }
            var lastIndex = index
            while (lastIndex - index + 1 < target) {
                val nxt = lastIndex + 1
                if (nxt >= chunkCount) break
                if (synchronized(task.chunkDone) { task.chunkDone[nxt] }) break
                if (!nextChunk.compareAndSet(nxt, nxt + 1)) break
                lastIndex = nxt
            }
            val firstRange = ChunkPlanner.rangeOf(grid, index)
            val lastRange = ChunkPlanner.rangeOf(grid, lastIndex)
            val ac = ActiveChunk(index, firstRange.first, lastRange.last + 1)
            synchronized(activeLock) { active.add(ac) }
            downloadActive(ac)
        }
    }

    /**
     * Endgame split (v2.2): cut the largest in-flight remainder
     * into roughly equal pieces — one stays with its owner, the
     * others go to idle workers. The decision itself is the pure
     * [Endgame.planSplit]; this method only applies it to the
     * live chunks. One piece is returned for the calling worker,
     * the rest wait in [pendingTails]. Returns null when nothing
     * is worth splitting (the caller then exits).
     *
     * The plan is computed from a snapshot, then RE-planned
     * against the chosen chunk's live position before cutting:
     * the owner keeps writing while we decide, and a cut placed
     * behind its current position would make two workers write
     * (and double-count) the same bytes.
     */
    private fun stealTail(): ActiveChunk? {
        pollPendingTail()?.let { return it }
        synchronized(activeLock) {
            val idle = (activeWorkers.get() - active.size).coerceAtLeast(1)
            val snapshot = active.map { Endgame.Range(it.index, it.pos, it.end) }
            val plan = Endgame.planSplit(snapshot, idle, MIN_SPLIT) ?: return null
            val owner = active.firstOrNull { it.index == plan.index } ?: return null
            val live = Endgame.planSplit(
                listOf(Endgame.Range(owner.index, owner.pos, owner.end)),
                idle, MIN_SPLIT,
            ) ?: return null
            val origEnd = owner.end
            val bounds = live.cuts + origEnd
            // v4.7 step B (coordinator-approved fix): rebalance
            // chunkParts for EVERY index the owner's span touches,
            // in this same locked section, BEFORE any mutation.
            // The owner's shrink surrenders the indexes past the
            // first cut to the tails; each surrendered index loses
            // the owner's share (-1) and gains one per tail
            // covering it, each kept index gains only its covering
            // tails — see ChunkPlanner.splitRebalancedCounts for
            // the invariant and why the result can never be <= 0
            // for a correct partition (the tails partition the
            // surrendered range, so every surrendered index is
            // covered by >= 1 tail). If it ever were <= 0 the
            // partition logic itself would be broken: abort the
            // split untouched rather than store a count that could
            // mark an index done from a split (done-marking is
            // exclusive to downloadActive's completion drain).
            // The formula is span-based, not index-based, so it
            // applies recursively when a cross-boundary TAIL is
            // itself split later, exactly as it does here.
            // Single-chunk owner: the transfer set is empty (the
            // cut is always inside its one index), every tail
            // covers that index, and the delta is exactly
            // +tails.size — word-for-word the pre-v4.7 behaviour.
            val grid = ChunkPlanner.gridOf(
                task.totalSize, task.chunkSize, task.fineChunkSize, task.fineChunkCount
            )
            val origOverlap = ChunkPlanner.indexesOverlapping(grid, owner.start, origEnd)
            val newOverlap = ChunkPlanner.indexesOverlapping(grid, owner.start, live.cuts.first())
            val tailOverlaps = (0 until bounds.size - 1).map { i ->
                ChunkPlanner.indexesOverlapping(grid, bounds[i], bounds[i + 1])
            }
            val rebalanced = ChunkPlanner.splitRebalancedCounts(
                origOverlap, newOverlap, tailOverlaps
            ) { idx -> chunkParts[idx] ?: 1 }
            if (rebalanced.values.any { it <= 0 }) return null
            owner.end = live.cuts.first()
            val tails = ArrayList<ActiveChunk>(bounds.size - 1)
            for (i in 0 until bounds.size - 1) {
                tails.add(ActiveChunk(owner.index, bounds[i], bounds[i + 1]))
            }
            for ((idx, newCount) in rebalanced) chunkParts[idx] = newCount
            active.addAll(tails)
            for (t in tails.drop(1)) pendingTails.addLast(t)
            return tails.first()
        }
    }

    private fun downloadActive(ac: ActiveChunk) {
        var attempt = 0
        var ok = false
        while (attempt < 3 && !stopped() && !ok) {
            attempt++
            if (attempt > 1) task.retryCount++ // v1.4 stats: chunk-level retries
            try {
                // v4.6 (server profile): per-source byte
                // accounting. The ledger is the part's pos
                // advance over THIS attempt: pos moves only
                // after target.writeAt has taken the bytes
                // (downloadRange's read loop), so the delta is
                // exactly the bytes that landed in the file —
                // bytes an attempt read but discarded (a failed
                // handshake, a rejected range, a stall eviction
                // before any write) never move pos and never
                // count, while bytes written before a failure
                // DO count because the retry resumes at pos and
                // keeps them. No double count and no leak across
                // the endgame split: a split only shrinks the
                // owner's end (its pos — and this attempt's
                // delta — are untouched), and each cut tail is
                // its own ActiveChunk whose attempts account
                // their own disjoint [pos, end) range.
                val src = sourceFor(ac, attempt)
                val startPos = ac.pos
                try {
                    downloadRange(ac, src)
                } finally {
                    val delta = ac.pos - startPos
                    if (delta > 0) {
                        synchronized(task.sourceBytes) {
                            task.sourceBytes[src] =
                                (task.sourceBytes[src] ?: 0) + delta
                        }
                    }
                }
                ok = ac.pos >= ac.end
            } catch (e: Exception) {
                if (isStorageFull(e)) {
                    // No point retrying into a full disk (audit A10).
                    task.error = Notes.storageFull()
                    task.failureKind = FailureKind.STORAGE_FULL
                    break
                }
                if (isStorageGone(e)) {
                    // Nor into a pulled card / read-only volume:
                    // every chunk would fail the same way.
                    task.error = Notes.storageDeviceGone()
                    task.failureKind = FailureKind.STORAGE_GONE
                    break
                }
                if (attempt >= 3) {
                    // v4.4 (audit round 29): the segmented path
                    // composes task.error ONLY from humanized /
                    // Notes text, so the raw verdicts for the
                    // service's parks are recorded HERE, where
                    // the original exception still exists.
                    // v4.5 (audit round 30): the structured
                    // failure verdict joins them, same site.
                    recordFailureVerdict(e)
                    task.error = Errors.human(e)
                }
                try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) { }
            }
        }
        synchronized(activeLock) { active.remove(ac) }
        ac.connection = null
        if (ok && ac.index >= 0) {
            // v4.7 step B: completion drains EVERY index this
            // part's FINAL span [ac.start, ac.end) overlaps — the
            // grid rebuilt from the task fields exactly as
            // workerLoop builds it. ac.end is read NOW, after any
            // endgame split shrank it, so a split owner drains only
            // the indexes it still covers (the tails drain theirs).
            // Each overlapped index loses exactly one — this part
            // was one of the live parts covering it (the core
            // invariant on chunkParts) — and an index whose count
            // reaches 0 is marked done; this drain is the ONLY
            // place done is ever marked. For a single-chunk part
            // the overlapped set is exactly [ac.index], so the
            // arithmetic is word-for-word the pre-v4.7 behaviour.
            val grid = ChunkPlanner.gridOf(
                task.totalSize, task.chunkSize, task.fineChunkSize, task.fineChunkCount
            )
            val overlapped = ChunkPlanner.indexesOverlapping(grid, ac.start, ac.end)
            synchronized(activeLock) {
                for (idx in overlapped) {
                    val left = (chunkParts[idx] ?: 1) - 1
                    if (left <= 0) {
                        chunkParts.remove(idx)
                        synchronized(task.chunkDone) { task.chunkDone[idx] = true }
                    } else {
                        chunkParts[idx] = left
                    }
                }
            }
        }
    }

    private fun downloadRange(ac: ActiveChunk, sourceUrl: String = task.url) {
        // v1.3: the request starts at the part's CURRENT offset, so a
        // retry (or a stall eviction) keeps every byte already written
        // instead of re-downloading the whole part.
        // v1.6: the bytes may come from any validated mirror —
        // SourceCheck proved they are the same file, and the
        // Content-Range start check below still guards every hop.
        val from = ac.pos
        val to = ac.end - 1
        if (from > to) return
        val c = openFollowingRedirects(sourceUrl) {
            it.setRequestProperty("Range", "bytes=$from-$to")
        }
        ac.connection = c
        val code = c.responseCode
        // v4.4 (audit round 29): a portal answers a Range
        // request with a redirect to its login page, which the
        // follower lands as a 200 HTML — the status /
        // Content-Range gauntlet below already guaranteed
        // those bytes are NEVER written; name the cause
        // properly so the service can park instead of failing.
        if (PortalDetect.isHtmlContentType(c.getHeaderField("Content-Type"))) {
            c.disconnect()
            throw PortalBlockedException()
        }
        // v2.8: rate-limit evidence — PRIMARY source only (the
        // ceiling is remembered per host of the task URL; a
        // mirror is a different host with its own behaviour).
        // task.threads mirrors the scaler's live target, i.e.
        // how many connections were running when this one was
        // refused.
        if (sourceUrl == task.url &&
            (code == 429 || code == HttpURLConnection.HTTP_FORBIDDEN)
        ) {
            rejections.add(
                RateLimit.Rejection(code, System.currentTimeMillis(), task.threads)
            )
        }
        if (code == HttpURLConnection.HTTP_OK) {
            // Server ignored Range mid-download: cannot segment safely.
            c.disconnect()
            throw IllegalStateException("服务器不支持 Range")
        }
        if (code != HttpURLConnection.HTTP_PARTIAL) {
            c.disconnect()
            throw HttpFailureException(code)
        }
        // A redirect chain that lands on a different byte range than
        // asked would corrupt the file; Content-Range must match.
        val cr = c.getHeaderField("Content-Range") ?: ""
        // v4.1 audit round 26: a MISSING or unparsable
        // Content-Range used to SKIP this check entirely (the old
        // inline parse yielded null and null was excused) — a 206
        // carrying no range header at all was trusted to hold the
        // requested bytes blind. contentRangeStart returns -1 for
        // those, and -1 != from condemns, exactly how the
        // single-stream path has always judged its own 206.
        if (contentRangeStart(cr) != from) {
            c.disconnect()
            throw IllegalStateException("服务器返回的分片位置不对，已重试")
        }
        // v2.4 audit round 10: bind this response to the file the
        // probe validated, not just to the right offset. The
        // Content-Range TOTAL is the size the responding server
        // believes the file has NOW; if it no longer matches the
        // probed total, the file was swapped mid-download and
        // these bytes belong to a different file — refuse them
        // (the retry rotates to the next source, which may still
        // serve the right file). Applies to mirrors too: their
        // totals were validated equal before use.
        val crTotal = contentRangeTotal(cr)
        if (crTotal > 0 && task.totalSize > 0 && crTotal != task.totalSize) {
            c.disconnect()
            throw IllegalStateException("服务器上的文件在下载途中变了（大小对不上），已停下重试")
        }
        // Same binding via a strong ETag — PRIMARY source only.
        // Mirrors are different servers and legitimately mint
        // different ETags for identical bytes; the primary is the
        // server the probe captured the validator from. A failed
        // check fails the task loudly (never silently mixes two
        // versions' bytes); a manual resume then re-validates
        // and, if the file really changed, restarts it cleanly.
        if (sourceUrl == task.url) {
            val respEtag = c.getHeaderField("ETag") ?: ""
            if (etagChanged(task.etag, respEtag)) {
                c.disconnect()
                throw IllegalStateException("服务器上的文件在下载途中变了（校验标记对不上），已停下重试")
            }
        }
        // v2.7 audit round 13 (long-run stability): the close /
        // disconnect used to sit AFTER this loop, so any read()
        // that threw — connection reset, read timeout, a stall
        // eviction racing us — skipped them entirely. The caller
        // retries a chunk up to 3 times and a flaky server can
        // fail hundreds of chunks over a multi-hour download, so
        // the unclosed sockets piled up until GC got round to
        // them: a slow file-descriptor leak on exactly the
        // workload this app exists for. Now the release is in a
        // finally and happens on every path out.
        var input: InputStream? = null
        // v4.0: keep-alive discipline, same as fetchText (v2.2) —
        // a response body read to its EXACT end + stream close
        // returns the socket to the keep-alive pool, so the next
        // chunk reuses it instead of paying a fresh TCP+TLS
        // handshake; a body cut short is NOT reusable and must
        // be disconnected. "Exact end" means the ORIGINAL
        // requested end (to, captured at entry): ac.end can
        // shrink mid-run when an endgame split hands the tail to
        // a new worker, and a stop at that shrunken limit leaves
        // response bytes unread — not consumed, disconnect.
        var consumed = false
        try {
            input = c.inputStream
            // v4.2 audit round 27: size the buffer by the LIVE
            // worker count when it exceeds the target (a scaler
            // shrink leaves surplus workers draining chunks /
            // tails at the new, smaller target — sizing by the
            // target alone would hand each of them the LARGER
            // low-count buffer and break the per-task 4 MB
            // account in bufferSizeFor's doc).
            val buf = ByteArray(
                bufferSizeFor(bufferThreadsFor(task.threads, activeWorkers.get()))
            )
            var pos = from
            while (!stopped()) {
                val limit = ac.end // may shrink when split
                if (pos >= limit) break
                val want = minOf(buf.size.toLong(), limit - pos).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                target.writeAt(pos, buf, 0, n)
                pos += n
                ac.pos = pos
                ac.lastAdvanceMs = System.currentTimeMillis()
                downloadedBytes.addAndGet(n.toLong())
                limiter?.throttle(n)
            }
            // The loop exited with pos at the original end: the
            // whole body was read exactly. A stopped() exit, an
            // exception, an early EOF, or a stop at a split-
            // shrunken limit all leave pos < to + 1.
            consumed = pos >= to + 1
        } finally {
            // The stream close stays unconditional (round-13
            // leak discipline); only the disconnect is now
            // conditional on the body not having been consumed.
            runCatching { input?.close() }
            if (!consumed) runCatching { c.disconnect() }
            if (ac.connection == c) ac.connection = null
        }
    }

    // ---------- HLS (m3u8) path (v1.6) ----------

    /** v4.1 audit round 26: does this Content-Range describe the
     *  whole resource, of which [actual] bytes are in hand? The
     *  parsing is the companion's; the verdict is DeclaredLength's. */
    private fun wholeRange(contentRange: String?, actual: Long): Boolean =
        DeclaredLength.wholeRange(
            contentRangeStart(contentRange), contentRangeTotal(contentRange), actual
        )

    /** Read a small text resource (playlist) with the usual headers. */
    private fun fetchText(url: String, capBytes: Int = 4 * 1024 * 1024): String {
        val c = openFollowingRedirects(url) { }
        // v2.2: a body read to EOF + stream close returns the
        // connection to the keep-alive pool (playlist fetches are
        // immediately followed by segment fetches to the same
        // host); a body cut off at the cap is not reusable.
        var consumed = false
        try {
            if (c.responseCode !in 200..299) {
                throw HttpFailureException(c.responseCode)
            }
            var readTotal = 0L
            val text = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var total = 0
                while (total < capBytes) {
                    val n = input.read(buf, 0, minOf(buf.size, capBytes - total))
                    if (n < 0) {
                        consumed = true
                        break
                    }
                    out.write(buf, 0, n)
                    total += n
                }
                readTotal = total.toLong()
                String(out.toByteArray(), Charsets.UTF_8)
            }
            // v4.4 (audit round 29): a portal's login page is
            // not a playlist — Hls.parse would turn it into an
            // empty Media and the run would fail with the
            // misleading "no segments". The head sniff is the
            // verdict (a real playlist starts with #EXTM3U and
            // can never trip it).
            if (PortalDetect.looksLikeHtml(text)) {
                throw PortalBlockedException()
            }
            // v4.1 audit round 26: a playlist body that ended
            // before its declared Content-Length is a TRUNCATED
            // playlist — parsing it would silently drop the tail
            // segments and merge a partial stream as DONE. Only
            // an EOF read is judged; a body cut at the cap was
            // never claimed to be complete (see DeclaredLength).
            if (consumed && !DeclaredLength.eofMatches(c.contentLengthLong, readTotal)) {
                throw IllegalStateException("播放列表长度对不上（服务器声明和实际发来的不一样），已停下")
            }
            // Same round: a 206 answer to a request that carried
            // no Range is only the whole resource when its range
            // starts at 0 and its quoted total is the body in
            // hand — anything else is a slice posing as the whole.
            if (c.responseCode == HttpURLConnection.HTTP_PARTIAL &&
                !wholeRange(c.getHeaderField("Content-Range"), readTotal)
            ) {
                throw IllegalStateException("播放列表只发来了一部分，已停下")
            }
            return text
        } finally {
            if (!consumed) runCatching { c.disconnect() }
        }
    }

    /** Read a whole segment (or a key) into memory, size-capped. */
    private fun fetchBytes(url: String, capBytes: Int): ByteArray {
        val c = openFollowingRedirects(url) { }
        var consumed = false
        try {
            if (c.responseCode !in 200..299) {
                throw HttpFailureException(c.responseCode)
            }
            val data = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) {
                        consumed = true
                        break
                    }
                    total += n
                    if (total > capBytes) throw IllegalStateException("分片太大了，已停下")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            // v4.4 (audit round 29): an HTML body here is a
            // portal login page, not a segment or a key — the
            // .ts sync-byte and 16-byte-key checks downstream
            // would refuse it too, but with the wrong story;
            // the shared detector names it first.
            if (PortalDetect.looksLikeHtml(data)) {
                throw PortalBlockedException()
            }
            // v4.1 audit round 26: a segment body that ended
            // before its declared Content-Length used to be
            // merged and marked written exactly like a whole
            // one — a silently truncated segment inside a file
            // that reports DONE. A declared length is a promise
            // (see DeclaredLength); breaking it throws here,
            // and segmentBytes' existing 3-attempt retry is what
            // answers. An undeclared (chunked) body cannot be
            // judged and never condemns.
            if (!DeclaredLength.eofMatches(c.contentLengthLong, data.size.toLong())) {
                throw IllegalStateException("分片长度对不上（服务器声明和实际发来的不一样），已重试")
            }
            // Same round: a 206 answer to a request that carried
            // no Range is only the whole segment when its range
            // starts at 0 and its quoted total is the body in
            // hand — a slice's own Content-Length would pass the
            // check above while the segment is silently short.
            if (c.responseCode == HttpURLConnection.HTTP_PARTIAL &&
                !wholeRange(c.getHeaderField("Content-Range"), data.size.toLong())
            ) {
                throw IllegalStateException("分片只发来了一部分，已重试")
            }
            return data
        } finally {
            if (!consumed) runCatching { c.disconnect() }
        }
    }

    /**
     * Download an HLS stream: fetch the playlist (for a master
     * playlist, the highest-bandwidth variant), download the TS
     * segments a few at a time, and write them into the target
     * STRICTLY in playlist order — the finished file is one plain
     * .ts that any player can open. No transcoding (there is no
     * ffmpeg here); the note on the card says exactly that.
     *
     * Resume granularity is one segment: written segments are a
     * prefix of the playlist and are never re-downloaded; segments
     * fetched but not yet written when a pause hits are fetched
     * again next time (bounded by the prefetch window).
     */
    private fun runHls(): TaskStatus {
        var parsed = Hls.parse(fetchText(task.url), task.url)
        if (parsed is Hls.Playlist.Master) {
            val variant = parsed.variants.maxByOrNull { it.bandwidth }
                ?: parsed.variants.firstOrNull()
                ?: throw IllegalStateException("播放列表里没有可选的清晰度")
            parsed = Hls.parse(fetchText(variant.url), variant.url)
        }
        val media = parsed as? Hls.Playlist.Media
            ?: throw IllegalStateException("播放列表格式不对，可能不是有效的 m3u8")
        val segments = media.segments
        if (segments.isEmpty()) throw IllegalStateException("播放列表里没有分片")
        if (media.hasMap) {
            // v1.7 audit: #EXT-X-MAP means fragmented MP4 — the
            // merged bytes would be missing the init segment and
            // would not play. Refuse plainly instead of producing
            // a broken file that claims to be done.
            throw IllegalStateException(
                "这个流媒体用的是分片 MP4（fMP4）格式，暂不支持合并，下不了"
            )
        }
        // v1.7 audit (B1): the old check required keyUri != null, so
        // a segment with an unsupported METHOD but no URI (or an
        // AES-128 tag with no URI) slipped through and was merged
        // still-encrypted while the task reported DONE. Any key
        // method that is not NONE and not a usable AES-128 now
        // stops the download with a plain reason.
        val badKey = segments.firstOrNull { seg ->
            val m = seg.keyMethod
            m != null && m != "NONE" && !(m == "AES-128" && seg.keyUri != null)
        }
        if (badKey != null) {
            throw IllegalStateException(
                "这个流媒体用了暂不支持的加密方式（${badKey.keyMethod}），下不了"
            )
        }

        // Resume state: a playlist whose segment count changed is a
        // different stream — old merged bytes are meaningless.
        // v2.1 audit (round 7, C6): the count alone is not identity.
        // A playlist can change CONTENT at the same count (an
        // updated VOD, a slid live window); keeping the old written
        // prefix then would append the new playlist's tail onto the
        // old file's head — a silently corrupt merge that reports
        // DONE. The segment-URL fingerprint is the real identity
        // check. A blank stored fingerprint (state saved before
        // v2.1) cannot judge, so it is simply adopted this run.
        val fingerprint = Hls.fingerprint(segments.map { it.url })
        val playlistChanged = task.hlsTotalSegments != segments.size ||
            (task.hlsFingerprint.isNotBlank() && task.hlsFingerprint != fingerprint)
        if (playlistChanged) {
            if (task.hlsTotalSegments > 0) {
                task.note = "播放列表变了（内容和上次不一样），已重新开始合并。"
            }
            task.hlsTotalSegments = segments.size
            task.hlsDone = BooleanArray(segments.size)
            synchronized(task.hlsSizes) { task.hlsSizes.clear() }
            task.downloaded = 0
            downloadedBytes.set(0)
        }
        task.hlsFingerprint = fingerprint
        synchronized(task.hlsSizes) {
            while (task.hlsSizes.size < segments.size) task.hlsSizes.add(-1)
        }
        // Rebuild the write cursor: written segments form a prefix.
        var writeIndex = 0
        var offset = 0L
        while (writeIndex < segments.size && task.hlsDone.getOrElse(writeIndex) { false }) {
            val s = synchronized(task.hlsSizes) { task.hlsSizes.getOrElse(writeIndex) { -1 } }
            if (s < 0) break
            offset += s
            writeIndex++
        }
        if (task.hlsDone.drop(writeIndex).any { it }) {
            // Inconsistent state (done segments beyond a gap): the
            // only safe move is to merge again from the beginning.
            task.hlsDone = BooleanArray(segments.size)
            synchronized(task.hlsSizes) {
                task.hlsSizes.clear()
                while (task.hlsSizes.size < segments.size) task.hlsSizes.add(-1)
            }
            writeIndex = 0
            offset = 0
        }
        downloadedBytes.set(offset)
        task.downloaded = offset

        if (task.fileName.isBlank() || task.fileName.endsWith(".m3u8", true)) {
            task.fileName = hlsFileName(task.url)
        }
        target = targetProvider()
        // v3.2 audit round 18: the same part-integrity question
        // as the segmented path, in HLS geometry. The written
        // prefix is sequential, so a genuine part measures at
        // least the prefix length; a SHORTER part has lost bytes
        // (deleted content, in-place truncation, a recreated
        // empty file under a surviving Uri — obtainTarget resets
        // the segmented state for a vanished Uri, but the HLS
        // prefix lives in different fields and is judged here,
        // where the cursor is rebuilt). Writing the next segment
        // at the stale cursor into a short file would pad the gap
        // with zeros and merge a corrupt file that reports DONE.
        // An unmeasurable part never condemns (see PartIntegrity).
        if (!PartIntegrity.hlsTrustworthy(
                prefixBytes = offset,
                partSize = runCatching { target.size() }.getOrDefault(-1),
            )
        ) {
            task.hlsDone = BooleanArray(segments.size)
            synchronized(task.hlsSizes) {
                task.hlsSizes.clear()
                while (task.hlsSizes.size < segments.size) task.hlsSizes.add(-1)
            }
            writeIndex = 0
            offset = 0
            downloadedBytes.set(0)
            task.downloaded = 0
            task.note = "半成品文件对不上（字节丢了），已重新开始合并。"
            onSave()
        }
        if (offset == 0L) target.truncate(0)

        // v4.3: adaptive HLS fetch width (see HlsPlan for the
        // full rule table). The plan is fixed for the run from
        // the host's remembered optimum / rate-limit cap; the
        // scaler re-judges the live width on the sampler tick
        // below; the global connection allowance is re-read on
        // the same tick and applied as a gate — never baked into
        // the plan, because it changes as other tasks come and
        // go. Before v4.3 this path used a fixed pool of 4 and
        // never read the allowance at all.
        val hlsPlan = HlsPlan.plan(
            hint = initialThreadsHint,
            cap = initialThreadsCap,
            totalSegments = segments.size,
        )
        val hlsScaler = HlsScaler(hlsPlan.startWidth, hlsPlan.maxWidth)
        /** The gated live width the writer loop's submit window
         *  reads; rewritten by the sampler tick every second. */
        val hlsLiveWidth = AtomicInteger(hlsPlan.startWidth)
        /** Set by the writer loop whenever it reaches a segment
         *  whose fetch has not finished yet — the scaler's
         *  fetch-bound evidence; the sampler tick consumes and
         *  clears it. */
        val hlsStarved = AtomicInteger(0)
        var hlsLastRetryCount = task.retryCount
        // task.threads is the card's live connection display
        // (the same field the segmented path keeps equal to its
        // scaler target); for HLS it carries the live width.
        task.threads = hlsPlan.startWidth

        // Speed sampler on the same fixed 1 s grid as the other
        // paths (guards included; the traffic guard cannot block
        // here — an HLS total is unknowable before the last segment,
        // and TrafficGuard never blocks unknown sizes).
        val stopSampler = AtomicInteger(0)
        val sampler = Thread {
            var lastBytes = downloadedBytes.get()
            var lastTime = System.currentTimeMillis()
            var nextTick = System.currentTimeMillis() + 1000
            while (stopSampler.get() == 0 && !stopped()) {
                val waitMs = nextTick - System.currentTimeMillis()
                try {
                    Thread.sleep(waitMs.coerceIn(50, 1000))
                } catch (e: InterruptedException) { break }
                nextTick += 1000
                if (nextTick < System.currentTimeMillis()) {
                    nextTick = System.currentTimeMillis() + 1000
                }
                if (!networkGuard()) {
                    netBlocked = true
                    break
                }
                if (!trafficGuard()) {
                    trafficBlocked = true
                    break
                }
                val now = System.currentTimeMillis()
                val bytes = downloadedBytes.get()
                val dt = max(1L, now - lastTime)
                val speed = (bytes - lastBytes) * 1000 / dt
                task.speedBps = if (task.speedBps == 0L) speed else (task.speedBps * 3 + speed) / 4
                recordSpeed(task.speedBps)
                task.downloaded = bytes
                // v4.3: re-read the global allowance and re-judge
                // the HLS width on this same tick (no new thread —
                // the segmented path drives its scaler from its
                // sampler in exactly the same way). While the CAP
                // is what binds (allowance below the scaler's
                // width), the scaler is NOT evaluated: its
                // verdicts judge starvation/speed deltas, and a
                // delta the cap caused — another task starting or
                // finishing — would be misread as this host
                // speeding up or slowing down. The evidence is
                // still consumed so a stale starved/retry signal
                // cannot fire the moment the cap lifts; the gated
                // width simply holds until then.
                connAllowance = connectionAllowance()
                val retryDelta = task.retryCount - hlsLastRetryCount
                hlsLastRetryCount = task.retryCount
                val starved = hlsStarved.getAndSet(0) == 1
                if (connAllowance >= hlsScaler.width) {
                    hlsScaler.onTick(task.speedBps, starved, retryDelta)
                }
                val gatedWidth = HlsPlan.gate(hlsScaler.width, connAllowance)
                hlsLiveWidth.set(gatedWidth)
                task.threads = gatedWidth
                task.connCapHeld = connAllowance < hlsScaler.width
                lastBytes = bytes
                lastTime = now
                onProgress(task)
            }
        }
        sampler.isDaemon = true
        sampler.start()

        val keyCache = mutableMapOf<String, ByteArray>()
        fun segmentBytes(seg: Hls.Segment): ByteArray {
            var lastError: Exception? = null
            var attempt = 0
            while (attempt < 3) {
                attempt++
                if (attempt > 1) task.retryCount++
                if (stopped()) throw InterruptedException("stopped")
                try {
                    var data = fetchBytes(seg.url, MAX_SEGMENT_BYTES)
                    val keyUri = seg.keyUri
                    if (keyUri != null) {
                        val key = synchronized(keyCache) {
                            keyCache.getOrPut(keyUri) { fetchBytes(keyUri, 64) }
                        }
                        if (key.size != 16) {
                            throw IllegalStateException("这个流媒体的密钥长度不对，下不了")
                        }
                        val iv = seg.keyIv ?: Hls.sequenceIv(seg.sequence)
                        data = Hls.decryptAes128(data, key, iv)
                    }
                    // v2.4 audit round 10: a .ts segment MUST begin
                    // with the MPEG-TS sync byte 0x47. A CDN error
                    // page (or a swapped segment) served with a 200
                    // would otherwise be merged into a "successful"
                    // file no player can open. Scoped to .ts on
                    // purpose: raw AAC/MP3 audio segments are legal
                    // HLS and carry no sync byte, so they are not
                    // judged here.
                    if (isTsSegment(seg.url) && data.isNotEmpty() &&
                        data[0] != TS_SYNC_BYTE
                    ) {
                        throw IllegalStateException(
                            "分片内容不对（不像视频分片），可能是服务器返回了错误页面，已重试"
                        )
                    }
                    return data
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    try { Thread.sleep(400L * attempt) } catch (_: InterruptedException) {
                        throw InterruptedException("stopped")
                    }
                }
            }
            throw lastError ?: IllegalStateException("分片下载失败")
        }

        // v4.3: the pool is sized ONCE to the plan's MAX width;
        // scaling never rebuilds it — the submit window below
        // (writeIndex + live width) is what actually gates how
        // many fetches run at once, so a narrower live width
        // simply leaves pool threads idle.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(hlsPlan.maxWidth) { r ->
            Thread(r).apply { isDaemon = true }
        }
        // v1.7 perf: in-flight BYTE budget. The window alone does
        // not bound memory: each segment may be up to
        // MAX_SEGMENT_BYTES (64 MB), so a window of finished-but-
        // unwritten segments could theoretically pin hundreds of
        // MB. Fetched bytes now count against a ~48 MB budget;
        // prefetch pauses while the buffer is over budget and
        // resumes as the writer drains it. The segment AT the
        // write cursor is always submittable — the byte budget
        // exempts it (submitIndex == writeIndex below) and the
        // width gate can never close on it either, because the
        // gated live width has a floor of 1 (HlsPlan.gate), so
        // writeIndex itself is always inside the window. The
        // writer can never starve waiting for a fetch the gates
        // refused to start.
        val bufferedBytes = AtomicLong(0)
        var failure: Exception? = null
        try {
            val futures = HashMap<Int, java.util.concurrent.Future<ByteArray>>()
            var submitIndex = writeIndex
            while (writeIndex < segments.size && !stopped()) {
                while (submitIndex < segments.size &&
                    submitIndex < writeIndex + hlsLiveWidth.get() && !stopped() &&
                    (submitIndex == writeIndex ||
                        bufferedBytes.get() < HLS_BUFFER_BUDGET_BYTES)
                ) {
                    val idx = submitIndex
                    futures[idx] = pool.submit(
                        java.util.concurrent.Callable {
                            val data = segmentBytes(segments[idx])
                            bufferedBytes.addAndGet(data.size.toLong())
                            data
                        }
                    )
                    submitIndex++
                }
                val f = futures.remove(writeIndex) ?: break
                // v4.3: the writer reached a segment whose fetch
                // has not landed — fetch-bound evidence for the
                // scaler, consumed by the next sampler tick.
                if (!f.isDone) hlsStarved.set(1)
                val data = try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw (e.cause as? Exception) ?: e
                }
                target.writeAt(offset, data, 0, data.size)
                bufferedBytes.addAndGet(-data.size.toLong())
                offset += data.size
                synchronized(task.hlsSizes) { task.hlsSizes[writeIndex] = data.size.toLong() }
                task.hlsDone[writeIndex] = true
                downloadedBytes.addAndGet(data.size.toLong())
                task.downloaded = downloadedBytes.get()
                limiter?.throttle(data.size)
                writeIndex++
                if (writeIndex % 4 == 0) onSave()
            }
        } catch (e: InterruptedException) {
            if (!stopped()) failure = e
        } catch (e: Exception) {
            failure = e
        } finally {
            pool.shutdownNow()
            stopSampler.set(1)
            sampler.join(1500)
            task.speedBps = 0
            task.downloaded = downloadedBytes.get()
        }
        if (failure != null) throw failure
        if (writeIndex >= segments.size) {
            // v1.8: seal the merged file before it counts as DONE.
            runCatching { target.sync() }
        }
        return when {
            trafficBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = Notes.trafficGuardSwitchedHls()
            }
            netBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = Notes.wifiOnlyPaused()
            }
            paused -> TaskStatus.PAUSED.also { task.status = it }
            writeIndex >= segments.size -> TaskStatus.DONE.also {
                task.status = it
                task.totalSize = downloadedBytes.get()
                task.note = "HLS 流媒体：已把 ${segments.size} 个分片按顺序合并成一个 .ts 文件，" +
                    "可直接用播放器打开（没有转码）。"
            }
            else -> TaskStatus.FAILED.also {
                task.status = it
                if (task.error.isBlank()) task.error = "分片合并中断"
            }
        }
    }

    private fun hlsFileName(url: String): String {
        val raw = fileNameFrom(null, url)
        val base = raw.substringBeforeLast('.', raw)
        return (if (base.isBlank()) "stream" else base) + ".ts"
    }

    // ---------- FTP path (v1.7, segmented v4.8) ----------

    /**
     * Download an ftp:// file with the hand-written [FtpClient].
     *
     * v4.8: the run opens ONE probe session (connect / login /
     * binary / SIZE, then the traffic + space guards, then the
     * REST probe — the v1.7 order and wording unchanged), and the
     * probe session is CLOSED before any worker starts, so it
     * never counts against the server's session cap. The probe
     * results decide the shape of the run ([FtpSegmented.decide]):
     * known total + REST + at least 2 chunks → the segmented path
     * ([runFtpSegmented]); anything else → the v1.7 single-stream
     * body ([runFtpSingle]) with an honest note saying why. Either
     * way [TaskRecord.singleThreadOnly] is rewritten truthfully
     * (the persisted flag cannot be trusted as a verdict: runFtp
     * set it on EVERY ftp task from v1.7 to v4.7).
     *
     * There is deliberately NO second fallback: if every
     * segmented worker fails, the run is FAILED with progress
     * kept, exactly like the HTTP segmented path. A server whose
     * session cap is 1 still lets worker 0 transfer — the other
     * workers retire quietly at login — so the realistic refusal
     * modes never reach that failure.
     */
    private fun runFtp(): TaskStatus {
        val ftp = FtpClient.parseUrl(task.url)
            ?: throw IllegalStateException("FTP 地址格式不对，看不懂这个链接")
        if (task.fileName.isBlank()) {
            task.fileName = fileNameFrom(null, task.url)
        }
        val probe = FtpClient()
        try {
            probe.connect(ftp.host, ftp.port)
            probe.login(ftp.user, ftp.password)
            probe.binary()
            val total = probe.size(ftp.path)
            var serverChanged = false
            if (total >= 0) {
                if (task.totalSize > 0 && task.totalSize != total) {
                    serverChanged = true
                    task.note = "服务器上的文件大小变了，旧进度作废，已重新开始下载。"
                }
                task.totalSize = total
            }
            // Traffic protection can only judge once the size is known.
            if (!trafficGuard()) {
                trafficBlocked = true
                task.status = TaskStatus.PAUSED
                task.note = Notes.trafficGuardAtStart(task.totalSize)
                return task.status
            }
            // v2.3 space pre-check (FTP learns its size from SIZE).
            if (total > 0) {
                val spaceMsg = runCatching { spaceGuard() }.getOrNull()
                if (spaceMsg != null) {
                    task.status = TaskStatus.PAUSED
                    task.note = spaceMsg
                    return task.status
                }
            }
            val rest = probe.restSupported()
            // The chunk count the grid WOULD have (an existing
            // grid keeps its persisted count; a fresh one is the
            // gridFor decision runFtpSegmented will persist).
            val wouldCount = if (total > 0) {
                if (task.chunkSize > 0 && task.chunkDone.isNotEmpty()) {
                    task.chunkDone.size
                } else {
                    ChunkPlanner.chunkCount(
                        ChunkPlanner.gridFor(
                            total,
                            ChunkPlanner.chunkSizeFor(total, 16),
                            StartPlan.BURST_START,
                        )
                    )
                }
            } else {
                0
            }
            val block = FtpSegmented.decide(total > 0, rest, wouldCount)
            // v4.8 ruling: the probe session closes BEFORE the
            // workers start — it must not hold one of the server's
            // sessions while the run tries to open its own.
            runCatching { probe.close() }
            if (block == null) {
                return runFtpSegmented(ftp, total, serverChanged)
            }
            when (block) {
                FtpSegmented.Block.SIZE_UNKNOWN ->
                    if (!task.note.contains("已用单连接下载")) {
                        appendNote("这个 FTP 服务器不报文件大小（SIZE），没法分片，已用单连接下载。")
                    }
                FtpSegmented.Block.TOO_FEW_CHUNKS ->
                    if (!task.note.contains("已用单连接下载")) {
                        appendNote("文件太小，分片没有意义，已用单连接下载。")
                    }
                // REST: runFtpSingle writes the existing REST
                // note, which now also states the single
                // connection consequence.
                FtpSegmented.Block.REST_UNSUPPORTED -> Unit
            }
            return runFtpSingle(ftp, total, serverChanged, rest)
        } finally {
            // Idempotent: the explicit close above already ran on
            // the decide path; this covers the guard early-returns
            // and probe failures.
            runCatching { probe.close() }
        }
    }

    /**
     * The v1.7 single-stream FTP body, unchanged in behaviour:
     * one data stream resumed with REST when the server supports
     * it — the resume offset is the length the existing .part
     * already has, measured through the target itself. A server
     * file whose SIZE changed since the last run restarts the
     * transfer instead of mixing bytes. Runs on a FRESH session
     * (the probe session is already closed, see [runFtp]).
     */
    private fun runFtpSingle(
        ftp: FtpClient.Companion.FtpUrl,
        total: Long,
        serverChanged: Boolean,
        rest: Boolean,
    ): TaskStatus {
        task.singleThreadOnly = true
        val client = FtpClient()
        // Speed sampler on the same fixed 1 s grid as the other
        // paths (single source for card, chart and notification).
        val stopSampler = AtomicInteger(0)
        val sampler = Thread {
            var lastBytes = downloadedBytes.get()
            var lastTime = System.currentTimeMillis()
            var nextTick = System.currentTimeMillis() + 1000
            while (stopSampler.get() == 0 && !stopped()) {
                val waitMs = nextTick - System.currentTimeMillis()
                try {
                    Thread.sleep(waitMs.coerceIn(50, 1000))
                } catch (e: InterruptedException) { break }
                nextTick += 1000
                if (nextTick < System.currentTimeMillis()) {
                    nextTick = System.currentTimeMillis() + 1000
                }
                if (!networkGuard()) {
                    netBlocked = true
                    break
                }
                if (!trafficGuard()) {
                    trafficBlocked = true
                    break
                }
                val now = System.currentTimeMillis()
                val bytes = downloadedBytes.get()
                val dt = max(1L, now - lastTime)
                val speed = (bytes - lastBytes) * 1000 / dt
                task.speedBps = if (task.speedBps == 0L) speed else (task.speedBps * 3 + speed) / 4
                recordSpeed(task.speedBps)
                task.downloaded = bytes
                // v3.5: one whole-file data connection — the
                // details view's snapshot is this single entry
                // (chunkIndex -1, see ConnInfo).
                task.connInfos = listOf(
                    ConnInfo(-1, bytes, task.totalSize, task.speedBps)
                )
                lastBytes = bytes
                lastTime = now
                onProgress(task)
            }
        }
        sampler.isDaemon = true
        try {
            client.connect(ftp.host, ftp.port)
            client.login(ftp.user, ftp.password)
            client.binary()
            target = targetProvider()
            var offset = 0L
            // v1.8 audit: a .part that already holds the WHOLE file
            // (the previous run died between the last byte and the
            // rename) is finished, not a resume candidate — the old
            // code truncated it and downloaded everything again.
            var alreadyComplete = false
            if (rest && !serverChanged) {
                val existing = runCatching { target.size() }.getOrDefault(0)
                if (existing > 0 && total > 0 && existing >= total) {
                    offset = total
                    alreadyComplete = true
                } else if (existing > 0 && (total < 0 || existing < total)) {
                    offset = existing
                }
            }
            if (alreadyComplete) {
                appendNote("FTP：文件其实已经下完了，直接收尾。")
            } else if (offset == 0L) {
                target.truncate(0)
            } else {
                appendNote("FTP 断点续传：从 ${Format.size(offset)} 处接着下。")
            }
            if (!rest && !task.note.contains("不支持断点续传")) {
                appendNote("这个 FTP 服务器不支持断点续传（REST），没法分片，已用单连接下载；暂停后重新开始会从头下载。")
            }
            downloadedBytes.set(offset)
            task.downloaded = offset
            if (!alreadyComplete) {
                sampler.start()
                var pos = offset
                client.retrieve(ftp.path, offset) { buf, n ->
                    if (stopped()) throw InterruptedException("stopped")
                    target.writeAt(pos, buf, 0, n)
                    pos += n
                    downloadedBytes.addAndGet(n.toLong())
                    limiter?.throttle(n)
                }
                // v4.1 audit round 26: SIZE declared a total —
                // a transfer that delivered a different byte
                // count (a clean 226 over a short body, or extra
                // bytes past the declared end) is not the promised
                // file. The old ">=" completion check below would
                // have called the oversized case DONE; pos is the
                // whole file's length here (resume offset + what
                // this run added), so it must equal the total
                // exactly. An undeclared total (SIZE refused)
                // never condemns.
                if (total > 0 && pos != total) {
                    throw IllegalStateException("FTP 发来的数据和声明的大小对不上，已停下")
                }
                // v1.8: seal the file before it counts as DONE.
                if (!stopped()) runCatching { target.sync() }
            }
        } catch (e: InterruptedException) {
            // Pause (or a guard) stopped the transfer mid-stream;
            // the status decision below reads the stop flags.
            if (!stopped()) throw e
        } finally {
            stopSampler.set(1)
            runCatching { sampler.join(1500) }
            runCatching { client.close() }
            if (::target.isInitialized) runCatching { target.close() }
            task.speedBps = 0
            task.downloaded = downloadedBytes.get()
        }
        return when {
            trafficBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = Notes.trafficGuardSwitched(task.totalSize)
            }
            netBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = Notes.wifiOnlyPaused()
            }
            paused -> TaskStatus.PAUSED.also { task.status = it }
            // v4.1 audit round 26: equality, not ">=" — more
            // bytes than SIZE declared is a broken promise too
            // (the retrieve check above throws first in the
            // streamed case; this also covers alreadyComplete).
            task.totalSize > 0 && task.downloaded == task.totalSize ->
                TaskStatus.DONE.also { task.status = it }
            task.totalSize <= 0 ->
                TaskStatus.DONE.also { task.status = it }
            else -> TaskStatus.FAILED.also {
                task.status = it
                if (task.error.isBlank()) task.error = "FTP 下载中断"
            }
        }
    }

    /**
     * v4.8: the FTP segmented run — the HTTP segmented path's
     * DISCIPLINE on FTP transport (see [FtpSegmented] for the
     * recorded narrowings: no endgame split, no ThreadScaler, no
     * mirrors, no HostMemory). The ChunkPlanner grid is decided
     * once and persisted exactly like runSegmented's, the
     * PartIntegrity gate is the same, workers claim chunk indexes
     * from a shared atomic cursor starting at the first unfinished
     * chunk, each chunk gets at most 3 attempts resuming at its
     * current position, and the 1 s sampler publishes per-part
     * connInfos, tracks peakConns and evicts stalls by closing
     * the part's data socket.
     */
    private fun runFtpSegmented(
        ftp: FtpClient.Companion.FtpUrl,
        total: Long,
        serverChanged: Boolean,
    ): TaskStatus {
        task.singleThreadOnly = false
        target = targetProvider()
        // Entry state for the rulings below, captured BEFORE any
        // reset: did a grid / bitmap already exist, and how long
        // is the .part right now (measured before truncate(total)
        // rewrites its length)?
        val hadGrid = task.chunkSize > 0 && task.chunkDone.isNotEmpty()
        val hadBitmap = task.chunkDone.any { it }
        val entryPartSize = runCatching { target.size() }.getOrDefault(-1)
        // Ruling: a persisted totalSize that disagrees with this
        // run's SIZE means the server file changed — the grid,
        // the bitmap and the per-source accounting reset, with
        // the note runFtp's probe already wrote (the existing FTP
        // wording). Judged BEFORE the PartIntegrity gate, like
        // runSegmented's validator reset precedes its own gate.
        if (serverChanged) {
            task.chunkDone = BooleanArray(0)
            task.chunkSize = 0
            task.fineChunkSize = 0
            task.fineChunkCount = 0
            synchronized(task.sourceBytes) { task.sourceBytes.clear() }
            task.downloaded = 0
            downloadedBytes.set(0)
            onSave()
        }
        // v3.2 audit round 18 gate, identical to runSegmented's:
        // a .part shorter than the total while chunks are marked
        // done has lost bytes behind our back; trusting the
        // bitmap would seal zeros as DONE.
        if (!PartIntegrity.segmentedTrustworthy(
                hasProgress = task.chunkDone.any { it },
                partSize = entryPartSize,
                totalSize = total,
            )
        ) {
            task.chunkDone = BooleanArray(0)
            task.chunkSize = 0
            task.fineChunkSize = 0
            task.fineChunkCount = 0
            synchronized(task.sourceBytes) { task.sourceBytes.clear() }
            task.downloaded = 0
            downloadedBytes.set(0)
            task.note = "半成品文件对不上（字节丢了），旧进度作废，已重新开始下载。"
            onSave()
        }
        if (task.chunkSize <= 0 || task.chunkDone.isEmpty()) {
            // The grid is decided HERE, once, and persisted on
            // the task — a resume reuses it verbatim (the resume
            // invariant), exactly as in runSegmented.
            val coarse = ChunkPlanner.chunkSizeFor(total, 16)
            val grid = ChunkPlanner.gridFor(total, coarse, StartPlan.BURST_START)
            task.chunkSize = grid.coarseSize
            task.fineChunkSize = grid.fineSize
            task.fineChunkCount = grid.fineCount
            task.chunkDone = BooleanArray(ChunkPlanner.chunkCount(grid))
        }
        val grid = ChunkPlanner.gridOf(
            total, task.chunkSize, task.fineChunkSize, task.fineChunkCount
        )
        val count = task.chunkDone.size
        // Ruling (legacy FTP progress adoption): a task last run
        // by v4.7 or earlier has NO grid/bitmap — only a .part
        // whose length L the single stream wrote SEQUENTIALLY, so
        // [0, L) is continuously trustworthy. Chunks lying wholly
        // inside L are marked done (a partial tail chunk is not —
        // its unwritten remainder cannot be told from its written
        // head without a bitmap); downloadedBytes is then derived
        // from the bitmap below, like every segmented resume.
        // L >= total never reaches here as a partial (the grid
        // decision required L < total to matter); L unmeasurable
        // (< 0) adopts nothing. serverChanged excludes adoption
        // too: those bytes belong to the OLD server file, not
        // this grid's file.
        if (!serverChanged && !hadGrid && !hadBitmap &&
            entryPartSize > 0 && entryPartSize < total
        ) {
            var adopted = 0
            synchronized(task.chunkDone) {
                for (i in 0 until count) {
                    val r = ChunkPlanner.rangeOf(grid, i)
                    if (r.last < entryPartSize) {
                        task.chunkDone[i] = true
                        adopted++
                    } else {
                        break
                    }
                }
            }
            if (adopted > 0) {
                appendNote(
                    "接上了之前单连接下好的进度（约 ${Format.size(entryPartSize)}），" +
                        "剩下的部分用多连接接着下。"
                )
            }
        }
        // Resume cursor: first unfinished chunk.
        var firstOpen = task.chunkDone.indexOfFirst { !it }
        if (firstOpen < 0) firstOpen = count
        val cursor = AtomicInteger(firstOpen)
        downloadedBytes.set(ChunkPlanner.downloadedOf(grid, task.chunkDone))
        task.downloaded = downloadedBytes.get()

        target.truncate(total)

        val workerCount = FtpSegmented.workerCount(
            connectionAllowance(), task.threads, count
        )
        task.threads = workerCount
        if (!task.note.contains("FTP 多连接下载")) {
            appendNote("FTP 多连接下载：$workerCount 条连接一起下。")
        }
        onSave()

        val parts = java.util.Collections.synchronizedList(mutableListOf<FtpPart>())
        val storageStop = AtomicInteger(0)
        val liveWorkers = AtomicInteger(0)
        val stopSampler = AtomicInteger(0)
        val sampler = Thread {
            var lastBytes = downloadedBytes.get()
            var lastTime = System.currentTimeMillis()
            var nextTick = System.currentTimeMillis() + 1000
            while (stopSampler.get() == 0 && !stopped() && storageStop.get() == 0) {
                val waitMs = nextTick - System.currentTimeMillis()
                try {
                    Thread.sleep(waitMs.coerceIn(50, 1000))
                } catch (e: InterruptedException) { break }
                nextTick += 1000
                if (nextTick < System.currentTimeMillis()) {
                    nextTick = System.currentTimeMillis() + 1000
                }
                if (!networkGuard()) {
                    netBlocked = true
                    break
                }
                if (!trafficGuard()) {
                    trafficBlocked = true
                    break
                }
                val now = System.currentTimeMillis()
                val bytes = downloadedBytes.get()
                val dt = max(1L, now - lastTime)
                val speed = (bytes - lastBytes) * 1000 / dt
                task.speedBps = if (task.speedBps == 0L) speed else (task.speedBps * 3 + speed) / 4
                recordSpeed(task.speedBps)
                val aw = liveWorkers.get()
                if (aw > task.peakConns) task.peakConns = aw
                task.downloaded = bytes
                // v3.5 snapshot, per live part (same rule as the
                // segmented sampler: this tick's position delta).
                val snapshot = synchronized(parts) { parts.toList() }
                task.connInfos = snapshot.map { p ->
                    val sp = (p.pos - p.lastSamplePos) * 1000 / dt
                    p.lastSamplePos = p.pos
                    ConnInfo(
                        chunkIndex = p.index,
                        downloaded = (p.pos - p.start).coerceAtLeast(0),
                        total = (p.end - p.start).coerceAtLeast(0),
                        speedBps = sp.coerceAtLeast(0),
                    )
                }.sortedBy { it.chunkIndex }
                // Stall eviction (StallPolicy, same inputs as the
                // HTTP path): the FTP action is closing the part's
                // DATA socket — the worker wakes with an exception
                // and retries the chunk from its current position.
                for (p in snapshot) {
                    if (StallPolicy.shouldEvict(
                            task.speedBps,
                            now - p.lastAdvanceMs,
                            snapshot.size,
                        )
                    ) {
                        p.lastAdvanceMs = now // don't spam
                        p.client?.abortTransfer()
                    }
                }
                lastBytes = bytes
                lastTime = now
                onProgress(task)
            }
        }
        sampler.isDaemon = true
        sampler.start()

        val threads = ArrayList<Thread>()
        for (w in 0 until workerCount) {
            val t = Thread {
                liveWorkers.incrementAndGet()
                try {
                    ftpWorkerLoop(ftp, grid, count, cursor, parts, storageStop)
                } finally {
                    liveWorkers.decrementAndGet()
                }
            }
            t.isDaemon = true
            threads.add(t)
            t.start()
        }
        for (t in threads) {
            try { t.join() } catch (e: InterruptedException) { break }
        }
        stopSampler.set(1)
        runCatching { sampler.join(1500) }

        task.speedBps = 0
        task.downloaded = downloadedBytes.get()
        val doneCount = synchronized(task.chunkDone) { task.chunkDone.count { it } }
        if (doneCount == count) {
            // v1.8: one fsync seals the file before DONE.
            runCatching { target.sync() }
        }
        runCatching { target.close() }
        return when {
            // Segmented completion is strict: every chunk done
            // AND the byte count equal to SIZE's total.
            doneCount == count && task.downloaded == total -> {
                task.status = TaskStatus.DONE
                task.status
            }
            trafficBlocked -> {
                task.note = Notes.trafficGuardSwitched(task.totalSize)
                task.status = TaskStatus.PAUSED
                task.status
            }
            netBlocked -> {
                task.note = Notes.wifiOnlyPaused()
                task.status = TaskStatus.PAUSED
                task.status
            }
            paused -> {
                task.status = TaskStatus.PAUSED
                task.status
            }
            else -> {
                task.status = TaskStatus.FAILED
                if (task.error.isBlank()) task.error = "部分分片下载失败"
                task.status
            }
        }
    }

    /** One worker's fresh FTP session (connect + login + binary). */
    private fun newFtpSession(ftp: FtpClient.Companion.FtpUrl): FtpClient {
        val c = FtpClient()
        try {
            c.connect(ftp.host, ftp.port)
            c.login(ftp.user, ftp.password)
            c.binary()
        } catch (e: Exception) {
            runCatching { c.close() }
            throw e
        }
        return c
    }

    /**
     * One FTP worker: owns ONE [FtpClient] session, reused across
     * chunks, and claims chunk indexes from the shared cursor.
     * A worker whose session cannot be established (the server's
     * per-IP session cap refusing another login is the common
     * case) retires quietly — the remaining workers carry the
     * run. A session the client marks controlDead is dropped and
     * re-established on the next attempt. Storage-full /
     * storage-gone stop the WHOLE run (the v4.3 discipline, same
     * as downloadActive): every other error retries the chunk
     * from its current position, at most 3 attempts.
     */
    private fun ftpWorkerLoop(
        ftp: FtpClient.Companion.FtpUrl,
        grid: ChunkGrid,
        count: Int,
        cursor: AtomicInteger,
        parts: MutableList<FtpPart>,
        storageStop: AtomicInteger,
    ) {
        var client: FtpClient? = null
        try {
            while (!stopped() && storageStop.get() == 0) {
                val index = cursor.getAndIncrement()
                if (index >= count) break
                if (synchronized(task.chunkDone) { task.chunkDone[index] }) continue
                val range = ChunkPlanner.rangeOf(grid, index)
                val part = FtpPart(index, range.first, range.last + 1)
                synchronized(parts) { parts.add(part) }
                var retired = false
                try {
                    var attempt = 0
                    var partDone = false
                    while (attempt < 3 && !partDone &&
                        !stopped() && storageStop.get() == 0
                    ) {
                        attempt++
                        if (attempt > 1) task.retryCount++
                        if (client == null) {
                            client = try {
                                newFtpSession(ftp)
                            } catch (e: Exception) {
                                // Session refused (server cap) or
                                // unreachable: retire quietly; the
                                // verdict is recorded in case EVERY
                                // worker ends up retiring.
                                recordFailureVerdict(e)
                                null
                            }
                            if (client == null) {
                                retired = true
                                break
                            }
                        }
                        val c = client!!
                        part.client = c
                        try {
                            val from = part.pos
                            c.retrieveRange(ftp.path, from, part.end - from) { buf, n ->
                                if (stopped() || storageStop.get() != 0) {
                                    throw InterruptedException("stopped")
                                }
                                target.writeAt(part.pos, buf, 0, n)
                                part.pos += n
                                part.lastAdvanceMs = System.currentTimeMillis()
                                downloadedBytes.addAndGet(n.toLong())
                                limiter?.throttle(n)
                            }
                            partDone = part.pos >= part.end
                        } catch (e: InterruptedException) {
                            // Pause / guard / storage stop — the
                            // loop conditions below exit; the part
                            // keeps its position for the next run.
                            break
                        } catch (e: Exception) {
                            if (isStorageFull(e)) {
                                task.error = Notes.storageFull()
                                task.failureKind = FailureKind.STORAGE_FULL
                                storageStop.set(1)
                                break
                            }
                            if (isStorageGone(e)) {
                                task.error = Notes.storageDeviceGone()
                                task.failureKind = FailureKind.STORAGE_GONE
                                storageStop.set(1)
                                break
                            }
                            if (c.controlDead) {
                                // The control channel desynced —
                                // drop the session; the next
                                // attempt reconnects.
                                runCatching { c.close() }
                                if (client === c) client = null
                                part.client = null
                            }
                            if (attempt >= 3) {
                                recordFailureVerdict(e)
                                if (task.error.isBlank()) task.error = Errors.human(e)
                            }
                            try {
                                Thread.sleep(500L * attempt)
                            } catch (_: InterruptedException) { }
                        }
                    }
                    if (partDone) {
                        synchronized(task.chunkDone) { task.chunkDone[index] = true }
                    }
                } finally {
                    synchronized(parts) { parts.remove(part) }
                }
                // Retired at session setup: leave the loop (the
                // chunk stays unfinished for another worker or
                // the next run).
                if (retired) break
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // ---------- single-stream fallback ----------

    private fun runSingleStream(): TaskStatus {
        // Single-stream downloads cannot resume mid-file; restart cleanly.
        downloadedBytes.set(0)
        task.downloaded = 0
        val stopSampler = AtomicInteger(0)
        val sampler = Thread {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            // v1.5: fixed-period ticks, same as the segmented sampler
            // (chart window, big number and notification all read this
            // one EMA, sampled on an exact 1 s grid).
            var nextTick = System.currentTimeMillis() + 1000
            while (stopSampler.get() == 0 && !stopped()) {
                val waitMs = nextTick - System.currentTimeMillis()
                try {
                    Thread.sleep(waitMs.coerceIn(50, 1000))
                } catch (e: InterruptedException) { break }
                nextTick += 1000
                if (nextTick < System.currentTimeMillis()) {
                    nextTick = System.currentTimeMillis() + 1000
                }
                if (!networkGuard()) {
                    netBlocked = true
                    break
                }
                if (!trafficGuard()) {
                    trafficBlocked = true
                    break
                }
                val now = System.currentTimeMillis()
                val bytes = downloadedBytes.get()
                val dt = max(1L, now - lastTime)
                val speed = (bytes - lastBytes) * 1000 / dt
                task.speedBps = if (task.speedBps == 0L) speed else (task.speedBps * 3 + speed) / 4
                recordSpeed(task.speedBps)
                task.downloaded = bytes
                // v3.5: one whole-file connection — the
                // details view's snapshot is this single entry
                // (chunkIndex -1, see ConnInfo).
                task.connInfos = listOf(
                    ConnInfo(-1, bytes, task.totalSize, task.speedBps)
                )
                lastBytes = bytes
                lastTime = now
                onProgress(task)
            }
        }
        sampler.isDaemon = true
        sampler.start()
        try {
            val c = openFollowingRedirects(task.url) { }
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw HttpFailureException(code)
            }
            // v4.4 (audit round 29): this path had NO HTML
            // check at all. With a KNOWN total the length
            // binding below still catches a portal page; with
            // an UNKNOWN total (the probe saw a chunked 200)
            // the page's own length used to be ADOPTED as the
            // total and the login page saved as a DONE file.
            // The Content-Type check now runs before a single
            // byte is written, and the copy loop sniffs the
            // first block when the total is unknown (a portal
            // that lies about its Content-Type too).
            if (PortalDetect.isHtmlContentType(c.getHeaderField("Content-Type"))) {
                c.disconnect()
                throw PortalBlockedException()
            }
            // v2.4 audit round 10: this GET is a SECOND request —
            // the probe's 200 body was thrown away — so bind it to
            // the probed file before a single byte is written. A
            // 206 answer to a request that carried no Range is
            // only usable if it starts at byte 0 and its total is
            // the probed one; a 200 whose length disagrees with
            // the probe means the server swapped the file between
            // the two requests (the old code trusted whichever
            // body arrived and could call a different, shorter or
            // longer file "done").
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val cr = c.getHeaderField("Content-Range") ?: ""
                if (contentRangeStart(cr) != 0L) {
                    c.disconnect()
                    throw IllegalStateException("服务器返回的分片位置不对，已停下")
                }
                val crTotal = contentRangeTotal(cr)
                if (crTotal > 0) {
                    if (task.totalSize > 0 && crTotal != task.totalSize) {
                        c.disconnect()
                        throw IllegalStateException(Notes.fileChangedSize())
                    }
                    task.totalSize = crTotal
                }
            } else {
                val len = c.contentLengthLong
                if (len > 0) {
                    if (task.totalSize > 0 && len != task.totalSize) {
                        c.disconnect()
                        throw IllegalStateException(Notes.fileChangedSize())
                    }
                    task.totalSize = len
                }
                val respEtag = c.getHeaderField("ETag") ?: ""
                if (etagChanged(task.etag, respEtag)) {
                    c.disconnect()
                    throw IllegalStateException(Notes.fileChangedValidator())
                }
            }
            // v4.0: same keep-alive rule as downloadRange — a
            // body read to EOF can go back to the pool; only a
            // stopped (cut-short) read disconnects.
            var eof = false
            c.inputStream.use { input ->
                target.openStream().use { out ->
                    val buf = ByteArray(bufferSizeFor(1))
                    var firstBlock = true
                    while (!stopped()) {
                        val n = input.read(buf)
                        if (n < 0) {
                            eof = true
                            break
                        }
                        // v4.4 (audit round 29): unknown-total
                        // adoption guard — judge the first
                        // block BEFORE it is written; once a
                        // total is known the length binding
                        // above is the guard and binary file
                        // heads are never sniffed.
                        if (firstBlock) {
                            firstBlock = false
                            if (task.totalSize <= 0 &&
                                PortalDetect.looksLikeHtml(buf, n)
                            ) {
                                c.disconnect()
                                throw PortalBlockedException()
                            }
                        }
                        out.write(buf, 0, n)
                        downloadedBytes.addAndGet(n.toLong())
                        // v4.1 audit round 26: a fixed-length
                        // stream is bounded by the platform, but
                        // a CHUNKED response (no Content-Length)
                        // is not — with a total known from the
                        // probe it could keep sending past it,
                        // and the old ">=" completion check below
                        // would call the oversized file DONE.
                        if (DeclaredLength.exceedsKnownTotal(
                                task.totalSize, downloadedBytes.get()
                            )
                        ) {
                            throw IllegalStateException("服务器发来的数据比声明的文件还多，已停下")
                        }
                        limiter?.throttle(n)
                    }
                }
            }
            // v1.8: the stream ran to its end unstopped — seal it.
            if (!stopped()) runCatching { target.sync() }
            if (!eof) c.disconnect()
        } finally {
            stopSampler.set(1)
            sampler.join(1500)
            task.speedBps = 0
            task.downloaded = downloadedBytes.get()
        }
        return when {
            trafficBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = Notes.trafficGuardSwitched(task.totalSize)
            }
            netBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = Notes.wifiOnlyPaused()
            }
            paused -> TaskStatus.PAUSED.also { task.status = it }
            task.totalSize > 0 && task.downloaded >= task.totalSize ->
                TaskStatus.DONE.also { task.status = it }
            task.totalSize <= 0 && !paused ->
                TaskStatus.DONE.also { task.status = it }
            else -> TaskStatus.FAILED.also {
                task.status = it
                if (task.error.isBlank()) task.error = "下载中断"
            }
        }
    }

    /**
     * Audit round 28: the storage medium itself went away or
     * turned read-only mid-run (SD card pulled, volume
     * unmounted). The OS surfaces it as an errno-flavoured
     * IOException ("pwrite failed: EROFS (Read-only file
     * system)"); without this check Errors.human calls any
     * IOException a network problem and sends the user off to
     * debug their router while the card sits on the desk.
     */
    private fun isStorageGone(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val m = cur.message ?: ""
            if (m.contains("Read-only file system", true) || m.contains("EROFS") ||
                m.contains("No such device", true) || m.contains("ENODEV") ||
                m.contains("Input/output error", true) || m.contains("EIO")
            ) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * v4.5 (audit round 30): record every raw-failure verdict
     * for [e] on the task, at a site where the original
     * exception still exists — the v4.4 netOutage/portalHit
     * flags plus the structured, PERSISTED failure kind and
     * HTTP code (see FailureKind). Consumers downstream
     * (FailureAdvice, the auto-retry gate, the service's
     * parks) judge these verdicts, never the humanized
     * task.error text that is composed right after.
     */
    private fun recordFailureVerdict(e: Throwable) {
        if (isPortalBlocked(e)) task.portalHit = true
        if (NetRetry.isNetworkError(e)) task.netOutage = true
        val kind = when {
            isStorageFull(e) -> FailureKind.STORAGE_FULL
            isStorageGone(e) -> FailureKind.STORAGE_GONE
            else -> FailureClassifier.ofThrowable(e)
        }
        if (kind != FailureKind.UNKNOWN) task.failureKind = kind
        val code = FailureClassifier.httpCodeOf(e)
        if (code != 0) task.failureHttpCode = code
    }

    /** v4.4 (audit round 29): did this failure chain pass
     *  through a [PortalBlockedException]? */
    private fun isPortalBlocked(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is PortalBlockedException) return true
            cur = cur.cause
        }
        return false
    }

    private fun isStorageFull(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val m = cur.message ?: ""
            if (m.contains("No space left", true) || m.contains("ENOSPC") ||
                m.contains("存储空间不足")
            ) return true
            cur = cur.cause
        }
        return false
    }

    companion object {
        const val MAX_THREADS = 64
        /** v4.8: FTP segmented ceiling — the single source is
         *  [FtpSegmented.MAX_CONNECTIONS] (see its doc for why 8);
         *  this alias exists so DownloadCenter's desire table
         *  reads in engine constants like the other branches. */
        const val FTP_MAX_CONNECTIONS = FtpSegmented.MAX_CONNECTIONS
        const val MIN_SPLIT = 256L * 1024
        const val BUFFER_SIZE = 128 * 1024
        /** v1.7: was 6 — long CDN redirect chains hit it; loops are
         *  detected separately, so a higher budget is safe. */
        const val MAX_REDIRECTS = 10
        /** Pre-v4.6 flat stall patience; since v4.6 the live
         *  threshold is StallPolicy's speed-aware curve, whose
         *  maximum this remains (kept as the single source for
         *  that number). */
        const val STALL_EVICT_MS = StallPolicy.MAX_THRESHOLD_MS
        /** v1.6 HLS segment size cap. */
        const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
        /** v4.3: the widest an HLS fetch pool may ever get (see
         *  HlsPlan for why 16: segments are small and short, the
         *  sequential writer and the ~48 MB in-flight budget bind
         *  long before width past this pays, and more mostly
         *  invites per-IP rate limiting). Replaces the pre-v4.3
         *  fixed HLS_PARALLEL = 4 / HLS_WINDOW = 6 pair — the
         *  pool is now sized to this max and the live width
         *  (plan + scaler + allowance gate) is the window. */
        const val HLS_MAX_PARALLEL = 16
        /** v1.7: cap on fetched-but-unwritten HLS segment bytes. */
        const val HLS_BUFFER_BUDGET_BYTES = 48L * 1024 * 1024

        /** True when the URL path ends in .m3u8 (query/fragment aside). */
        fun isHlsUrl(url: String): Boolean =
            url.substringBefore('?').substringBefore('#')
                .endsWith(".m3u8", ignoreCase = true)

        /** v2.4 audit round 10: the MPEG-TS sync byte every .ts
         *  segment starts with. */
        const val TS_SYNC_BYTE: Byte = 0x47

        /** True when the URL path ends in .ts (query/fragment aside). */
        fun isTsSegment(url: String): Boolean =
            url.substringBefore('?').substringBefore('#')
                .endsWith(".ts", ignoreCase = true)

        /**
         * v2.4 audit round 10: the total length out of a
         * "bytes start-end/total" Content-Range value; -1 when the
         * header is absent, malformed, or carries "*" (unknown).
         * Pure — unit-tested.
         */
        fun contentRangeTotal(contentRange: String?): Long {
            if (contentRange.isNullOrBlank()) return -1
            return contentRange.substringAfter('/', "").trim().toLongOrNull() ?: -1
        }

        /** The start offset out of a Content-Range value; -1 when unparsable. */
        fun contentRangeStart(contentRange: String?): Long {
            if (contentRange.isNullOrBlank()) return -1
            return contentRange.removePrefix("bytes ")
                .substringBefore('-').trim().toLongOrNull() ?: -1
        }

        /**
         * v2.4 audit round 10: mid-download identity verdict. Only
         * a STRONG ETag testifies byte identity — a weak ("W/…")
         * tag explicitly does not, per RFC 9110 — so only a
         * strong-vs-strong mismatch condemns the response. Either
         * side missing or weak = no verdict (false). Pure —
         * unit-tested.
         */
        fun etagChanged(stored: String, current: String): Boolean {
            if (stored.isBlank() || current.isBlank()) return false
            if (stored.startsWith("W/") || current.startsWith("W/")) return false
            return stored != current
        }
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /**
         * v1.5 buffer sizing: the per-connection read buffer shrinks
         * as the worker count grows, so total read-buffer memory
         * stays bounded instead of scaling linearly with threads.
         * Few threads keep the full 128 KB (fewest syscalls per MB);
         * many threads trade a little per-connection efficiency for
         * a hard memory cap.
         *
         * v4.2 memory account (the total budget doubled 2 MB ->
         * 4 MB when MAX_THREADS went 32 -> 64): at 64 connections
         * each buffer is 64 KB, so one task's worst case is
         * 64 x 64 KB = 4 MB; at 32 connections each buffer is back
         * at the 128 KB ceiling.
         *
         * Audit round 27 corrected the app-wide half of this
         * account (the earlier "≲ 8 MB" line quietly assumed every
         * connection runs a 64 KB buffer, which only holds when
         * every task's target is 64): downloadRange sizes each
         * buffer by [bufferThreadsFor] — max(target, LIVE worker
         * count) — so one task's live buffers NEVER exceed
         * count x (4 MB / count) = 4 MB, even while surplus
         * workers drain after a shrink, and even when the global
         * allowance lets fewer workers run than the target (the
         * buffers then just come out larger per worker, total
         * unchanged). App-wide the segmented read buffers are
         * therefore bounded by 4 MB x concurrent tasks (at most
         * 5, so 20 MB absolute), and in steady state by the
         * global connection cap x the 128 KB per-buffer ceiling:
         * the default cap 32 -> <= 4 MB; the maximum cap 128 ->
         * <= 16 MB, reachable only with several low-target tasks
         * sharing a raised cap; all-target-64 at cap 128 ->
         * 128 x 64 KB = 8 MB. (Single-stream / HLS buffers are
         * separate, fixed-size allocations, not this account.)
         * Connection reuse itself is the platform's keep-alive pool:
         * every path here fully consumes or explicitly closes its
         * streams, which is what lets HttpURLConnection recycle the
         * underlying sockets — audited in v1.5, no change needed.
         */
        fun bufferSizeFor(threads: Int): Int {
            val t = threads.coerceAtLeast(1)
            val budget = 4 * 1024 * 1024 / t
            return budget.coerceIn(32 * 1024, BUFFER_SIZE)
        }

        /**
         * v4.2 audit round 27: the thread count a new read buffer
         * is sized by — the scaler target, or the LIVE worker
         * count when more workers than the target are still
         * draining (post-shrink surplus, endgame tails). Sizing
         * by the larger of the two is what makes the per-task
         * 4 MB account in [bufferSizeFor]'s doc hold at every
         * moment, not just in steady state: with n live buffers
         * each sized by >= n, the total stays <= n x (4 MB / n).
         * Pure — unit-tested (the bound is swept over the whole
         * target x live-count grid in NetRetryTest).
         */
        fun bufferThreadsFor(target: Int, activeWorkers: Int): Int =
            maxOf(target, activeWorkers).coerceAtLeast(1)

        fun fileNameFrom(contentDisposition: String?, url: String): String {
            if (!contentDisposition.isNullOrBlank()) {
                // RFC 5987: filename*=charset'lang'percent-encoded —
                // v1.7: the charset is whatever the server says
                // (GBK''… is common on older Chinese sites), not
                // always UTF-8.
                val star = Regex("filename\\*=([^']*)'[^']*'([^;]+)")
                    .find(contentDisposition)
                if (star != null) {
                    val charsetName = star.groupValues[1].ifBlank { "UTF-8" }
                    val raw = star.groupValues[2]
                    val decoded = runCatching {
                        val cs = runCatching {
                            java.nio.charset.Charset.forName(charsetName)
                        }.getOrDefault(Charsets.UTF_8)
                        java.net.URLDecoder.decode(raw, cs.name())
                    }.getOrDefault(raw)
                    return sanitizeFileName(decoded)
                }
                val plain = Regex("filename=\"?([^\";]+)\"?").find(contentDisposition)
                if (plain != null) {
                    // Servers that put RAW (non-encoded) UTF-8 or GBK
                    // bytes into the header reach us as ISO-8859-1
                    // mojibake through HttpURLConnection — recover
                    // the original text before using it as a name.
                    return sanitizeFileName(decodeRawHeader(plain.groupValues[1].trim()))
                }
            }
            val path = runCatching { URL(url).path }.getOrDefault(url)
            var name = path.substringAfterLast('/').substringBefore('?')
            // v1.6: a SourceForge-style "…/file.zip/download" hop —
            // the last segment is the literal word "download" and the
            // real file name is the segment before it.
            if (name.equals("download", true)) {
                val prev = path.substringBeforeLast('/').substringAfterLast('/')
                if (prev.contains('.')) name = prev
            }
            // A literal '+' in a URL path is a plus, not a space —
            // protect it before URLDecoder gets to translate it.
            val decoded = runCatching {
                java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(name)
            return sanitizeFileName(decoded)
        }

        /**
         * A file NAME must never carry path separators: a hostile
         * or sloppy Content-Disposition ("../../x", "a/b.zip")
         * would otherwise leak directory structure into the public
         * Download folder naming. Keeps the last segment only.
         */
        fun sanitizeFileName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .trim().trim('"').trim()
                .ifBlank { "download.bin" }

        /**
         * Undo the ISO-8859-1 decoding HttpURLConnection applies to
         * header bytes: if the text is non-ASCII but every char fits
         * in a byte, re-encode to the original bytes and try UTF-8
         * first, then GBK (the two encodings real servers use for
         * raw filename bytes). Anything else is returned untouched.
         */
        fun decodeRawHeader(value: String): String {
            if (value.all { it.code < 0x80 }) return value
            if (value.any { it.code > 0xFF }) return value
            val bytes = value.toByteArray(Charsets.ISO_8859_1)
            val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            if (utf8 != null && !utf8.contains('\uFFFD')) return utf8
            return runCatching {
                String(bytes, java.nio.charset.Charset.forName("GBK"))
            }.getOrDefault(value)
        }
    }
}

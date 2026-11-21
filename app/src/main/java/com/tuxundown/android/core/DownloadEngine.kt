package com.tuxundown.android.core

import java.io.InputStream
import java.net.HttpURLConnection
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
 *   every 2 s while the measured speed keeps growing by more than 10%;
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

    private val downloadedBytes = AtomicLong(task.downloaded)
    private val nextChunk = AtomicInteger(0)
    private val activeWorkers = AtomicInteger(0)

    private class ActiveChunk(val index: Int, start: Long, end: Long) {
        @Volatile var pos: Long = start // next byte to write
        @Volatile var end: Long = end   // exclusive; may shrink when split
        val start: Long = start
        /** Live connection, so the sampler can evict a stalled worker. */
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var lastAdvanceMs: Long = System.currentTimeMillis()
    }

    private val active = mutableListOf<ActiveChunk>()
    private val activeLock = Object()
    /** Outstanding parts per chunk index (a chunk split by stealing has 2). */
    private val chunkParts = mutableMapOf<Int, Int>()

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
        val runStartMs = System.currentTimeMillis()
        try {
            // v1.7: FTP links take their own path (hand-written
            // FtpClient: PASV, SIZE/REST probe, single stream with
            // REST resume) — HTTP probing would be meaningless.
            if (FtpClient.isFtpUrl(task.url)) {
                return runFtp()
            }
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
                task.note = "流量保护：当前是流量网络，这个文件有 ${Format.size(task.totalSize)}，" +
                    "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"
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
                }
                return result
            } finally {
                runCatching { target.close() }
            }
        } catch (e: Exception) {
            // v2.2 audit round 8: never show a raw class name or an
            // English library message on the card — Errors.human
            // keeps our own plain sentences and maps the rest.
            task.error = Errors.human(e)
            task.status = if (paused) TaskStatus.PAUSED else TaskStatus.FAILED
            return task.status
        } finally {
            task.elapsedMs += System.currentTimeMillis() - runStartMs
            if (task.status == TaskStatus.DONE && task.finishedAt == 0L) {
                task.finishedAt = System.currentTimeMillis()
            }
            running = false
            onSave()
            onProgress(task)
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
     * headers (UA / Cookie / Referer) and the caller's own headers
     * (Range) are re-applied on every hop. Returns the connection
     * after the final response code has been read.
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
            task.headers["Cookie"]?.let { c.setRequestProperty("Cookie", it) }
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
        if (contentType.contains("html", true) || contentType.contains("xhtml", true)) {
            c.disconnect()
            throw IllegalStateException(
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
                val total = range.substringAfter('/', "").toLongOrNull() ?: -1
                task.totalSize = total
                if (total < 0) task.singleThreadOnly = true
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
                throw IllegalStateException(Errors.httpMessage(code))
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
        if (task.chunkSize <= 0) {
            task.chunkSize = ChunkPlanner.chunkSizeFor(total, 16)
            task.chunkDone = BooleanArray(ChunkPlanner.chunkCount(total, task.chunkSize))
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
        downloadedBytes.set(ChunkPlanner.downloadedOf(task.chunkDone, total, task.chunkSize))
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
                task.downloaded = bytes
                ticks++
                // Evict stalled connections: a part that has not
                // advanced for 12 s while the task as a whole IS
                // moving gets its connection dropped; the worker
                // retries the part on a fresh connection, resuming
                // from the part's current offset.
                if (speed > 0) {
                    synchronized(activeLock) {
                        for (ac in active) {
                            if (now - ac.lastAdvanceMs > STALL_EVICT_MS) {
                                ac.lastAdvanceMs = now // don't spam
                                runCatching { ac.connection?.disconnect() }
                            }
                        }
                    }
                }
                // Every 2 s the ThreadScaler re-decides the worker
                // count (v2.3): opening ladder first, then steady
                // grow/shrink with verdicts and learned bounds.
                if (ticks % 2 == 0) {
                    val sc = scaler
                    if (sc != null) {
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
                            task.note = "加线程后速度不再提升，服务器可能按 IP 总限速，已用最优线程数。"
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
                    onSave()
                }
                lastBytes = bytes
                lastTime = now
                onProgress(task)
            }
        }
        sampler.isDaemon = true
        sampler.start()

        // Audit A9: a FRESH start re-probes from 4 threads (yesterday's
        // optimum says nothing about today's network); a resume keeps
        // the persisted count but starts at most at 8 and lets the
        // prober grow it again if the network wants more.
        val resuming = firstOpen > 0 || downloadedBytes.get() > 0
        // v1.5: a FRESH start with a remembered optimum for this host
        // begins at that count and skips probing altogether — the
        // first ~6 s of ramp-up are pure waste on a known server.
        val hint = if (!resuming && initialThreadsHint in 2..MAX_THREADS) {
            initialThreadsHint
        } else {
            0
        }
        val initial = when {
            resuming -> task.threads.coerceIn(1, 8)
            hint > 0 -> hint
            else -> 4
        }
        // v2.3: the scaler owns the count from here. A hint start
        // skips the ladder (steady state from the first tick); any
        // other start ladders first — a resume included, since the
        // network may have changed since the count was persisted.
        val sc = ThreadScaler(initial, probing = hint == 0)
        scaler = sc
        if (hint > 0 && task.note.isBlank()) {
            task.note = "这个服务器上次的最佳线程数是 $hint，已直接用它下载，跳过试探。"
        }
        task.threads = sc.target
        repeat(sc.target) { spawnWorker(count) }

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
                task.note = "流量保护：网络已切到流量，这个文件有 ${Format.size(task.totalSize)}，" +
                    "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"
                task.status = TaskStatus.PAUSED
                task.status
            }
            netBlocked -> {
                task.note = "网络已切到流量（已开启「仅 WiFi 下载」），已暂停；回到 WiFi 后点继续即可续传。"
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
            return range.substringAfter('/', "").trim().toLongOrNull() ?: -1
        } finally {
            c.disconnect()
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

    private fun workerLoop(chunkCount: Int) {
        while (!stopped()) {
            // v2.3 dynamic scaling: when the scaler has lowered the
            // target below the live worker count, surplus workers
            // leave HERE — between chunks, never mid-chunk — so a
            // shrink can never tear a part in half or orphan its
            // bytes. Two workers racing the check can dip the count
            // one below target; the sampler's top-up corrects that
            // within one evaluation (2 s).
            val sc = scaler
            if (sc != null && activeWorkers.get() > sc.target) return
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
            val r = ChunkPlanner.rangeOf(task.totalSize, task.chunkSize, index)
            val ac = ActiveChunk(index, r.first, r.last + 1)
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
        synchronized(activeLock) {
            if (pendingTails.isNotEmpty()) return pendingTails.removeFirst()
            val idle = (activeWorkers.get() - active.size).coerceAtLeast(1)
            val snapshot = active.map { Endgame.Range(it.index, it.pos, it.end) }
            val plan = Endgame.planSplit(snapshot, idle, MIN_SPLIT) ?: return null
            val owner = active.firstOrNull { it.index == plan.index } ?: return null
            val live = Endgame.planSplit(
                listOf(Endgame.Range(owner.index, owner.pos, owner.end)),
                idle, MIN_SPLIT,
            ) ?: return null
            val origEnd = owner.end
            owner.end = live.cuts.first()
            val bounds = live.cuts + origEnd
            val tails = ArrayList<ActiveChunk>(bounds.size - 1)
            for (i in 0 until bounds.size - 1) {
                tails.add(ActiveChunk(owner.index, bounds[i], bounds[i + 1]))
            }
            chunkParts[owner.index] = (chunkParts[owner.index] ?: 1) + tails.size
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
                downloadRange(ac, sourceFor(ac, attempt))
                ok = ac.pos >= ac.end
            } catch (e: Exception) {
                if (isStorageFull(e)) {
                    // No point retrying into a full disk (audit A10).
                    task.error = "手机存储空间不足，下载已停下。清出空间后点继续即可续传。"
                    break
                }
                if (attempt >= 3) {
                    task.error = Errors.human(e)
                }
                try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) { }
            }
        }
        synchronized(activeLock) { active.remove(ac) }
        ac.connection = null
        if (ok && ac.index >= 0) {
            synchronized(activeLock) {
                val left = (chunkParts[ac.index] ?: 1) - 1
                if (left <= 0) {
                    chunkParts.remove(ac.index)
                    synchronized(task.chunkDone) { task.chunkDone[ac.index] = true }
                } else {
                    chunkParts[ac.index] = left
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
        if (code == HttpURLConnection.HTTP_OK) {
            // Server ignored Range mid-download: cannot segment safely.
            c.disconnect()
            throw IllegalStateException("服务器不支持 Range")
        }
        if (code != HttpURLConnection.HTTP_PARTIAL) {
            c.disconnect()
            throw IllegalStateException(Errors.httpMessage(code))
        }
        // A redirect chain that lands on a different byte range than
        // asked would corrupt the file; Content-Range must match.
        val cr = c.getHeaderField("Content-Range") ?: ""
        val crStart = cr.removePrefix("bytes ").substringBefore('-').trim().toLongOrNull()
        if (crStart != null && crStart != from) {
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
        try {
            input = c.inputStream
            val buf = ByteArray(bufferSizeFor(task.threads))
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
        } finally {
            runCatching { input?.close() }
            runCatching { c.disconnect() }
            if (ac.connection == c) ac.connection = null
        }
    }

    // ---------- HLS (m3u8) path (v1.6) ----------

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
                throw IllegalStateException(Errors.httpMessage(c.responseCode))
            }
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
                String(out.toByteArray(), Charsets.UTF_8)
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
                throw IllegalStateException(Errors.httpMessage(c.responseCode))
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
        if (offset == 0L) target.truncate(0)

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

        val pool = java.util.concurrent.Executors.newFixedThreadPool(HLS_PARALLEL) { r ->
            Thread(r).apply { isDaemon = true }
        }
        // v1.7 perf: in-flight BYTE budget. The window alone (6
        // segments) does not bound memory: each segment may be up to
        // MAX_SEGMENT_BYTES (64 MB), so six finished-but-unwritten
        // segments could theoretically pin ~384 MB. Fetched bytes now
        // count against a ~48 MB budget; prefetch pauses while the
        // buffer is over budget and resumes as the writer drains it.
        // The segment AT the write cursor is always submittable, so
        // the writer can never starve waiting for a fetch the budget
        // refused to start.
        val bufferedBytes = AtomicLong(0)
        var failure: Exception? = null
        try {
            val futures = HashMap<Int, java.util.concurrent.Future<ByteArray>>()
            var submitIndex = writeIndex
            while (writeIndex < segments.size && !stopped()) {
                while (submitIndex < segments.size &&
                    submitIndex < writeIndex + HLS_WINDOW && !stopped() &&
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
                task.note = "流量保护：网络已切到流量，已自动暂停省流量。回到 WiFi 点继续即可续传。"
            }
            netBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = "网络已切到流量（已开启「仅 WiFi 下载」），已暂停；回到 WiFi 后点继续即可续传。"
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

    // ---------- FTP path (v1.7) ----------

    /**
     * Download an ftp:// file with the hand-written [FtpClient]:
     * one data stream (FTP has no Range-style chunking), resumed
     * with REST when the server supports it — the resume offset is
     * the length the existing .part already has, measured through
     * the target itself. A server file whose SIZE changed since
     * the last run restarts the transfer instead of mixing bytes.
     */
    private fun runFtp(): TaskStatus {
        val ftp = FtpClient.parseUrl(task.url)
            ?: throw IllegalStateException("FTP 地址格式不对，看不懂这个链接")
        if (task.fileName.isBlank()) {
            task.fileName = fileNameFrom(null, task.url)
        }
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
            val total = client.size(ftp.path)
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
                task.note = "流量保护：当前是流量网络，这个文件有 ${Format.size(task.totalSize)}，" +
                    "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"
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
            val rest = client.restSupported()
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
                appendNote("这个 FTP 服务器不支持断点续传（REST），暂停后重新开始会从头下载。")
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
                task.note = "流量保护：网络已切到流量，这个文件有 ${Format.size(task.totalSize)}，" +
                    "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"
            }
            netBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = "网络已切到流量（已开启「仅 WiFi 下载」），已暂停；回到 WiFi 后点继续即可续传。"
            }
            paused -> TaskStatus.PAUSED.also { task.status = it }
            task.totalSize > 0 && task.downloaded >= task.totalSize ->
                TaskStatus.DONE.also { task.status = it }
            task.totalSize <= 0 ->
                TaskStatus.DONE.also { task.status = it }
            else -> TaskStatus.FAILED.also {
                task.status = it
                if (task.error.isBlank()) task.error = "FTP 下载中断"
            }
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
                throw IllegalStateException(Errors.httpMessage(code))
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
                        throw IllegalStateException(
                            "服务器在下载途中换了文件（大小和刚才探到的对不上），已停下；点继续会重新开始"
                        )
                    }
                    task.totalSize = crTotal
                }
            } else {
                val len = c.contentLengthLong
                if (len > 0) {
                    if (task.totalSize > 0 && len != task.totalSize) {
                        c.disconnect()
                        throw IllegalStateException(
                            "服务器在下载途中换了文件（大小和刚才探到的对不上），已停下；点继续会重新开始"
                        )
                    }
                    task.totalSize = len
                }
                val respEtag = c.getHeaderField("ETag") ?: ""
                if (etagChanged(task.etag, respEtag)) {
                    c.disconnect()
                    throw IllegalStateException(
                        "服务器在下载途中换了文件（校验标记对不上），已停下；点继续会重新开始"
                    )
                }
            }
            c.inputStream.use { input ->
                target.openStream().use { out ->
                    val buf = ByteArray(bufferSizeFor(1))
                    while (!stopped()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloadedBytes.addAndGet(n.toLong())
                        limiter?.throttle(n)
                    }
                }
            }
            // v1.8: the stream ran to its end unstopped — seal it.
            if (!stopped()) runCatching { target.sync() }
            c.disconnect()
        } finally {
            stopSampler.set(1)
            sampler.join(1500)
            task.speedBps = 0
            task.downloaded = downloadedBytes.get()
        }
        return when {
            trafficBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = "流量保护：网络已切到流量，这个文件有 ${Format.size(task.totalSize)}，" +
                    "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"
            }
            netBlocked -> TaskStatus.PAUSED.also {
                task.status = it
                task.note = "网络已切到流量（已开启「仅 WiFi 下载」），已暂停；回到 WiFi 后点继续即可续传。"
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
        const val MAX_THREADS = 32
        const val MIN_SPLIT = 256L * 1024
        const val BUFFER_SIZE = 128 * 1024
        /** v1.7: was 6 — long CDN redirect chains hit it; loops are
         *  detected separately, so a higher budget is safe. */
        const val MAX_REDIRECTS = 10
        const val STALL_EVICT_MS = 12_000L
        /** v1.6 HLS: segments in flight / prefetch window / size cap. */
        const val HLS_PARALLEL = 4
        const val HLS_WINDOW = 6
        const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
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
         * stays bounded (~2 MB worst case at 32 threads) instead of
         * scaling linearly with threads. Few threads keep the full
         * 128 KB (fewest syscalls per MB); many threads trade a
         * little per-connection efficiency for a hard memory cap.
         * Connection reuse itself is the platform's keep-alive pool:
         * every path here fully consumes or explicitly closes its
         * streams, which is what lets HttpURLConnection recycle the
         * underlying sockets — audited in v1.5, no change needed.
         */
        fun bufferSizeFor(threads: Int): Int {
            val t = threads.coerceAtLeast(1)
            val budget = 2 * 1024 * 1024 / t
            return budget.coerceIn(32 * 1024, BUFFER_SIZE)
        }

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

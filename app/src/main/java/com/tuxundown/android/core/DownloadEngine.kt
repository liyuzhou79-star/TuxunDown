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
 *   On top of that, an idle worker may split the unfinished tail of the
 *   slowest in-flight chunk and take half of it.
 * - Auto thread probing: starts low, then doubles 4 -> 8 -> 16 -> 32
 *   every 2 s while the measured speed keeps growing by more than 10%;
 *   stops at the first plateau.
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
) {
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
            if (task.totalSize <= 0 || task.fileName.isBlank()) probe()
            // Traffic protection can only judge once the size is known.
            if (!trafficGuard()) {
                trafficBlocked = true
                task.status = TaskStatus.PAUSED
                task.note = "流量保护：当前是流量网络，这个文件有 ${Format.size(task.totalSize)}，" +
                    "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"
                return task.status
            }
            if (task.totalSize == 0L) {
                // A genuinely empty file: nothing to transfer.
                target = targetProvider()
                try { target.truncate(0) } finally { runCatching { target.close() } }
                task.downloaded = 0
                task.status = TaskStatus.DONE
                return task.status
            }
            validateResume()
            target = targetProvider()
            try {
                if (task.singleThreadOnly || task.totalSize <= 0) {
                    return runSingleStream()
                }
                return runSegmented()
            } finally {
                runCatching { target.close() }
            }
        } catch (e: Exception) {
            task.error = e.message ?: e.javaClass.simpleName
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
        repeat(MAX_REDIRECTS + 1) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", task.headers["User-Agent"] ?: DEFAULT_UA)
            task.headers["Cookie"]?.let { c.setRequestProperty("Cookie", it) }
            task.headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
            apply(c)
            val code = c.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) {
                    throw IllegalStateException("服务器跳转但没给地址（HTTP $code）")
                }
                current = runCatching { URL(URL(current), loc).toString() }
                    .getOrElse { throw IllegalStateException("服务器给的跳转地址无效") }
                return@repeat
            }
            return c
        }
        throw IllegalStateException("跳转次数太多，已停下")
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
            }
            HttpURLConnection.HTTP_OK -> {
                task.totalSize = c.contentLengthLong
                task.singleThreadOnly = true
                task.note = "服务器不支持分片下载（Range），已用单线程下载，无法加速。"
            }
            else -> throw IllegalStateException("服务器返回 HTTP $code")
        }
        c.disconnect()
        onSave()
    }

    /**
     * Audit A4: before resuming a partially downloaded file, ask the
     * server whether the file is still the same one. If a stored
     * validator (ETag / Last-Modified) or the total size disagrees,
     * the old partial bytes belong to a different file version —
     * drop the chunk state and start the byte ranges over (the .part
     * file itself is truncated by the segmented run that follows).
     * When the server offers no validators either time, resume as-is.
     */
    private fun validateResume() {
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
            c.disconnect()
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
            var lastSpeed = 0L
            var plateauSteps = 0
            var ticks = 0
            while (stopSampler.get() == 0 && !stopped()) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
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
                // Every 2 s decide whether more threads still help.
                if (ticks % 2 == 0) {
                    val cur = task.speedBps
                    if (task.threads < MAX_THREADS && nextChunk.get() < count) {
                        if (lastSpeed == 0L || cur > lastSpeed * 11 / 10) {
                            val want = (task.threads * 2).coerceAtMost(MAX_THREADS)
                            val add = want - activeWorkers.get()
                            if (add > 0) repeat(add) { spawnWorker(count) }
                            task.threads = want
                            plateauSteps = 0
                        } else {
                            plateauSteps++
                            if (plateauSteps >= 1 && task.note.isBlank() && task.threads <= 8) {
                                task.note = "加线程后速度不再提升，服务器可能按 IP 总限速，已用最优线程数。"
                            }
                        }
                    }
                    lastSpeed = cur
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
        val initial = if (resuming) {
            task.threads.coerceIn(1, 8)
        } else {
            4
        }
        task.threads = initial
        repeat(initial) { spawnWorker(count) }

        // Wait until all workers exit (done or paused).
        while (activeWorkers.get() > 0) {
            try { Thread.sleep(200) } catch (e: InterruptedException) { break }
        }
        stopSampler.set(1)
        sampler.join(1500)

        task.speedBps = 0
        task.downloaded = downloadedBytes.get()
        val doneCount = task.chunkDone.count { it }
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

    private fun spawnWorker(chunkCount: Int) {
        activeWorkers.incrementAndGet()
        val t = Thread {
            try {
                workerLoop(chunkCount)
            } finally {
                activeWorkers.decrementAndGet()
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun workerLoop(chunkCount: Int) {
        while (!stopped()) {
            var index = nextChunk.getAndIncrement()
            if (index >= chunkCount) {
                // No fresh chunks: try to steal the tail of the slowest
                // in-flight chunk before giving up.
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

    /** Split the largest remaining in-flight chunk; returns the tail half. */
    private fun stealTail(): ActiveChunk? {
        synchronized(activeLock) {
            var best: ActiveChunk? = null
            var bestRemaining = MIN_SPLIT
            for (ac in active) {
                val remaining = ac.end - ac.pos
                if (remaining > bestRemaining) {
                    bestRemaining = remaining
                    best = ac
                }
            }
            val target = best ?: return null
            val mid = target.pos + bestRemaining / 2
            val tail = ActiveChunk(target.index, mid, target.end)
            target.end = mid
            chunkParts[target.index] = (chunkParts[target.index] ?: 1) + 1
            active.add(tail)
            return tail
        }
    }

    private fun downloadActive(ac: ActiveChunk) {
        var attempt = 0
        var ok = false
        while (attempt < 3 && !stopped() && !ok) {
            attempt++
            if (attempt > 1) task.retryCount++ // v1.4 stats: chunk-level retries
            try {
                downloadRange(ac)
                ok = ac.pos >= ac.end
            } catch (e: Exception) {
                if (isStorageFull(e)) {
                    // No point retrying into a full disk (audit A10).
                    task.error = "手机存储空间不足，下载已停下。清出空间后点继续即可续传。"
                    break
                }
                if (attempt >= 3) {
                    task.error = e.message ?: "分片下载失败"
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

    private fun downloadRange(ac: ActiveChunk) {
        // v1.3: the request starts at the part's CURRENT offset, so a
        // retry (or a stall eviction) keeps every byte already written
        // instead of re-downloading the whole part.
        val from = ac.pos
        val to = ac.end - 1
        if (from > to) return
        val c = openFollowingRedirects(task.url) {
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
            throw IllegalStateException("分片请求返回 HTTP $code")
        }
        // A redirect chain that lands on a different byte range than
        // asked would corrupt the file; Content-Range must match.
        val cr = c.getHeaderField("Content-Range") ?: ""
        val crStart = cr.removePrefix("bytes ").substringBefore('-').trim().toLongOrNull()
        if (crStart != null && crStart != from) {
            c.disconnect()
            throw IllegalStateException("服务器返回的分片位置不对，已重试")
        }
        val input: InputStream = c.inputStream
        val buf = ByteArray(BUFFER_SIZE)
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
        input.close()
        c.disconnect()
        ac.connection = null
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
            while (stopSampler.get() == 0 && !stopped()) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
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
                throw IllegalStateException("服务器返回 HTTP $code")
            }
            if (task.totalSize <= 0) task.totalSize = c.contentLengthLong
            c.inputStream.use { input ->
                target.openStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (!stopped()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloadedBytes.addAndGet(n.toLong())
                        limiter?.throttle(n)
                    }
                }
            }
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
        const val MAX_REDIRECTS = 6
        const val STALL_EVICT_MS = 12_000L
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        fun fileNameFrom(contentDisposition: String?, url: String): String {
            if (!contentDisposition.isNullOrBlank()) {
                val star = Regex("filename\\*=UTF-8''([^;]+)").find(contentDisposition)
                if (star != null) {
                    return runCatching {
                        java.net.URLDecoder.decode(star.groupValues[1], "UTF-8")
                    }.getOrDefault(star.groupValues[1]).trim('"')
                }
                val plain = Regex("filename=\"?([^\";]+)\"?").find(contentDisposition)
                if (plain != null) return plain.groupValues[1].trim()
            }
            val path = runCatching { URL(url).path }.getOrDefault(url)
            val name = path.substringAfterLast('/').substringBefore('?')
            val decoded = runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
            return decoded.ifBlank { "download.bin" }
        }
    }
}

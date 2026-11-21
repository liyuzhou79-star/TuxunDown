package com.tuxundown.android.core

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
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
 * - Auto thread probing: starts at 4 connections, then doubles
 *   8 -> 16 -> 32 every few seconds while the measured speed keeps
 *   growing by more than 10%; stops at the first plateau.
 * - Honest fallback: if the server does not support Range, the task is
 *   downloaded in a single stream and [TaskRecord.note] says so. If more
 *   threads stop helping (speed plateau at the first step), the note
 *   explains the server is likely throttling per IP.
 */
class DownloadEngine(
    private val task: TaskRecord,
    private val file: File,
    private val onProgress: (TaskRecord) -> Unit,
    private val onSave: () -> Unit,
) {
    @Volatile var paused = false
        private set
    @Volatile private var running = false

    private val downloadedBytes = AtomicLong(task.downloaded)
    private val nextChunk = AtomicInteger(0)
    private val activeWorkers = AtomicInteger(0)

    private class ActiveChunk(val index: Int, start: Long, end: Long) {
        @Volatile var pos: Long = start // next byte to write
        @Volatile var end: Long = end   // exclusive; may shrink when split
        val start: Long = start
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
        try {
            if (task.totalSize <= 0 || task.fileName.isBlank()) probe()
            if (task.singleThreadOnly || task.totalSize <= 0) {
                return runSingleStream()
            }
            return runSegmented()
        } catch (e: Exception) {
            task.error = e.message ?: e.javaClass.simpleName
            task.status = if (paused) TaskStatus.PAUSED else TaskStatus.FAILED
            return task.status
        } finally {
            running = false
            onSave()
            onProgress(task)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", task.headers["User-Agent"] ?: DEFAULT_UA)
        task.headers["Cookie"]?.let { c.setRequestProperty("Cookie", it) }
        task.headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
        return c
    }

    private fun probe() {
        val c = open(task.url)
        c.setRequestProperty("Range", "bytes=0-0")
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
        when (code) {
            HttpURLConnection.HTTP_PARTIAL -> {
                val range = c.getHeaderField("Content-Range") ?: ""
                val total = range.substringAfter('/', "").toLongOrNull() ?: -1
                task.totalSize = total
                if (total <= 0) task.singleThreadOnly = true
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

        RandomAccessFile(file, "rw").use { it.setLength(total) }

        // Speed sampler + auto thread probing controller.
        val stopSampler = AtomicInteger(0)
        val sampler = Thread {
            var lastBytes = downloadedBytes.get()
            var lastTime = System.currentTimeMillis()
            var lastSpeed = 0L
            var plateauSteps = 0
            var ticks = 0
            while (stopSampler.get() == 0 && !paused) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                val now = System.currentTimeMillis()
                val bytes = downloadedBytes.get()
                val dt = max(1L, now - lastTime)
                val speed = (bytes - lastBytes) * 1000 / dt
                task.speedBps = if (task.speedBps == 0L) speed else (task.speedBps * 3 + speed) / 4
                recordSpeed(task.speedBps)
                task.downloaded = bytes
                ticks++
                // Every 3 s decide whether more threads still help.
                if (ticks % 3 == 0) {
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

        val initial = task.threads.coerceIn(1, MAX_THREADS)
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
        while (!paused) {
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
        while (attempt < 3 && !paused && !ok) {
            attempt++
            try {
                downloadRange(ac)
                ok = ac.pos >= ac.end
            } catch (e: Exception) {
                if (attempt >= 3) {
                    task.error = e.message ?: "分片下载失败"
                }
                try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) { }
            }
        }
        synchronized(activeLock) { active.remove(ac) }
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
        // A retry restarts this part from its start: un-count bytes the
        // previous attempt already added so progress never over-reports.
        if (ac.pos > ac.start) {
            downloadedBytes.addAndGet(-(ac.pos - ac.start))
            ac.pos = ac.start
        }
        val c = open(task.url)
        c.setRequestProperty("Range", "bytes=${ac.start}-${ac.end - 1}")
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
        val input: InputStream = c.inputStream
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(ac.start)
            val buf = ByteArray(64 * 1024)
            var pos = ac.start
            while (!paused) {
                val limit = ac.end // may shrink when split
                if (pos >= limit) break
                val want = minOf(buf.size.toLong(), limit - pos).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                raf.write(buf, 0, n)
                pos += n
                ac.pos = pos
                downloadedBytes.addAndGet(n.toLong())
            }
        }
        input.close()
        c.disconnect()
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
            while (stopSampler.get() == 0 && !paused) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
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
            val c = open(task.url)
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("服务器返回 HTTP $code")
            }
            if (task.totalSize <= 0) task.totalSize = c.contentLengthLong
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (!paused) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloadedBytes.addAndGet(n.toLong())
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

    companion object {
        const val MAX_THREADS = 32
        const val MIN_SPLIT = 512L * 1024
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

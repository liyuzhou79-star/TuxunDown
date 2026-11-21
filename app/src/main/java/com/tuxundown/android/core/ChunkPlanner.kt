package com.tuxundown.android.core

/**
 * Pure chunk-planning logic for segmented downloads.
 *
 * A file is split into fixed-size chunks. Workers pull chunk indexes from a
 * shared queue, so a worker that finishes early automatically goes back and
 * takes the next unfinished chunk — the slowest region never waits for one
 * dedicated connection. Chunk completion is tracked in a bitset so a paused
 * task can resume exactly where it stopped.
 */
object ChunkPlanner {

    const val MIN_CHUNK = 128L * 1024
    const val MAX_CHUNK = 4L * 1024 * 1024

    /**
     * Pick a chunk size — v1.8 tiered by file size (this replaces
     * the v1.3 formula total/(threads*24), which cut a 30 MB file
     * into 240 slivers of 128 KB: hundreds of extra requests whose
     * round trips cost more than the stealing flexibility bought).
     *
     * The tiers keep the chunk COUNT in the few-hundred range at
     * every size — enough pieces for the work-stealing queue to
     * stay fluid, few enough that per-request overhead stays
     * negligible:
     *   <= 8 MB   -> 128 KB   (<= 64 chunks; tiny files finish fast anyway)
     *   <= 128 MB -> 512 KB   (<= 256 chunks)
     *   <= 1 GB   -> 1 MB     (<= 1024 chunks)
     *   <= 8 GB   -> 2 MB     (<= 4096 chunks)
     *   >  8 GB   -> 4 MB     (the MAX_CHUNK cap)
     * On top of the tier, a thread-count guard: when the tier would
     * give each connection fewer than ~3 chunks (very high thread
     * counts on a mid-size file), the chunk shrinks so every worker
     * still gets several pieces — never below MIN_CHUNK, where the
     * file is simply too small to feed everyone (harmless: extra
     * workers exit immediately).
     */
    fun chunkSizeFor(totalSize: Long, threads: Int): Long {
        if (totalSize <= 0) return MIN_CHUNK
        val tier = when {
            totalSize <= 8L * 1024 * 1024 -> MIN_CHUNK
            totalSize <= 128L * 1024 * 1024 -> 512L * 1024
            totalSize <= 1024L * 1024 * 1024 -> 1024L * 1024
            totalSize <= 8L * 1024 * 1024 * 1024 -> 2L * 1024 * 1024
            else -> MAX_CHUNK
        }
        val perThreadCap = totalSize / (threads.coerceAtLeast(1) * 3L)
        return minOf(tier, maxOf(perThreadCap, MIN_CHUNK)).coerceIn(MIN_CHUNK, MAX_CHUNK)
    }

    /**
     * v1.9 audit (round 5): hard ceiling on the chunk count. Real
     * downloads stay in the low thousands (see the tiers above —
     * even a 128 GB file at the 128 KB floor is 1M chunks), but
     * [chunkCount] also sizes arrays from numbers read out of
     * tasks.json / IMPORTED BACKUPS: a hostile or corrupt record
     * claiming totalSize ≈ Long.MAX with chunkSize = 1 used to ask
     * for a billion-entry BooleanArray (OOM) — or overflow the
     * addition and go negative. The count is now computed in Long
     * and clamped; a record that hits the clamp simply loses its
     * chunk state instead of taking the app down.
     */
    const val MAX_CHUNKS = 4_000_000

    fun chunkCount(totalSize: Long, chunkSize: Long): Int {
        if (totalSize <= 0 || chunkSize <= 0) return 0
        val n = (totalSize + chunkSize - 1) / chunkSize
        return n.coerceIn(0, MAX_CHUNKS.toLong()).toInt()
    }

    /** Inclusive-exclusive byte range [start, end) of chunk [index]. */
    fun rangeOf(totalSize: Long, chunkSize: Long, index: Int): LongRange {
        val start = index * chunkSize
        val end = minOf(start + chunkSize, totalSize)
        return start until end
    }

    fun encodeBitset(done: BooleanArray): String {
        val bytes = ByteArray((done.size + 7) / 8)
        for (i in done.indices) {
            if (done[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    fun decodeBitset(hex: String, count: Int): BooleanArray {
        // v1.9 audit: clamp BOTH inputs — the count sizes the
        // output array and the hex string sizes a scratch array,
        // and both can come from an untrusted backup file. Only
        // the bytes the (clamped) count actually needs are parsed.
        val n = count.coerceIn(0, MAX_CHUNKS)
        val out = BooleanArray(n)
        if (hex.isBlank()) return out
        val bytes = ByteArray(minOf(hex.length / 2, (n + 7) / 8))
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        for (i in 0 until n) {
            val b = bytes.getOrNull(i / 8) ?: break
            out[i] = (b.toInt() shr (i % 8)) and 1 == 1
        }
        return out
    }

    fun downloadedOf(done: BooleanArray, totalSize: Long, chunkSize: Long): Long {
        var sum = 0L
        for (i in done.indices) {
            if (done[i]) {
                val r = rangeOf(totalSize, chunkSize, i)
                sum += (r.last - r.first + 1)
            }
        }
        return sum
    }
}

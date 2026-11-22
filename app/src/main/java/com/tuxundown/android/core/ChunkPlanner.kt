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

    fun chunkCount(totalSize: Long, chunkSize: Long): Int =
        chunkCount(uniform(totalSize, chunkSize))

    /** Inclusive-exclusive byte range [start, end) of chunk [index]. */
    fun rangeOf(totalSize: Long, chunkSize: Long, index: Int): LongRange =
        rangeOf(uniform(totalSize, chunkSize), index)

    // ---------- v4.2: two-zone chunk grid (fine head, coarse tail) ----------

    /**
     * Rebuild the grid a task was created with from its persisted
     * fields. fineCount = 0 (every record written before v4.2, and
     * every task whose grid never qualified for a fine zone) is a
     * uniform grid — byte-identical to the pre-v4.2 layout, which
     * is what keeps old resumes exact. A fine zone that would
     * cover the whole file is not a grid [gridFor] could have
     * produced, so a corrupt/imported record claiming one falls
     * back to uniform instead of inventing ranges.
     */
    fun gridOf(
        totalSize: Long,
        chunkSize: Long,
        fineChunkSize: Long,
        fineChunkCount: Int,
    ): ChunkGrid {
        val g = ChunkGrid(totalSize, fineChunkSize, fineChunkCount, chunkSize)
        return if (fineChunkCount <= 0 || fineChunkSize <= 0 ||
            g.fineZoneBytes >= totalSize
        ) {
            uniform(totalSize, chunkSize)
        } else {
            g
        }
    }

    /** A single-granularity grid: no fine zone, [chunkSize] throughout. */
    fun uniform(totalSize: Long, chunkSize: Long): ChunkGrid =
        ChunkGrid(totalSize, fineSize = 0, fineCount = 0, coarseSize = chunkSize)

    /**
     * Decide a NEW task's grid (existing tasks keep the grid they
     * were created with — see the resume invariant on [ChunkGrid]).
     *
     * The fine zone exists so the opening burst of connections
     * finishes its first pieces quickly and the work-stealing
     * queue gets fine-grained early; the coarse tail keeps the
     * request count low for the bulk of the file. It only pays
     * off on big files with a chunky coarse size, so:
     * - total < 256 MB, or coarse < 512 KB -> uniform;
     * - fineSize = coarse / 4, and if that drops below MIN_CHUNK
     *   the granularity contrast is gone -> uniform;
     * - the fine zone spans min(total / 8, fineSize x
     *   startThreads x 4) bytes — an eighth of the file at most,
     *   and at least four fine pieces per opening connection;
     * - the fine zone must leave at least one coarse chunk
     *   behind it, else uniform.
     */
    fun gridFor(totalSize: Long, coarseSize: Long, startThreads: Int): ChunkGrid {
        val fallback = uniform(totalSize, coarseSize)
        if (totalSize < 256L * 1024 * 1024) return fallback
        if (coarseSize < 512L * 1024) return fallback
        val fineSize = coarseSize / 4
        if (fineSize < MIN_CHUNK) return fallback
        val zoneBytes = minOf(
            totalSize / 8,
            fineSize * startThreads.coerceAtLeast(1).toLong() * 4,
        )
        if (zoneBytes <= 0) return fallback
        val fineCount = ((zoneBytes + fineSize - 1) / fineSize)
            .coerceAtLeast(1)
        // fineCount * fineSize can exceed zoneBytes by < 1 piece
        // (the ceil); it must still leave a coarse tail.
        if (fineCount > Int.MAX_VALUE) return fallback
        val grid = ChunkGrid(totalSize, fineSize, fineCount.toInt(), coarseSize)
        if (grid.fineZoneBytes >= totalSize) return fallback
        return grid
    }

    fun chunkCount(grid: ChunkGrid): Int {
        if (grid.totalSize <= 0 || grid.coarseSize <= 0) return 0
        val rest = grid.totalSize - grid.fineZoneBytes
        val coarseCount = if (rest > 0) {
            (rest + grid.coarseSize - 1) / grid.coarseSize
        } else {
            0L
        }
        val n = grid.fineCount.toLong() + coarseCount
        return n.coerceIn(0, MAX_CHUNKS.toLong()).toInt()
    }

    /** Inclusive-exclusive byte range [start, end) of chunk [index]. */
    fun rangeOf(grid: ChunkGrid, index: Int): LongRange {
        val start: Long
        val size: Long
        if (index < grid.fineCount) {
            start = index * grid.fineSize
            size = grid.fineSize
        } else {
            start = grid.fineZoneBytes + (index - grid.fineCount) * grid.coarseSize
            size = grid.coarseSize
        }
        val end = minOf(start + size, grid.totalSize)
        return start until end
    }

    fun downloadedOf(grid: ChunkGrid, done: BooleanArray): Long {
        var sum = 0L
        for (i in done.indices) {
            if (done[i]) {
                val r = rangeOf(grid, i)
                sum += (r.last - r.first + 1)
            }
        }
        return sum
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

    fun downloadedOf(done: BooleanArray, totalSize: Long, chunkSize: Long): Long =
        downloadedOf(uniform(totalSize, chunkSize), done)
}

/**
 * v4.2: a task's byte grid — the layout its chunk bitmap indexes.
 *
 * Chunks [0, fineCount) are [fineSize] each (the fine zone at the
 * head of the file); every chunk after that is [coarseSize].
 * fineCount = 0 is the pre-v4.2 single-granularity grid.
 *
 * RESUME INVARIANT (unchanged from the uniform era): the grid is
 * decided ONCE, when the task's chunk state is first created,
 * and every parameter is persisted on the task record
 * (chunkSize = [coarseSize], fineChunkSize = [fineSize],
 * fineChunkCount = [fineCount]). A resume rebuilds the identical
 * grid from those fields and never re-decides it, so a persisted
 * bitmap always means the same byte ranges it meant when written.
 */
data class ChunkGrid(
    val totalSize: Long,
    val fineSize: Long,
    val fineCount: Int,
    val coarseSize: Long,
) {
    /** Bytes covered by the fine zone (0 when there is none). */
    val fineZoneBytes: Long
        get() = if (fineCount <= 0 || fineSize <= 0) {
            0L
        } else if (fineSize > Long.MAX_VALUE / fineCount) {
            Long.MAX_VALUE // saturate: hostile persisted fields
        } else {
            fineCount.toLong() * fineSize
        }
}

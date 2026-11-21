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

    const val MIN_CHUNK = 256L * 1024
    const val MAX_CHUNK = 8L * 1024 * 1024

    /** Pick a chunk size: aim for ~16 chunks per connection, clamped. */
    fun chunkSizeFor(totalSize: Long, threads: Int): Long {
        if (totalSize <= 0) return MIN_CHUNK
        val target = totalSize / (threads.coerceAtLeast(1) * 16L)
        return target.coerceIn(MIN_CHUNK, MAX_CHUNK)
    }

    fun chunkCount(totalSize: Long, chunkSize: Long): Int {
        if (totalSize <= 0 || chunkSize <= 0) return 0
        return ((totalSize + chunkSize - 1) / chunkSize).toInt()
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
        val out = BooleanArray(count)
        if (hex.isBlank()) return out
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        for (i in 0 until count) {
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

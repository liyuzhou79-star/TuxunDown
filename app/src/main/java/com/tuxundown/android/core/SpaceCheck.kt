package com.tuxundown.android.core

/**
 * Free-space pre-check (v2.3): judge BEFORE a single byte moves
 * whether the target volume can hold what this run still has to
 * write — the old behaviour discovered a full disk halfway into
 * a multi-GB download and stopped there (audit A10 caught the
 * write failure; this catches the situation before it happens).
 *
 * The accounting is deliberately honest:
 * - only bytes still TO BE WRITTEN count. A segmented .part is
 *   ftruncate()d to the full length at start, but ftruncate sets
 *   the length without allocating the blocks — the space is
 *   consumed as bytes land, so a resume needs (total - downloaded)
 *   new bytes, not the whole file again. Single-stream tasks
 *   restart from byte 0, so they need the whole file.
 * - sibling tasks reserve their remaining bytes: with several
 *   tasks running (or queued) at once, each one that starts
 *   would otherwise assume the same free space is all its own.
 * - anything unknown NEVER blocks: an unknown file size (HLS,
 *   a server that hides its length) or an unmeasurable volume
 *   (a custom SAF folder — Android gives no honest StatFs for
 *   an arbitrary tree Uri) skips the check entirely.
 *
 * Pure — unit-tested. The service supplies the measured inputs.
 */
object SpaceCheck {

    sealed class Verdict {
        /** Enough room (or nothing left to write). */
        object Ok : Verdict()

        /** Cannot judge — unknown size or unknown free space. */
        object Unknown : Verdict()

        /** Not enough room; [missingBytes] more are needed. */
        data class Insufficient(val missingBytes: Long) : Verdict()
    }

    /**
     * Bytes a task still has to write: -1 when its total size is
     * unknown, otherwise the full size for a restart-from-scratch
     * task (single-stream) or the unwritten remainder.
     */
    fun remainingOf(totalSize: Long, downloaded: Long, restartFromScratch: Boolean): Long {
        if (totalSize < 0) return -1
        if (totalSize == 0L) return 0
        return if (restartFromScratch) {
            totalSize
        } else {
            (totalSize - downloaded).coerceAtLeast(0)
        }
    }

    /** Total reservation of sibling tasks (unknown sizes reserve 0). */
    fun reservedOf(remainingBytes: List<Long>): Long =
        remainingBytes.filter { it > 0 }.sum()

    /**
     * @param availableBytes free bytes on the target volume,
     *   or -1 when the volume cannot be measured.
     * @param neededBytes bytes this run still has to write,
     *   or -1 when the file size is unknown.
     * @param reservedBytes bytes sibling tasks will still write
     *   to the same volume.
     */
    fun assess(availableBytes: Long, neededBytes: Long, reservedBytes: Long): Verdict {
        if (neededBytes < 0 || availableBytes < 0) return Verdict.Unknown
        if (neededBytes == 0L) return Verdict.Ok
        val total = neededBytes + reservedBytes.coerceAtLeast(0)
        return if (availableBytes >= total) {
            Verdict.Ok
        } else {
            Verdict.Insufficient(total - availableBytes)
        }
    }

    /** The plain-language card note for a blocked task. */
    fun blockMessage(fileSize: Long, availableBytes: Long, missingBytes: Long): String =
        "手机空间不够：这个文件有 ${Format.size(fileSize)}，当前可用空间只有 " +
            "${Format.size(availableBytes)}，还差 ${Format.size(missingBytes)}。" +
            "清出空间后点继续即可下载。"
}

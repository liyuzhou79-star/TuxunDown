package com.tuxundown.android.core

/**
 * v3.0: the pure safety rules for "extract a finished .zip in
 * place" (the platform writer is [ZipExtractor]).
 *
 * Two threats shape this file:
 * - ZIP-SLIP: an entry named "../../x" or "/x" must never be
 *   written outside the target folder. Any ".." segment, an
 *   absolute path or a drive letter refuses the entry — and
 *   the writer aborts the WHOLE archive on the first refusal:
 *   an archive carrying one hostile path is treated as a
 *   hostile archive, not mined for its good entries.
 * - ZIP BOMBS: a tiny archive that expands to terabytes.
 *   Entry-count and total-uncompressed-byte caps, enforced
 *   twice — on the sizes the archive DECLARES and on the
 *   bytes ACTUALLY written (ZipInputStream entries often
 *   declare -1 and only reveal their size as they stream).
 *
 * Only .zip is supported, by design: it is what
 * java.util.zip reads natively. rar / 7z / tar would each
 * need a native library — the settings copy says so plainly.
 */
object ZipExtract {

    /** Entry-count cap (files + directories) for one archive. */
    const val MAX_ENTRIES = 20_000

    /** Total uncompressed-bytes cap for one archive: 16 GiB. */
    const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024

    fun isZipName(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true)

    /**
     * The folder an archive extracts into: its own name minus
     * the ".zip" suffix ("a.b.zip" -> "a.b"). A name that is
     * ONLY the suffix falls back to a plain label instead of
     * an empty (invisible) folder name.
     */
    fun folderNameFor(zipName: String): String {
        val n = zipName.trim()
        val stem = if (isZipName(n)) n.dropLast(4) else n
        return stem.ifBlank { "解压文件" }
    }

    /**
     * Normalize one entry name into a safe relative path
     * ("a/b.txt"), or null when the entry must not be written
     * at all: absolute paths, drive letters and any ".."
     * segment are refused. Backslashes count as separators
     * (Windows-made archives), "." and empty segments
     * collapse away. A name that reduces to nothing ("." or
     * "./") returns "" — the target folder itself, nothing
     * to write.
     */
    fun safeRelativePath(rawName: String): String? {
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) return null
        // Windows drive-letter absolute ("C:/...") after the
        // slash unification above.
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) {
            return null
        }
        val out = mutableListOf<String>()
        for (seg in name.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> return null
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }

    /**
     * Running tally against the bomb caps. [acceptEntry]
     * counts an entry and its DECLARED size; [acceptWritten]
     * counts bytes as they actually land — the second tally
     * is the one that cannot be lied to.
     */
    class Guard {
        var entries = 0
            private set
        var declaredBytes = 0L
            private set
        var writtenBytes = 0L
            private set

        /** false = a cap is blown; abort the whole extraction. */
        fun acceptEntry(declaredSize: Long): Boolean {
            entries++
            if (entries > MAX_ENTRIES) return false
            if (declaredSize > 0) {
                declaredBytes += declaredSize
                if (declaredBytes > MAX_TOTAL_BYTES) return false
            }
            return true
        }

        /** false = the written total passed the cap; abort. */
        fun acceptWritten(n: Int): Boolean {
            writtenBytes += n
            return writtenBytes <= MAX_TOTAL_BYTES
        }
    }
}

package com.tuxundown.android.core

/**
 * Sidecar checksum files (v5.8): many release sites publish a
 * small text file right next to the download — "<file>.sha256"
 * or "<file>.md5" — holding the expected hash so the downloader
 * does not have to be told by hand. When the user's
 * 自动读取校验文件 switch is on, creating a task also tries
 * those two neighbours of the PRIMARY url, parses one hash out
 * of the answer and fills the task's expected-hash fields,
 * marked as website-supplied (TaskRecord.checksumFromSidecar).
 *
 * Discipline (the switch is OFF by default, and every rule
 * below keeps a sidecar failure invisible to the download):
 * - Only http/https tasks are eligible. FTP has no sidecar
 *   convention served this way, HLS playlists are segment
 *   lists (not one file with one hash), and every other scheme
 *   is skipped silently — no request is ever made for them.
 * - Only the PRIMARY source (TaskRecord.url) is probed.
 *   Mirrors in TaskRecord.sources are the same file from other
 *   hosts; their sidecars are never fetched — the primary's
 *   word is the one the task records.
 * - A task that already carries a hand-typed expected hash is
 *   never overwritten: hand-typed wins, sidecar skips quietly.
 * - Fetching is bounded (see MAX_BODY_BYTES / TIMEOUT_MS),
 *   asynchronous and best-effort: 404, timeout, garbage and
 *   ambiguity all resolve to "no expected hash", never to a
 *   blocked or failed task. The Android glue lives in
 *   DownloadCenter; everything here is pure and unit-tested.
 */
object SidecarChecksum {

    /** Response body cap: a checksum file is a few lines; 8 KB
     *  is generous, and anything past it is truncated away. */
    const val MAX_BODY_BYTES = 8 * 1024

    /** Connect/read timeout for one sidecar request. */
    const val TIMEOUT_MS = 5_000

    /** Sidecar suffixes tried, in order (stronger hash first). */
    val SUFFIXES: List<String> = listOf(".sha256", ".md5")

    data class Entry(val kind: String, val hex: String, val fileName: String)

    /**
     * May a sidecar be fetched for this task at all? [enabled]
     * is the user's switch — when it is off the answer is
     * always no and the caller must make ZERO network requests.
     * Also no when the task is HLS, already carries an expected
     * hash (hand-typed priority), or its primary url is not
     * http/https (FTP / other schemes: silent skip, see the
     * class doc).
     */
    fun shouldFetch(
        enabled: Boolean,
        url: String,
        isHls: Boolean,
        expectedSha256: String,
        expectedMd5: String,
    ): Boolean {
        if (!enabled) return false
        if (isHls) return false
        if (expectedSha256.isNotBlank() || expectedMd5.isNotBlank()) return false
        val scheme = LinkSchemes.schemeOf(url)
        return scheme == "http" || scheme == "https"
    }

    /** Task-shaped convenience overload of [shouldFetch]. Only
     *  the primary url is judged — [TaskRecord.sources] play no
     *  part, which is exactly the "multi-source: primary only"
     *  rule. */
    fun shouldFetch(enabled: Boolean, task: TaskRecord): Boolean =
        shouldFetch(enabled, task.url, task.isHls, task.expectedSha256, task.expectedMd5)

    /** The sidecar urls for a PRIMARY url, in try order. */
    fun sidecarUrls(primaryUrl: String): List<String> =
        SUFFIXES.map { primaryUrl + it }

    /** The file name a sidecar entry must name: the task's own
     *  file name when known, else the primary url's last path
     *  segment. Mirrors never contribute a name either. */
    fun targetFileName(task: TaskRecord): String {
        if (task.fileName.isNotBlank()) return baseName(task.fileName)
        return baseName(task.url.substringBefore('?').substringBefore('#'))
    }

    /**
     * Filename comparison key: basename only (any directory
     * prefix — "./", "dir/", "dir\" — is dropped), a leading
     * "*" (GNU binary marker) is dropped, surrounding quotes
     * are dropped, and the comparison is CASE-INSENSITIVE.
     * Release assets are case-stable in practice; folding case
     * only ever merges entries whose names differ by case, and
     * [parse] still refuses when those carry different hashes.
     */
    fun baseName(name: String): String =
        name.trim().trim('"')
            .substringAfterLast('/').substringAfterLast('\\')
            .removePrefix("*").trim()

    fun namesMatch(a: String, b: String): Boolean {
        val x = baseName(a)
        val y = baseName(b)
        return x.isNotEmpty() && x.equals(y, ignoreCase = true)
    }

    private val bsdLine = Regex(
        """^(?:SHA-?256|MD5)\s*\((.*)\)\s*=\s*([0-9a-fA-F]+)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val gnuLine = Regex(
        """^([0-9a-fA-F]+)(?:\s+(.*))?$""",
    )

    /** Parse ONE line into an [Entry], or null when the line
     *  carries no usable 32/64-char hash. The algorithm is
     *  always judged by hash LENGTH (the standing Checksum
     *  rule), never by a printed label. */
    fun parseLine(rawLine: String): Entry? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        bsdLine.matchEntire(line)?.let { m ->
            val hex = Checksum.normalize(m.groupValues[2])
            val kind = Checksum.detectKind(hex) ?: return null
            return Entry(kind, hex, baseName(m.groupValues[1]))
        }
        gnuLine.matchEntire(line)?.let { m ->
            val hex = Checksum.normalize(m.groupValues[1])
            val kind = Checksum.detectKind(hex) ?: return null
            return Entry(kind, hex, baseName(m.groupValues[2]))
        }
        // Shapes like "sha256: <hex>" carry no file name; reuse
        // the round-43 dirty-input parser (it refuses ambiguity
        // within the line itself).
        val parsed = Checksum.parseExpected(line) ?: return null
        return Entry(parsed.first, parsed.second, "")
    }

    /** All usable entries in a sidecar body, in file order. */
    fun parseEntries(content: String): List<Entry> =
        content.lines().mapNotNull { parseLine(it) }

    /**
     * Pick THE hash for [targetFileName] out of a sidecar body:
     * 1. An entry whose trailing file name matches the target
     *    (see [namesMatch]) wins — when several match, they
     *    must agree on one distinct hash, else give up.
     * 2. No name matched: if the WHOLE body holds exactly one
     *    distinct hash, use it (the common one-file sidecar,
     *    including a body whose single entry names a slightly
     *    different name, and a bare-hash body).
     * 3. Otherwise (several distinct hashes, none naming the
     *    target) return null — never guess.
     * Empty / garbage bodies return null. Pure.
     */
    fun parse(content: String, targetFileName: String): Pair<String, String>? {
        if (content.isBlank()) return null
        // Bounded like the fetch: a hostile body handed in whole
        // is judged on its first MAX_BODY_BYTES chars only.
        val bounded = if (content.length > MAX_BODY_BYTES) {
            content.substring(0, MAX_BODY_BYTES)
        } else {
            content
        }
        val entries = parseEntries(bounded)
        if (entries.isEmpty()) return null
        val named = entries.filter {
            it.fileName.isNotEmpty() && namesMatch(it.fileName, targetFileName)
        }
        if (named.isNotEmpty()) {
            val distinct = named.map { it.kind to it.hex }.distinct()
            return if (distinct.size == 1) distinct.single() else null
        }
        val distinctAll = entries.map { it.kind to it.hex }.distinct()
        return if (distinctAll.size == 1) distinctAll.single() else null
    }

    /**
     * Fill [task]'s expected hash from a parsed sidecar value.
     * Returns false (and changes nothing) when the task already
     * carries an expected hash — hand-typed priority, checked
     * again here at fill time because the user may have typed
     * one while the fetch was in flight. On success the value
     * lands in the field matching its length (the standing
     * rule) and the website-supplied mark is set.
     */
    fun applyTo(task: TaskRecord, parsed: Pair<String, String>): Boolean {
        if (task.expectedSha256.isNotBlank() || task.expectedMd5.isNotBlank()) {
            return false
        }
        val (kind, hex) = parsed
        when (kind) {
            Checksum.SHA256 -> task.expectedSha256 = hex
            Checksum.MD5 -> task.expectedMd5 = hex
            else -> return false
        }
        task.checksumFromSidecar = true
        return true
    }
}

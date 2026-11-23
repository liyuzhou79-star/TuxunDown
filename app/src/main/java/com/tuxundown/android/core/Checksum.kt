package com.tuxundown.android.core

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest

/**
 * Post-download integrity checks (v1.7): the user can attach an
 * expected SHA-256 or MD5 hash to a task (release pages publish
 * them all the time); when the download finishes, the service
 * streams the finished file through the digest and the card says
 * plainly whether it matched — 对上 / 对不上.
 *
 * The pure parts (hex handling, streaming digest) are unit-tested;
 * [ChecksumVerifier] is the Android glue shared by the service
 * (verify at finalize) and the card dialog (verify a finished task
 * whose hash was set afterwards).
 */
object Checksum {

    /** checksumState values persisted on the task. */
    const val STATE_NONE = 0
    const val STATE_VERIFYING = 1
    const val STATE_MATCHED = 2
    const val STATE_MISMATCHED = 3

    const val SHA256 = "SHA-256"
    const val MD5 = "MD5"

    /** Tidy user input: trim, drop ALL whitespace (space /
     *  tab / CR / LF), lowercase. */
    fun normalize(hex: String): String =
        hex.trim().replace(Regex("\\s"), "").lowercase()

    /**
     * Which algorithm a hex string must be: 64 hex chars = SHA-256,
     * 32 = MD5, anything else (or non-hex) = null. Pure.
     */
    fun detectKind(hex: String): String? {
        val h = normalize(hex)
        if (h.isEmpty()) return null
        if (!h.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return when (h.length) {
            64 -> SHA256
            32 -> MD5
            else -> null
        }
    }

    /**
     * Audit round 43: parse what a user actually pastes from
     * a release page into the hash dialog. The strict
     * [detectKind] only accepted a bare hex string (whitespace
     * aside), so the common real-world shapes were all
     * rejected with 没认出来 even though the hash was right
     * there:
     * - an algorithm prefix: "sha256: <hex>", "SHA-256=<hex>",
     *   "MD5: <hex>", BSD style "SHA256 (file.zip) = <hex>";
     * - a checksum-file line: "<hex>  file.zip",
     *   "<hex> *file.zip", "<hex>\tfile.zip";
     * - a bare hash with mixed case / inner spaces / tabs /
     *   newlines (grouped hex) — the whitespace-squashed fast
     *   path below.
     *
     * Rules, in order:
     * 1. Squash ALL whitespace; if the whole thing is then a
     *    pure 32/64 hex string, that is the hash.
     * 2. Otherwise look at the MAXIMAL hex runs in the raw
     *    text: runs of exactly 64 or 32 hex chars are
     *    candidates. Exactly ONE distinct candidate -> it
     *    wins, kind by length. Zero, or two or more DISTINCT
     *    candidates (ambiguous — e.g. a hash whose file name
     *    stem is itself 32 hex chars), -> null: the dialog
     *    asks again instead of guessing.
     *
     * A plain file name ("release.zip", "app-v1.apk") has no
     * 32/64 hex run, so it can never be misread as a hash.
     * The algorithm is ALWAYS judged by length, never by a
     * typed label — a wrong label cannot smuggle a wrong
     * kind in. Pure.
     */
    fun parseExpected(raw: String): Pair<String, String>? {
        val squashed = normalize(raw)
        detectKind(squashed)?.let { return it to squashed }
        val candidates = Regex("[0-9a-fA-F]+").findAll(raw)
            .map { it.value.lowercase() }
            .filter { it.length == 64 || it.length == 32 }
            .distinct()
            .toList()
        if (candidates.size != 1) return null
        val hex = candidates.single()
        return detectKind(hex)?.let { it to hex }
    }

    /** The task's expected (kind, normalized hex), SHA-256 first; null when unset. */
    fun expectedOf(task: TaskRecord): Pair<String, String>? {
        if (task.expectedSha256.isNotBlank()) {
            return SHA256 to normalize(task.expectedSha256)
        }
        if (task.expectedMd5.isNotBlank()) {
            return MD5 to normalize(task.expectedMd5)
        }
        return null
    }

    /**
     * v4.5: the streaming read buffer — 1 MB, up from 256 KB.
     *
     * Why this size: the loop's per-round cost is one
     * InputStream.read + one MessageDigest.update, and a
     * read(byte[]) asks the stream for the WHOLE buffer each
     * time, so the buffer size is what amortizes the per-call
     * cost (for contentResolver streams each read also crosses
     * into the provider). 256 KB -> 1 MB quarters the number of
     * read/update rounds for the same file. Going further
     * (4 MB+) buys nothing measurable — the per-call cost is
     * already amortized away — and only spends memory and
     * widens the cancel grain below.
     *
     * No BufferedInputStream wrapper, on purpose: it would add
     * its own internal buffer plus a full extra copy of every
     * byte (fill its buffer, copy into ours) while amortizing
     * nothing the big read does not already amortize. It only
     * pays off for callers that read a few bytes at a time;
     * every caller here hands over a bulk stream.
     *
     * Memory account: each running digest holds exactly ONE
     * buffer = 1 MB. Verifications of finished files: the
     * finalize lane is serial (one auto-verify at a time) =
     * 1 MB; a card-triggered manual verify can overlap it on
     * a DIFFERENT task = +1 MB worst case. The duplicate
     * finder hashes one file at a time through the same code
     * = at most one more. Total worst case ~3 MB, transient.
     *
     * Cancel grain: [shouldContinue] is checked once per
     * buffer, so a cancel lands after at most one more buffer
     * is read — the grain widened 256 KB -> 1 MB with the
     * buffer. The semantics did not change: a cancelled
     * digest returns null and callers record NO verdict.
     */
    const val BUFFER_BYTES = 1024 * 1024

    /** Stream [input] through the digest; returns lowercase hex. */
    fun digest(input: InputStream, kind: String): String =
        digestCancellable(input, kind) { true }!!

    /**
     * v2.9: cancellable variant — returns null as soon as
     * [shouldContinue] turns false, so verifying a multi-GB
     * file can be abandoned without reading the rest of it.
     * The check runs once per [BUFFER_BYTES] buffer.
     *
     * v4.5: [onProgress] (optional) is called once per buffer
     * read with the CUMULATIVE bytes read so far — per-buffer
     * is the throttle; no caller needs finer than 1 MB steps.
     */
    fun digestCancellable(
        input: InputStream,
        kind: String,
        onProgress: (Long) -> Unit = {},
        shouldContinue: () -> Boolean,
    ): String? {
        val md = MessageDigest.getInstance(kind)
        val buf = ByteArray(BUFFER_BYTES)
        var readTotal = 0L
        while (true) {
            if (!shouldContinue()) return null
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) {
                md.update(buf, 0, n)
                readTotal += n
                onProgress(readTotal)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * v4.5: honest progress percent for a running verify.
     * Returns null when the total is unknown (<= 0) — callers
     * then show a bare "校验中…" rather than an invented
     * number. Over-reads clamp to 100, negative reads to 0.
     * Pure.
     */
    fun progressPercent(readBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        if (readBytes <= 0L) return 0
        if (readBytes >= totalBytes) return 100
        return (readBytes * 100 / totalBytes).toInt()
    }

    /**
     * v4.5: the card's live verify label — "正在校验…N%" when
     * a real percent exists, the plain "校验中…" when it does
     * not (size unknown). Pure.
     */
    fun verifyingLabel(progressPct: Int): String =
        if (progressPct in 0..100) "正在校验…$progressPct%" else "校验中…"

    /**
     * v5.8: card wording when the expected hash came from a
     * website sidecar file (see SidecarChecksum). The verdict
     * words themselves stay [stateLabel]'s — this only adds
     * the source sentence, so there is still ONE wording for
     * 对上 / 对不上. For a hand-typed hash (or no hash) it
     * returns [stateLabel] unchanged.
     */
    fun stateLabelFor(task: TaskRecord): String {
        val base = stateLabel(task.checksumState)
        if (!task.checksumFromSidecar || expectedOf(task) == null) return base
        return when (task.checksumState) {
            STATE_MATCHED -> "$base · 已按网站给的校验值核对"
            STATE_MISMATCHED -> "$base · 网站给的校验值与文件对不上"
            STATE_VERIFYING -> base
            else -> "$base · 校验值来自网站给的校验文件"
        }
    }

    /** Card wording for a checksum state. */
    fun stateLabel(state: Int): String = when (state) {
        STATE_MATCHED -> "✓ 对上（文件完整）"
        STATE_MISMATCHED -> "✗ 对不上（文件可能损坏或不对）"
        STATE_VERIFYING -> "校验中…"
        // v2.9: auto-verify is a setting now — the label no
        // longer promises it; the card's 立即校验 button and
        // the completion note carry the explanation.
        else -> "还没校验"
    }
}

/**
 * Android glue: stream the task's finished file from its content
 * Uri through the expected digest and record the verdict on the
 * task. Returns a plain-language verdict sentence for the task
 * note ("" when the task has no expected hash at all).
 */
object ChecksumVerifier {

    /**
     * v2.9: ids of tasks whose running verification should
     * stop at the next buffer boundary. Process-wide on
     * purpose: a verification can be started by the service
     * (auto-verify at finalize) or by the card （立即校验）,
     * and the card's 取消校验 button must stop either one.
     */
    private val cancelRequests =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * v4.5: ids of tasks with a verification RUNNING right
     * now. The guard the progress field needs: before this,
     * nothing stopped two verifies of the SAME task from
     * overlapping (the card hides 立即校验 while VERIFYING,
     * but the state is only set on the verify's own thread,
     * and the hash dialog's save-and-verify and the service's
     * finalize auto-verify are separate doors). Two writers
     * would fight over checksumState and the live percent.
     * Now the first verify in owns the task; a second caller
     * gets "" (no verdict of its own — the running one's
     * verdict lands on the task as usual).
     */
    private val inFlight =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Ask a running verification of this task to stop. */
    fun requestCancel(taskId: String) {
        cancelRequests.add(taskId)
    }

    /** v7.7.0 Step C2: is a verification of this task running
     *  right now (the inFlight set — the same occupancy the
     *  verify guard itself uses). */
    fun isVerifying(taskId: String): Boolean = taskId in inFlight

    /**
     * The finished file's total bytes — the progress
     * denominator. Asked from the provider first (that IS the
     * file on disk, HLS merges included); the task's recorded
     * totalSize is the fallback; -1 = unknown, and then no
     * percent is published at all.
     */
    private fun finishedFileSize(
        context: Context,
        uri: Uri,
        task: TaskRecord,
    ): Long {
        val fromProvider = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(
                        android.provider.OpenableColumns.SIZE,
                    )
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                } else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        if (fromProvider > 0) return fromProvider
        return if (task.totalSize > 0) task.totalSize else -1L
    }

    fun verify(context: Context, task: TaskRecord): String {
        val (kind, expected) = Checksum.expectedOf(task) ?: return ""
        val uriStr = task.savedUri.ifBlank { task.targetUri }
        if (uriStr.isBlank()) return ""
        // v4.5: one verify per task at a time (see inFlight).
        if (!inFlight.add(task.id)) return ""
        task.checksumState = Checksum.STATE_VERIFYING
        task.checksumProgressPct = -1
        var cancelled = false
        try {
            val uri = Uri.parse(uriStr)
            val totalBytes = finishedFileSize(context, uri, task)
            val actual = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    Checksum.digestCancellable(
                        it, kind,
                        onProgress = { read ->
                            // Unknown total -> percent stays null
                            // and the field stays -1: the card
                            // shows a bare 校验中…, never a guess.
                            Checksum.progressPercent(read, totalBytes)
                                ?.let { pct ->
                                    task.checksumProgressPct = pct
                                }
                        },
                    ) {
                        val stop = task.id in cancelRequests
                        if (stop) cancelled = true
                        !stop
                    }
                }
            }.getOrNull()
            return finish(
                task, kind, expected, actual, cancelled,
                Checksum.expectedOf(task),
            )
        } finally {
            // v4.5: EVERY exit (matched / mismatched /
            // cancelled / unreadable / blew up) retires the
            // live percent and both process-wide marks, so no
            // stale progress survives into the next verify.
            inFlight.remove(task.id)
            cancelRequests.remove(task.id)
            task.checksumProgressPct = -1
        }
    }

    /**
     * Record a finished digest's verdict on the task and
     * return the plain-language sentence for it.
     *
     * Audit round 43: [current] is the task's expected hash
     * re-read AFTER the digest ran. The card's 改校验哈希
     * button stays tappable while a verify runs, so the user
     * can replace (or clear) the expected value mid-verify;
     * without this check the running verify would stamp its
     * OLD-hash verdict onto the task and the card would then
     * attribute 对上/对不上 to the NEW hash. A superseded
     * verify records NO verdict (back to STATE_NONE) and
     * says why. Internal (not private) so unit tests can pin
     * every branch; it touches only the record + strings.
     */
    internal fun finish(
        task: TaskRecord,
        kind: String,
        expected: String,
        actual: String?,
        cancelled: Boolean,
        current: Pair<String, String>?,
    ): String {
        return when {
            current != (kind to expected) -> {
                task.checksumState = Checksum.STATE_NONE
                "校验期间你改了校验值，这次结果没有算数；需要时可在任务详情里点「立即校验」再验一次。"
            }
            cancelled -> {
                // Stopped on purpose: no verdict either way,
                // back to "not checked"; the file is untouched.
                task.checksumState = Checksum.STATE_NONE
                "完整性校验已取消，文件本身没动；需要时可在任务详情里重新校验。"
            }
            actual == null -> {
                // Could not even read the file: do not claim either
                // verdict — reset to "not checked" and say so.
                task.checksumState = Checksum.STATE_NONE
                "完整性校验没做成：读不到下载好的文件。"
            }
            actual == expected -> {
                task.checksumState = Checksum.STATE_MATCHED
                // v5.8: a sidecar-supplied value was not typed by
                // the user — say where it came from.
                if (task.checksumFromSidecar) {
                    "完整性校验通过：$kind 对上，已按网站给的校验值核对，文件没坏。"
                } else {
                    "完整性校验通过：$kind 对上，文件没坏。"
                }
            }
            else -> {
                task.checksumState = Checksum.STATE_MISMATCHED
                if (task.checksumFromSidecar) {
                    "⚠ 完整性校验对不上：这个文件的 $kind 和网站给的校验值不一样，" +
                        "可能下载损坏了，或者来源给的不是同一个文件，请谨慎使用。"
                } else {
                    "⚠ 完整性校验对不上：这个文件的 $kind 和你填的不一样，" +
                        "可能下载损坏了，或者来源给的不是同一个文件，请谨慎使用。"
                }
            }
        }
    }
}

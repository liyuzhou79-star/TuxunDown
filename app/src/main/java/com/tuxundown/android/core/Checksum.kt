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

    /** Tidy user input: trim, drop spaces, lowercase. */
    fun normalize(hex: String): String =
        hex.trim().replace(" ", "").replace("\n", "").lowercase()

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

    /** Stream [input] through the digest; returns lowercase hex. */
    fun digest(input: InputStream, kind: String): String {
        val md = MessageDigest.getInstance(kind)
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Card wording for a checksum state. */
    fun stateLabel(state: Int): String = when (state) {
        STATE_MATCHED -> "✓ 对上（文件完整）"
        STATE_MISMATCHED -> "✗ 对不上（文件可能损坏或不对）"
        STATE_VERIFYING -> "校验中…"
        else -> "还没校验（下载完成时自动校验）"
    }
}

/**
 * Android glue: stream the task's finished file from its content
 * Uri through the expected digest and record the verdict on the
 * task. Returns a plain-language verdict sentence for the task
 * note ("" when the task has no expected hash at all).
 */
object ChecksumVerifier {

    fun verify(context: Context, task: TaskRecord): String {
        val (kind, expected) = Checksum.expectedOf(task) ?: return ""
        val uriStr = task.savedUri.ifBlank { task.targetUri }
        if (uriStr.isBlank()) return ""
        task.checksumState = Checksum.STATE_VERIFYING
        val actual = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                Checksum.digest(it, kind)
            }
        }.getOrNull()
        return when {
            actual == null -> {
                // Could not even read the file: do not claim either
                // verdict — reset to "not checked" and say so.
                task.checksumState = Checksum.STATE_NONE
                "完整性校验没做成：读不到下载好的文件。"
            }
            actual == expected -> {
                task.checksumState = Checksum.STATE_MATCHED
                "完整性校验通过：$kind 对上，文件没坏。"
            }
            else -> {
                task.checksumState = Checksum.STATE_MISMATCHED
                "⚠ 完整性校验对不上：这个文件的 $kind 和你填的不一样，" +
                    "可能下载损坏了，或者来源给的不是同一个文件，请谨慎使用。"
            }
        }
    }
}

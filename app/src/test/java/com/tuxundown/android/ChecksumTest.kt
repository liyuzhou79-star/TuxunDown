package com.tuxundown.android

import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.ChecksumVerifier
import com.tuxundown.android.core.TaskRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * v1.7: checksum helpers — kind auto-detection from hex length,
 * streaming digest against published test vectors, and the
 * SHA-256-over-MD5 precedence on a task.
 */
class ChecksumTest {

    @Test
    fun detectSha256() {
        assertEquals(
            Checksum.SHA256,
            Checksum.detectKind("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        )
    }

    @Test
    fun detectMd5() {
        assertEquals(Checksum.MD5, Checksum.detectKind("900150983cd24fb0d6963f7d28e17f72"))
    }

    @Test
    fun detectToleratesCaseAndSpaces() {
        assertEquals(
            Checksum.SHA256,
            Checksum.detectKind(" BA7816BF 8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD "),
        )
    }

    @Test
    fun detectRejectsGarbage() {
        assertNull(Checksum.detectKind(""))
        assertNull(Checksum.detectKind("xyz"))
        assertNull(Checksum.detectKind("a".repeat(63)))
        assertNull(Checksum.detectKind("g".repeat(64)))
        assertNull(Checksum.detectKind("a".repeat(128)))
    }

    @Test
    fun digestSha256Vector() {
        val hex = Checksum.digest(
            ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
            Checksum.SHA256,
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
    }

    @Test
    fun digestMd5Vector() {
        val hex = Checksum.digest(
            ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
            Checksum.MD5,
        )
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex)
    }

    @Test
    fun digestEmptyStream() {
        val hex = Checksum.digest(ByteArrayInputStream(ByteArray(0)), Checksum.SHA256)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex,
        )
    }

    @Test
    fun digestCancellableMatchesDigest() {
        val data = "hello tuxundown".toByteArray(Charsets.UTF_8)
        assertEquals(
            Checksum.digest(ByteArrayInputStream(data), Checksum.SHA256),
            Checksum.digestCancellable(
                ByteArrayInputStream(data), Checksum.SHA256
            ) { true },
        )
    }

    @Test
    fun digestCancellableStopsBeforeReadingEverything() {
        // Three buffers' worth of data; the gate closes at the
        // second check, so the digest must give up (null)
        // instead of finishing.
        val data = ByteArray(3 * Checksum.BUFFER_BYTES) { 7 }
        var checks = 0
        val out = Checksum.digestCancellable(
            ByteArrayInputStream(data), Checksum.SHA256
        ) {
            checks++
            checks < 2
        }
        assertNull(out)
        assertEquals(2, checks)
    }

    @Test
    fun digestCancellableCancelledImmediately() {
        val out = Checksum.digestCancellable(
            ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
            Checksum.SHA256,
        ) { false }
        assertNull(out)
    }

    // ---- v4.5: buffer size, progress callback, percent math ----

    @Test
    fun bufferIsOneMegabyte() {
        // The cancel grain and the memory account in
        // Checksum.BUFFER_BYTES' doc both hang off this value.
        assertEquals(1024 * 1024, Checksum.BUFFER_BYTES)
    }

    @Test
    fun digestReportsCumulativeProgressPerBuffer() {
        // 2 full buffers + a tail: ByteArrayInputStream fills
        // the buffer on every read until the tail, so the
        // callbacks must be exactly the cumulative marks.
        val tail = 12_345
        val data = ByteArray(2 * Checksum.BUFFER_BYTES + tail) { (it % 251).toByte() }
        val seen = mutableListOf<Long>()
        val hex = Checksum.digestCancellable(
            ByteArrayInputStream(data), Checksum.SHA256,
            onProgress = { seen.add(it) },
        ) { true }
        assertEquals(
            Checksum.digest(ByteArrayInputStream(data), Checksum.SHA256),
            hex,
        )
        assertEquals(
            listOf(
                Checksum.BUFFER_BYTES.toLong(),
                2L * Checksum.BUFFER_BYTES,
                data.size.toLong(),
            ),
            seen,
        )
    }

    @Test
    fun digestProgressStopsWithTheCancel() {
        // Gate closes at the second check: exactly one buffer
        // was read and reported, then null — no progress is
        // reported for bytes never read.
        val data = ByteArray(3 * Checksum.BUFFER_BYTES) { 7 }
        val seen = mutableListOf<Long>()
        var checks = 0
        val out = Checksum.digestCancellable(
            ByteArrayInputStream(data), Checksum.SHA256,
            onProgress = { seen.add(it) },
        ) {
            checks++
            checks < 2
        }
        assertNull(out)
        assertEquals(listOf(Checksum.BUFFER_BYTES.toLong()), seen)
    }

    @Test
    fun digestCancelledImmediatelyReportsNoProgress() {
        val seen = mutableListOf<Long>()
        val out = Checksum.digestCancellable(
            ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8)),
            Checksum.SHA256,
            onProgress = { seen.add(it) },
        ) { false }
        assertNull(out)
        assertEquals(emptyList<Long>(), seen)
    }

    @Test
    fun progressPercentMath() {
        assertEquals(0, Checksum.progressPercent(0, 100))
        assertEquals(0, Checksum.progressPercent(-5, 100))
        assertEquals(1, Checksum.progressPercent(1, 100))
        assertEquals(50, Checksum.progressPercent(50, 100))
        assertEquals(33, Checksum.progressPercent(1, 3))
        assertEquals(99, Checksum.progressPercent(999, 1000))
        assertEquals(100, Checksum.progressPercent(100, 100))
        // Over-read (provider size lied / file grew): clamp.
        assertEquals(100, Checksum.progressPercent(150, 100))
    }

    @Test
    fun progressPercentUnknownTotalIsNull() {
        assertNull(Checksum.progressPercent(0, 0))
        assertNull(Checksum.progressPercent(500, 0))
        assertNull(Checksum.progressPercent(500, -1))
    }

    @Test
    fun verifyingLabelShowsPercentOnlyWhenKnown() {
        assertEquals("正在校验…0%", Checksum.verifyingLabel(0))
        assertEquals("正在校验…57%", Checksum.verifyingLabel(57))
        assertEquals("正在校验…100%", Checksum.verifyingLabel(100))
        assertEquals("校验中…", Checksum.verifyingLabel(-1))
        assertEquals("校验中…", Checksum.verifyingLabel(101))
    }

    @Test
    fun stateLabelCoversEveryStateAndFallsBackToNotChecked() {
        // v5.6 (R5 closure): the card wording for each persisted
        // state, pinned verbatim — a wrong word here is the whole
        // failure mode of this function.
        assertEquals("✓ 对上（文件完整）", Checksum.stateLabel(Checksum.STATE_MATCHED))
        assertEquals(
            "✗ 对不上（文件可能损坏或不对）",
            Checksum.stateLabel(Checksum.STATE_MISMATCHED),
        )
        assertEquals("校验中…", Checksum.stateLabel(Checksum.STATE_VERIFYING))
        // STATE_NONE and any out-of-band stored value (corrupt /
        // future state read by this build) fall to the else arm.
        assertEquals("还没校验", Checksum.stateLabel(Checksum.STATE_NONE))
        assertEquals("还没校验", Checksum.stateLabel(99))
        assertEquals("还没校验", Checksum.stateLabel(-1))
    }

    // ---- audit round 43: parseExpected (dirty pasted input) ----

    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val md5 = "900150983cd24fb0d6963f7d28e17f72"

    @Test
    fun parseBareAndGroupedHashes() {
        assertEquals(Checksum.SHA256 to sha, Checksum.parseExpected(sha))
        assertEquals(Checksum.MD5 to md5, Checksum.parseExpected(md5))
        // Mixed case + spaces / tabs / newlines inside (grouped hex).
        assertEquals(
            Checksum.SHA256 to sha,
            Checksum.parseExpected(" BA7816BF\t8F01CFEA\n414140DE 5DAE2223 " +
                "B00361A396177A9CB410FF61F20015AD\r\n"),
        )
    }

    @Test
    fun parseAlgorithmPrefixes() {
        assertEquals(Checksum.SHA256 to sha, Checksum.parseExpected("sha256: $sha"))
        assertEquals(Checksum.SHA256 to sha, Checksum.parseExpected("SHA-256=$sha"))
        assertEquals(Checksum.SHA256 to sha, Checksum.parseExpected("SHA256:  ${sha.uppercase()}"))
        assertEquals(Checksum.MD5 to md5, Checksum.parseExpected("md5: $md5"))
        // BSD style: SHA256 (file) = hash — kind by LENGTH, not label.
        assertEquals(
            Checksum.SHA256 to sha,
            Checksum.parseExpected("SHA256 (tuxundown.apk) = $sha"),
        )
        assertEquals(
            Checksum.MD5 to md5,
            Checksum.parseExpected("MD5 (tuxundown.apk) = $md5"),
        )
    }

    @Test
    fun parseChecksumFileLines() {
        assertEquals(
            Checksum.SHA256 to sha,
            Checksum.parseExpected("$sha  tuxundown-v5.8.0.apk"),
        )
        assertEquals(
            Checksum.SHA256 to sha,
            Checksum.parseExpected("$sha *tuxundown-v5.8.0.apk"),
        )
        assertEquals(
            Checksum.MD5 to md5,
            Checksum.parseExpected("$md5\ttuxundown-v5.8.0.apk\n"),
        )
        // The same hash repeated (e.g. pasted twice) is still one candidate.
        assertEquals(Checksum.SHA256 to sha, Checksum.parseExpected("$sha  $sha"))
    }

    @Test
    fun parseRejectsNonHashesAndAmbiguity() {
        assertNull(Checksum.parseExpected(""))
        assertNull(Checksum.parseExpected("   "))
        // A file name alone must never be misread as a hash.
        assertNull(Checksum.parseExpected("tuxundown-v5.8.0.apk"))
        assertNull(Checksum.parseExpected("release.zip"))
        assertNull(Checksum.parseExpected("校验值见发布页说明"))
        // Wrong lengths / non-hex stay rejected.
        assertNull(Checksum.parseExpected("a".repeat(63)))
        assertNull(Checksum.parseExpected("a".repeat(65)))
        assertNull(Checksum.parseExpected("g".repeat(64)))
        // Two DIFFERENT hashes in one paste: ambiguous, ask again.
        assertNull(Checksum.parseExpected("$sha $md5"))
        assertNull(Checksum.parseExpected("$sha  ${"0".repeat(64)}"))
    }

    @Test
    fun normalizeDropsTabsAndCrLf() {
        assertEquals("ab12", Checksum.normalize(" A\tB\r\n1 2 "))
    }

    // ---- audit round 43: ChecksumVerifier.finish verdict branches ----

    @Test
    fun finishMatchedAndMismatched() {
        val t = TaskRecord(url = "https://x/f")
        val ok = ChecksumVerifier.finish(
            t, Checksum.SHA256, sha, sha, cancelled = false,
            current = Checksum.SHA256 to sha,
        )
        assertEquals(Checksum.STATE_MATCHED, t.checksumState)
        assertTrue(ok.contains("校验通过"))
        val bad = ChecksumVerifier.finish(
            t, Checksum.SHA256, sha, md5.padEnd(64, '0'), cancelled = false,
            current = Checksum.SHA256 to sha,
        )
        assertEquals(Checksum.STATE_MISMATCHED, t.checksumState)
        assertTrue(bad.contains("对不上"))
    }

    @Test
    fun finishCancelledAndUnreadableRecordNoVerdict() {
        val t = TaskRecord(url = "https://x/f")
        ChecksumVerifier.finish(
            t, Checksum.SHA256, sha, sha, cancelled = true,
            current = Checksum.SHA256 to sha,
        )
        assertEquals(Checksum.STATE_NONE, t.checksumState)
        ChecksumVerifier.finish(
            t, Checksum.SHA256, sha, null, cancelled = false,
            current = Checksum.SHA256 to sha,
        )
        assertEquals(Checksum.STATE_NONE, t.checksumState)
    }

    @Test
    fun finishSupersededWhenExpectedChangedMidVerify() {
        val t = TaskRecord(url = "https://x/f")
        // The digest matched the OLD hash, but the user replaced
        // the expected value while it ran: no verdict may be
        // stamped — the card would blame/praise the NEW hash.
        val msg = ChecksumVerifier.finish(
            t, Checksum.SHA256, sha, sha, cancelled = false,
            current = Checksum.MD5 to md5,
        )
        assertEquals(Checksum.STATE_NONE, t.checksumState)
        assertTrue(msg.contains("改了校验值"))
        // Cleared mid-verify is superseded too.
        ChecksumVerifier.finish(
            t, Checksum.SHA256, sha, sha, cancelled = false, current = null,
        )
        assertEquals(Checksum.STATE_NONE, t.checksumState)
    }

    @Test
    fun expectedPrefersSha256() {
        val t = TaskRecord(url = "https://x/f")
        assertNull(Checksum.expectedOf(t))
        t.expectedMd5 = "900150983CD24FB0D6963F7D28E17F72"
        assertEquals(
            Checksum.MD5 to "900150983cd24fb0d6963f7d28e17f72",
            Checksum.expectedOf(t),
        )
        t.expectedSha256 = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertEquals(Checksum.SHA256, Checksum.expectedOf(t)!!.first)
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.TaskRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

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
        val data = ByteArray(3 * 256 * 1024) { 7 }
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

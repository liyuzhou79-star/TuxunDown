package com.tuxundown.android

import com.tuxundown.android.core.FileTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Audit round 40: DownloadTarget had NO test at all. The
 * PfdTarget half is Android-bound (ParcelFileDescriptor /
 * android.system.Os) and cannot run on the JVM, but FileTarget
 * is plain java.io — and it is the half a wrong offset would
 * silently corrupt a downloaded file through, so its
 * positional-write contract is pinned here: out-of-order
 * writes land at their own offsets, a rewrite replaces only
 * its own span, truncate shrinks and extends, and the
 * sequential stream starts from a truncated empty file.
 */
class FileTargetTest {

    private fun tempTarget(): Pair<FileTarget, File> {
        val f = Files.createTempFile("filetarget", ".part").toFile()
        return FileTarget(f) to f
    }

    @Test
    fun outOfOrderPositionalWritesLandAtTheirOffsets() {
        val (t, f) = tempTarget()
        t.use {
            it.writeAt(6, "world".toByteArray(), 0, 5)
            it.writeAt(0, "hello ".toByteArray(), 0, 6)
            assertEquals(11L, it.size())
        }
        assertEquals("hello world", f.readText())
    }

    @Test
    fun writeAtHonoursTheBufferOffsetAndLength() {
        val (t, f) = tempTarget()
        t.use {
            val buf = "xxDATAyy".toByteArray()
            it.writeAt(0, buf, 2, 4)
            assertEquals(4L, it.size())
        }
        assertEquals("DATA", f.readText())
    }

    @Test
    fun rewritingASpanReplacesOnlyThatSpan() {
        val (t, f) = tempTarget()
        t.use {
            it.writeAt(0, "aaaa bbbb".toByteArray(), 0, 9)
            it.writeAt(5, "ZZ".toByteArray(), 0, 2)
            assertEquals(9L, it.size())
        }
        assertEquals("aaaa ZZbb", f.readText())
    }

    @Test
    fun truncateShrinksAndZeroExtends() {
        val (t, f) = tempTarget()
        t.use {
            it.writeAt(0, "0123456789".toByteArray(), 0, 10)
            it.truncate(4)
            assertEquals(4L, it.size())
            it.truncate(7)
            assertEquals(7L, it.size())
        }
        val bytes = f.readBytes()
        assertArrayEquals("0123".toByteArray(), bytes.copyOfRange(0, 4))
        assertArrayEquals(ByteArray(3), bytes.copyOfRange(4, 7))
    }

    @Test
    fun openStreamTruncatesAndWritesSequentially() {
        val (t, f) = tempTarget()
        t.use {
            it.writeAt(0, "stale stale".toByteArray(), 0, 11)
            it.openStream().use { s -> s.write("fresh".toByteArray()) }
            assertEquals(5L, it.size())
        }
        assertEquals("fresh", f.readText())
    }

    @Test
    fun syncAndCloseDoNotThrowOnALiveTarget() {
        val (t, _) = tempTarget()
        t.writeAt(0, "x".toByteArray(), 0, 1)
        t.sync()
        t.close()
        // close is runCatching by contract: a second one is fine.
        t.close()
    }

    private fun FileTarget.use(block: (FileTarget) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}

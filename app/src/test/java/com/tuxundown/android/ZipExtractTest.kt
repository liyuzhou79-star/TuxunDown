package com.tuxundown.android

import com.tuxundown.android.core.ZipExtract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipExtractTest {

    @Test
    fun isZipNameIsCaseInsensitiveAndSuffixOnly() {
        assertTrue(ZipExtract.isZipName("a.zip"))
        assertTrue(ZipExtract.isZipName("a.ZIP"))
        assertTrue(ZipExtract.isZipName("a.b.Zip"))
        assertFalse(ZipExtract.isZipName("a.zipx"))
        assertFalse(ZipExtract.isZipName("zip"))
        assertFalse(ZipExtract.isZipName("a.rar"))
    }

    @Test
    fun folderNameDropsTheSuffix() {
        assertEquals("a", ZipExtract.folderNameFor("a.zip"))
        assertEquals("a.b", ZipExtract.folderNameFor("a.b.ZIP"))
        assertEquals("a", ZipExtract.folderNameFor("  a.zip  "))
        // A bare ".zip" must not become an empty folder name.
        assertEquals("解压文件", ZipExtract.folderNameFor(".zip"))
        // Non-zip names pass through untouched (defensive).
        assertEquals("a.rar", ZipExtract.folderNameFor("a.rar"))
    }

    @Test
    fun safePathsNormalize() {
        assertEquals("a/b.txt", ZipExtract.safeRelativePath("a/b.txt"))
        assertEquals("a.txt", ZipExtract.safeRelativePath("./a.txt"))
        assertEquals("a/b", ZipExtract.safeRelativePath("a//b"))
        assertEquals("a/b", ZipExtract.safeRelativePath("a/./b"))
        assertEquals("a", ZipExtract.safeRelativePath("a/"))
        assertEquals("", ZipExtract.safeRelativePath("."))
        assertEquals("", ZipExtract.safeRelativePath("./"))
        assertEquals("", ZipExtract.safeRelativePath(""))
    }

    @Test
    fun traversalIsRefused() {
        assertNull(ZipExtract.safeRelativePath("../evil.txt"))
        assertNull(ZipExtract.safeRelativePath("a/../../evil.txt"))
        assertNull(ZipExtract.safeRelativePath("a/../b.txt"))
        assertNull(ZipExtract.safeRelativePath("/abs.txt"))
        assertNull(ZipExtract.safeRelativePath("//server/share"))
    }

    @Test
    fun windowsShapesAreHandled() {
        // Backslashes are separators, not filename characters.
        assertEquals("a/b.txt", ZipExtract.safeRelativePath("a\\b.txt"))
        assertEquals("a/b/c.txt", ZipExtract.safeRelativePath("a\\b\\c.txt"))
        // Drive-letter absolute paths are refused.
        assertNull(ZipExtract.safeRelativePath("C:\\evil.txt"))
        assertNull(ZipExtract.safeRelativePath("C:/evil.txt"))
        // A backslash traversal is still a traversal.
        assertNull(ZipExtract.safeRelativePath("..\\evil.txt"))
    }

    @Test
    fun guardCountsEntries() {
        val g = ZipExtract.Guard()
        repeat(ZipExtract.MAX_ENTRIES) {
            assertTrue(g.acceptEntry(0))
        }
        assertFalse(g.acceptEntry(0))
        assertEquals(ZipExtract.MAX_ENTRIES + 1, g.entries)
    }

    @Test
    fun guardCountsDeclaredBytes() {
        val g = ZipExtract.Guard()
        assertTrue(g.acceptEntry(ZipExtract.MAX_TOTAL_BYTES))
        assertFalse(g.acceptEntry(1))
    }

    @Test
    fun guardCountsWrittenBytesIndependently() {
        val g = ZipExtract.Guard()
        // Declared sizes can lie (-1); the written tally is
        // the real stop.
        assertTrue(g.acceptEntry(-1))
        assertTrue(g.acceptWritten(Int.MAX_VALUE))
        assertTrue(g.acceptWritten(Int.MAX_VALUE))
        var ok = true
        repeat(10) { if (!g.acceptWritten(Int.MAX_VALUE)) ok = false }
        assertFalse(ok)
        assertTrue(g.writtenBytes > ZipExtract.MAX_TOTAL_BYTES)
    }
}

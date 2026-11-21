package com.tuxundown.android

import com.tuxundown.android.core.DownloadedFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.9: the files page merge/sort/total logic. */
class DownloadedFilesTest {

    private fun e(name: String, uri: String, size: Long, mod: Long, task: String = "") =
        DownloadedFiles.Entry(name, uri, size, mod, task)

    @Test
    fun mergeKeepsRecordsAndAddsOnlyUnclaimedScanFiles() {
        val records = listOf(e("a.zip", "uri://1", 100, 10, "t1"))
        val scan = listOf(
            e("a.zip", "uri://1", 100, 10),          // claimed by the record
            e("b.zip", "uri://2", 200, 20),          // unclaimed: kept
            e("c.zip.part", "uri://3", 50, 30),      // partial: never listed
        )
        val merged = DownloadedFiles.merge(records, scan)
        assertEquals(listOf("uri://1", "uri://2"), merged.map { it.uri })
        assertEquals("t1", merged[0].taskId)
    }

    @Test
    fun sortBySizeDescAndTimeDesc() {
        val list = listOf(
            e("small", "u1", 10, 300),
            e("big", "u2", 999, 100),
            e("mid", "u3", 500, 200),
        )
        assertEquals(
            listOf("big", "mid", "small"),
            DownloadedFiles.sort(list, DownloadedFiles.SORT_SIZE).map { it.name },
        )
        assertEquals(
            listOf("small", "mid", "big"),
            DownloadedFiles.sort(list, DownloadedFiles.SORT_TIME).map { it.name },
        )
    }

    @Test
    fun totalIgnoresUnknownSizes() {
        val list = listOf(e("a", "u1", 100, 0), e("b", "u2", -1, 0))
        assertEquals(100L, DownloadedFiles.totalSize(list))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.6: the category-folder mapping behind 按类型分文件夹. */
class FileCategoryTest {

    @Test
    fun knownExtensionsMapToTheirFolders() {
        assertEquals(FileCategory.VIDEO, FileCategory.folderFor("movie.MP4"))
        assertEquals(FileCategory.VIDEO, FileCategory.folderFor("stream.ts"))
        assertEquals(FileCategory.AUDIO, FileCategory.folderFor("song.flac"))
        assertEquals(FileCategory.IMAGE, FileCategory.folderFor("photo.JPG"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.folderFor("paper.pdf"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.folderFor("pack.tar.gz"))
        assertEquals(FileCategory.APK, FileCategory.folderFor("app.apk"))
    }

    @Test
    fun unknownOrMissingExtensionStaysAtTheRoot() {
        assertNull(FileCategory.folderFor("model.bin"))
        assertNull(FileCategory.folderFor("noextension"))
        assertNull(FileCategory.folderFor("trailingdot."))
        assertNull(FileCategory.folderFor(""))
    }

    @Test
    fun categoryPathsRoundTrip() {
        for (c in FileCategory.ALL) {
            assertTrue(FileCategory.isCategoryPath("Download/$c/"))
            assertEquals(c, FileCategory.categoryOfPath("Download/$c/"))
        }
        assertFalse(FileCategory.isCategoryPath("Download/"))
        assertNull(FileCategory.categoryOfPath("Download/"))
        assertFalse(FileCategory.isCategoryPath("Download/别的/"))
    }
}

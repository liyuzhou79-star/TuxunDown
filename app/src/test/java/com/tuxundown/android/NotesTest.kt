package com.tuxundown.android

import com.tuxundown.android.core.Notes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v3.7 (audit round 23): the converged engine notes are pinned
 * byte-for-byte — a wording change must be a deliberate edit of
 * Notes AND of these expectations, never an accident in one
 * download path.
 */
class NotesTest {

    @Test
    fun trafficGuardAtStartKeepsTheStandingWording() {
        assertEquals(
            "流量保护：当前是流量网络，这个文件有 100.0 MB，" +
                "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。",
            Notes.trafficGuardAtStart(100L * 1024 * 1024),
        )
    }

    @Test
    fun trafficGuardSwitchedWithSize() {
        assertEquals(
            "流量保护：网络已切到流量，这个文件有 100.0 MB，" +
                "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。",
            Notes.trafficGuardSwitched(100L * 1024 * 1024),
        )
    }

    @Test
    fun trafficGuardSwitchedHlsHasNoSizeClause() {
        assertEquals(
            "流量保护：网络已切到流量，已自动暂停省流量。回到 WiFi 点继续即可续传。",
            Notes.trafficGuardSwitchedHls(),
        )
    }

    @Test
    fun wifiOnlyAndStorageFull() {
        assertEquals(
            "网络已切到流量（已开启「仅 WiFi 下载」），已暂停；回到 WiFi 后点继续即可续传。",
            Notes.wifiOnlyPaused(),
        )
        assertEquals(
            "手机存储空间不足，下载已停下。清出空间后点继续即可续传。",
            Notes.storageFull(),
        )
    }

    @Test
    fun fileChangedVariants() {
        assertEquals(
            "服务器在下载途中换了文件（大小和刚才探到的对不上），已停下；点继续会重新开始",
            Notes.fileChangedSize(),
        )
        assertEquals(
            "服务器在下载途中换了文件（校验标记对不上），已停下；点继续会重新开始",
            Notes.fileChangedValidator(),
        )
    }
}

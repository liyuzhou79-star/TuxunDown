package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.UserAgents
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.7: User-Agent precedence — task header > user setting > default. */
class UserAgentTest {

    @Test
    fun taskHeaderWinsOverSetting() {
        assertEquals(
            "TaskUA/1.0",
            UserAgents.resolve("TaskUA/1.0", "SettingUA/2.0"),
        )
    }

    @Test
    fun settingUsedWhenNoTaskHeader() {
        assertEquals("SettingUA/2.0", UserAgents.resolve(null, "SettingUA/2.0"))
        assertEquals("SettingUA/2.0", UserAgents.resolve("", "SettingUA/2.0"))
    }

    @Test
    fun defaultWhenNeither() {
        assertEquals(DownloadEngine.DEFAULT_UA, UserAgents.resolve(null, null))
        assertEquals(DownloadEngine.DEFAULT_UA, UserAgents.resolve("", ""))
    }

    @Test
    fun presetLookup() {
        assertEquals(0, UserAgents.presetIndex(""))
        assertEquals(-1, UserAgents.presetIndex("MyOwnUA/9.9"))
    }
}

package com.tuxundown.android

import com.tuxundown.android.core.NotificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v6.8: notification settings self-check line verdicts. */
class NotificationStatusTest {

    @Test
    fun unreadablePermissionMeansNoLineForEveryQuietState() {
        // Read failure = the element is absent, never a guess —
        // for every quiet combination.
        for (enabled in listOf(false, true)) {
            for (active in listOf(false, true)) {
                assertNull(NotificationStatus.statusLine(null, enabled, active))
            }
        }
    }

    @Test
    fun quietStateSplitsOffOutsideAndActive() {
        assertEquals(
            NotificationStatus.QuietState.OFF,
            NotificationStatus.quietState(quietEnabled = false, quietActive = false),
        )
        assertEquals(
            NotificationStatus.QuietState.ON_OUTSIDE,
            NotificationStatus.quietState(quietEnabled = true, quietActive = false),
        )
        assertEquals(
            NotificationStatus.QuietState.ACTIVE,
            NotificationStatus.quietState(quietEnabled = true, quietActive = true),
        )
    }

    @Test
    fun quietStateActiveWinsWhenReadsDisagree() {
        // quietHoursActive() already includes the switch, so
        // this cannot happen from the real reads; if it ever
        // did, "in effect right now" is the honest answer.
        assertEquals(
            NotificationStatus.QuietState.ACTIVE,
            NotificationStatus.quietState(quietEnabled = false, quietActive = true),
        )
    }

    @Test
    fun enabledQuietOff() {
        assertEquals(
            "通知现状：通知已开启；通知静默时段没开启。",
            NotificationStatus.statusLine(true, quietEnabled = false, quietActive = false),
        )
    }

    @Test
    fun enabledQuietOnButOutsideWindow() {
        assertEquals(
            "通知现状：通知已开启；通知静默时段已开启，现在不在静默时段里。",
            NotificationStatus.statusLine(true, quietEnabled = true, quietActive = false),
        )
    }

    @Test
    fun enabledQuietActive() {
        assertEquals(
            "通知现状：通知已开启；现在正在通知静默时段里，下载完成不震动、不响铃，完成通知会出现在通知栏。",
            NotificationStatus.statusLine(true, quietEnabled = true, quietActive = true),
        )
    }

    @Test
    fun disabledQuietOffSaysNotificationsWillNotAppear() {
        assertEquals(
            "通知现状：通知没开启，下载完成和下载失败的通知都不会出现在通知栏，" +
                "去系统设置里开启通知后才会出现；通知静默时段没开启。",
            NotificationStatus.statusLine(false, quietEnabled = false, quietActive = false),
        )
    }

    @Test
    fun disabledQuietOnButOutsideWindow() {
        assertEquals(
            "通知现状：通知没开启，下载完成和下载失败的通知都不会出现在通知栏，" +
                "去系统设置里开启通知后才会出现；通知静默时段已开启，现在不在静默时段里。",
            NotificationStatus.statusLine(false, quietEnabled = true, quietActive = false),
        )
    }

    @Test
    fun disabledQuietActiveDoesNotPromiseTheShade() {
        // Permission off + quiet active: the line must NOT
        // claim the completion notification appears in the
        // shade (the old static quiet copy's flaw).
        assertEquals(
            "通知现状：通知没开启，下载完成和下载失败的通知都不会出现在通知栏，" +
                "去系统设置里开启通知后才会出现；现在正在通知静默时段里，下载完成不震动、不响铃。",
            NotificationStatus.statusLine(false, quietEnabled = true, quietActive = true),
        )
    }
}

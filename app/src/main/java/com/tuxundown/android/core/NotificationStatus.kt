package com.tuxundown.android.core

/**
 * Notification settings self-check line (v6.8) — pure,
 * unit-tested.
 *
 * The settings 「提示」 group shows ONE status line answering
 * two questions in the group's own words:
 *  - are notifications enabled for this app (read live from
 *    the system by the UI — NotificationManagerCompat
 *    .areNotificationsEnabled, the app's first read site), and
 *  - where do the 通知静默时段 stand right now.
 *
 * Quiet hours are THREE states, not two, and the wording
 * keeps them apart:
 *  - OFF — the switch is off;
 *  - ON_OUTSIDE — the switch is on but now is outside the
 *    window (completions still vibrate / ring);
 *  - ACTIVE — now is inside the window
 *    ([DownloadCenter.quietHoursActive] is true: completions
 *    post to the shade but do not vibrate or ring).
 *
 * Unreadable is absent, never guessed: when the UI cannot
 * read the permission state it passes null here, and this
 * verdict returns null — the UI then renders NO line at all
 * rather than a made-up state.
 *
 * Wording follows Step B's silence caliber: the 下载完成
 * 震动 / 下载完成提示音 switches govern completion feedback
 * only, so nothing here claims they (or quiet hours) govern
 * failure notifications. When notifications are off, the
 * line says plainly that completion and failure
 * notifications will not appear in the shade — vibrate /
 * ringtone feedback is a separate fate (audit round 53,
 * item (6)) and is not promised either way here.
 */
object NotificationStatus {

    enum class QuietState { OFF, ON_OUTSIDE, ACTIVE }

    /**
     * The quiet verdict from the switch and the live
     * [DownloadCenter.quietHoursActive] read. quietHoursActive
     * already includes the switch, so ACTIVE wins if the two
     * ever disagree — "in effect right now" is the question
     * this state answers.
     */
    fun quietState(quietEnabled: Boolean, quietActive: Boolean): QuietState =
        when {
            quietActive -> QuietState.ACTIVE
            quietEnabled -> QuietState.ON_OUTSIDE
            else -> QuietState.OFF
        }

    /**
     * The one status line, or null when [notificationsEnabled]
     * is unknown (unreadable) — the caller renders nothing.
     */
    fun statusLine(
        notificationsEnabled: Boolean?,
        quietEnabled: Boolean,
        quietActive: Boolean,
    ): String? {
        if (notificationsEnabled == null) return null
        return statusLine(notificationsEnabled, quietState(quietEnabled, quietActive))
    }

    fun statusLine(notificationsEnabled: Boolean, quiet: QuietState): String {
        val permission = if (notificationsEnabled) {
            "通知已开启"
        } else {
            "通知没开启，下载完成和下载失败的通知都不会出现在通知栏，" +
                "去系统设置里开启通知后才会出现"
        }
        val quietText = when (quiet) {
            QuietState.OFF -> "通知静默时段没开启"
            QuietState.ON_OUTSIDE -> "通知静默时段已开启，现在不在静默时段里"
            QuietState.ACTIVE ->
                if (notificationsEnabled) {
                    "现在正在通知静默时段里，下载完成不震动、不响铃，完成通知会出现在通知栏"
                } else {
                    "现在正在通知静默时段里，下载完成不震动、不响铃"
                }
        }
        return "通知现状：$permission；$quietText。"
    }
}

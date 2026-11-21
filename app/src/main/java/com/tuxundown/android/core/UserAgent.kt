package com.tuxundown.android.core

/**
 * Custom User-Agent support (v1.7): some servers serve different
 * (or any) content depending on the User-Agent, so advanced users
 * can pick a preset or type their own. Precedence is strict:
 *
 *   a UA captured for THIS task (sniffer / built-in browser)
 *   > the user's setting
 *   > the built-in default.
 *
 * Pure logic — unit-tested. The setting itself lives in
 * DownloadCenter; the settings screen offers [PRESETS] plus a
 * free-text field.
 */
object UserAgents {

    /** (label shown in settings, UA string); "" = 跟随默认. */
    val PRESETS: List<Pair<String, String>> = listOf(
        "默认（安卓 Chrome）" to "",
        "电脑 Chrome（Windows）" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "安卓 Chrome" to
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "iPhone Safari" to
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 " +
            "Mobile/15E148 Safari/604.1",
        "电脑 Firefox" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) " +
            "Gecko/20100101 Firefox/121.0",
        "下载工具（curl）" to "curl/8.5.0",
    )

    /** The UA a request should actually carry. */
    fun resolve(taskHeader: String?, userSetting: String?): String {
        if (!taskHeader.isNullOrBlank()) return taskHeader
        if (!userSetting.isNullOrBlank()) return userSetting.trim()
        return DownloadEngine.DEFAULT_UA
    }

    /** Index of [ua] in PRESETS, or -1 when it is a custom string. */
    fun presetIndex(ua: String): Int =
        PRESETS.indexOfFirst { it.second == ua }
}

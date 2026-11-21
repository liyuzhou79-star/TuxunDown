package com.tuxundown.android.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check (v2.0.0).
 *
 * This is the app's ONLY self-initiated network request, and it
 * runs ONLY when the user taps 检查更新 in the About section —
 * never on launch, never on a timer, never in the background.
 * It asks GitHub's public API for the latest FORMAL release of
 * this project (the /releases/latest endpoint skips drafts and
 * pre-releases by design), compares its version with the one
 * installed, and — when newer — offers to download the release
 * APK with the app's own download engine (so it lands in the
 * public Download folder like any other file and can be
 * installed from the task card with one tap).
 *
 * Everything here fails in plain language: no network, a
 * rate-limited or moved API, a release without an APK asset —
 * each becomes a sentence the user can act on, never a stack
 * trace and never a silent nothing.
 */
object UpdateCheck {

    const val LATEST_API_URL =
        "https://api.github.com/repos/liyuzhou79-star/TuxunDown/releases/latest"

    const val RELEASES_PAGE_URL =
        "https://github.com/liyuzhou79-star/TuxunDown/releases"

    /** Body read cap: release JSON is a few KB; anything beyond
     *  this is not a releases response we should keep parsing. */
    private const val MAX_BODY_BYTES = 512 * 1024

    data class Latest(
        /** Version without the leading "v" ("2.0.0"). */
        val version: String,
        /** Release notes (the release page body), may be blank. */
        val notes: String,
        /** Direct APK asset URL ("" only if constructed so). */
        val apkUrl: String,
        /** APK asset file name, e.g. TuxunDown-v2.0.0.apk. */
        val apkName: String,
    )

    sealed interface Result {
        data class Found(val latest: Latest) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Numeric dotted-version compare: positive when [a] is newer
     * than [b], negative when older, 0 when equal. A leading "v"
     * is ignored; a part's non-digit suffix is ignored ("0-beta"
     * counts as 0); missing parts count as 0 ("2.0" == "2.0.0").
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    fun isNewer(latest: String, current: String): Boolean =
        compareVersions(latest, current) > 0

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /**
     * Parse a /releases/latest response body. Returns null when
     * the shape is not what the endpoint promises (the caller
     * turns that into a plain "没查到" sentence). A release
     * whose assets contain no .apk is also null — an update the
     * user cannot install from here is not an update offer.
     */
    fun parseLatest(json: String): Latest? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = root.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var apkName = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                apkName = name
                break
            }
        }
        if (apkUrl.isBlank()) return null
        return Latest(
            version = tag.removePrefix("v").removePrefix("V"),
            notes = root.optString("body").trim(),
            apkUrl = apkUrl,
            apkName = apkName,
        )
    }

    /**
     * The actual check. Blocking IO — call from a background
     * thread. Goes through the user's download proxy when one is
     * configured (ProxyState), like the sniffer does: for many
     * users GitHub is only reachable that way, and the proxy is
     * theirs.
     */
    fun fetchLatest(): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = ProxyState.open(URL(LATEST_API_URL))
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TuxunDown")
            val code = conn.responseCode
            if (code !in 200..299) {
                // v2.2 audit round 8: shared HTTP wording.
                return Result.Failed("没查到更新：${Errors.httpMessage(code)}")
            }
            val body = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BODY_BYTES) break
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
            val latest = parseLatest(body)
            if (latest == null) {
                Result.Failed("没查到更新：发布信息格式不对，或者最新版里没有安装包文件。")
            } else {
                Result.Found(latest)
            }
        } catch (e: Exception) {
            if (ProxyState.settings.active) {
                Result.Failed(
                    "没查到更新：${ProxySettings.unreachableMessage(ProxyState.settings, e)}"
                )
            } else {
                Result.Failed("没查到更新：${Errors.human(e)}")
            }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

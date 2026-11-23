package com.tuxundown.android.core

import android.content.Context
import java.io.File

/**
 * Crash self-rescue (v2.2) — strictly local, never uploaded.
 *
 * A default uncaught-exception handler is installed at process
 * start. When the app dies anyway, the handler first saves the
 * stack trace to a private file (and bumps a consecutive-crash
 * streak), then hands over to the previous handler so the
 * system still does its normal kill/dialog. On the NEXT launch
 * the UI finds the pending report and shows a friendly page:
 * what happened in plain words, a 复制日志 button (so the user
 * can send the trace to the developer through whatever channel
 * they like) and a 继续打开 button. Nothing is transmitted by
 * the app itself — this is a reporting aid for the user, not
 * telemetry.
 *
 * Crash-loop protection: the streak counts crashes with no
 * stable run in between ([markStable] clears it once the UI
 * has been up for a few seconds). At streak >= 2 the launch
 * goes into SAFE MODE: the onboarding guide and the clipboard
 * prompt — the two first-launch actions that touch the most
 * machinery — are skipped for that launch, and the crash page
 * says so. A crash caused by one of them therefore cannot loop
 * forever.
 *
 * Everything here is defensive: the handler itself is wrapped
 * so it can never throw, and every read is runCatching — the
 * rescue path must be the most boring code in the app.
 */
object CrashGuard {

    private const val PREFS = "crash_guard"
    private const val KEY_STREAK = "streak"
    private const val KEY_PENDING = "pending"
    private const val LOG_FILE = "last_crash.txt"
    const val MAX_LOG_CHARS = 32 * 1024
    const val SAFE_MODE_STREAK = 2

    /** True when this process should skip first-launch extras. */
    @Volatile var safeMode: Boolean = false
        private set

    data class CrashInfo(
        val summary: String,
        val detail: String,
        val streak: Int,
        val safeMode: Boolean,
    )

    // ---------- pure decisions (unit-tested) ----------

    fun shouldSafeMode(streak: Int): Boolean = streak >= SAFE_MODE_STREAK

    /** One plain sentence naming the kind of failure. */
    fun describe(className: String): String = when {
        className.contains("OutOfMemoryError") ->
            "内存不够用了（多半是同时开的任务或文件太大）"
        className.contains("SQLite") || className.contains("Database") ->
            "本地任务记录读写出了问题"
        className.contains("SecurityException") ->
            "系统权限被拒绝了（可能是厂商系统收紧了某个权限）"
        className.contains("IllegalStateException") &&
            className.contains("painterResource", ignoreCase = true) ->
            "界面资源加载出了问题"
        else -> "遇到一个没预料到的问题，应用自己停下了"
    }

    /** Keep the HEAD of a log: the exception line and the top
     *  frames are the part a developer needs most. */
    fun truncateLog(text: String, max: Int = MAX_LOG_CHARS): String =
        if (text.length <= max) text else text.substring(0, max) + "\n…（太长了，后面已截断）"

    fun buildLog(
        timeText: String,
        versionName: String,
        threadName: String,
        throwable: Throwable,
    ): String {
        val trace = runCatching {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            sw.toString()
        }.getOrDefault(throwable.toString())
        return truncateLog(
            "TuxunDown 崩溃记录\n" +
                "时间：$timeText\n" +
                "版本：$versionName\n" +
                "线程：$threadName\n" +
                "异常：${throwable.javaClass.name}: ${throwable.message ?: ""}\n\n" +
                trace
        )
    }

    // ---------- storage ----------

    private fun logFile(context: Context): File = File(context.filesDir, LOG_FILE)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun streak(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_STREAK, 0) }.getOrDefault(0)

    /** The pending crash report for this launch, or null. */
    fun pendingCrash(context: Context): CrashInfo? = runCatching {
        val p = prefs(context)
        if (!p.getBoolean(KEY_PENDING, false)) return null
        val detail = logFile(context).takeIf { it.exists() }
            ?.readText()?.takeIf { it.isNotBlank() } ?: return null
        val streakNow = p.getInt(KEY_STREAK, 0)
        val firstLine = detail.lineSequence()
            .firstOrNull { it.startsWith("异常：") }
            ?.removePrefix("异常：")?.trim().orEmpty()
        val className = firstLine.substringBefore(':').trim()
        CrashInfo(
            summary = describe(className),
            detail = detail,
            streak = streakNow,
            safeMode = shouldSafeMode(streakNow),
        )
    }.getOrNull()

    /** User tapped 继续打开 (or copied the log): the report is
     *  dealt with. The streak is NOT cleared here — only a
     *  stable run clears it ([markStable]). */
    fun clearPending(context: Context) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_PENDING, false).apply()
            logFile(context).delete()
        }
    }

    /** The UI survived a few seconds: the crash streak is over. */
    fun markStable(context: Context) {
        runCatching {
            prefs(context).edit().putInt(KEY_STREAK, 0).apply()
        }
    }

    /**
     * Install the handler (idempotent per process). Called first
     * thing in MainActivity.onCreate and DownloadService.onCreate
     * — whichever process entry runs first wins; the other call
     * sees [installed] and no-ops.
     */
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        safeMode = shouldSafeMode(streak(appContext))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val version = runCatching {
                    appContext.packageManager
                        .getPackageInfo(appContext.packageName, 0).versionName
                }.getOrNull() ?: "?"
                val timeText = ExportStamp.format(System.currentTimeMillis())
                logFile(appContext).writeText(
                    buildLog(timeText, version, thread.name, throwable)
                )
                prefs(appContext).edit()
                    .putInt(KEY_STREAK, streak(appContext) + 1)
                    .putBoolean(KEY_PENDING, true)
                    .commit()
            }
            // Hand over: the system still shows its crash dialog
            // and kills the process exactly as before. [previous]
            // was captured BEFORE this handler was installed, so
            // it can never be this handler itself.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No previous handler (should not happen): make
                // sure the process still dies instead of hanging
                // in a broken state.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}

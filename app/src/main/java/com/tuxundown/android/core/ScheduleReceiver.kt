package com.tuxundown.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * v3.5: the scheduled-start backup alarm's landing spot
 * (see ScheduleWake for the audit and the honest limits).
 *
 * Minimum work, all runCatching: re-arm for the next slot,
 * and — only when a scheduled task is actually DUE — try
 * to start the download service so its drain loop picks
 * the task up. The kick is skipped in CrashGuard safe
 * mode (after a crash streak the user decides what runs
 * next, not an automatic path); the re-arm is NOT skipped,
 * because arming starts nothing by itself. A refused
 * background start (Android 12+ without the battery
 * whitelist) is caught by the same runCatching: the task
 * then starts at the next app open, exactly as before.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            if (intent?.action != ACTION) return
            DownloadCenter.init(context)
            val tasks = DownloadCenter.store.tasks
            ScheduleWake.arm(context, tasks)
            if (CrashGuard.safeMode) return
            if (!ScheduleWake.anyDue(tasks, System.currentTimeMillis())) return
            val svc = Intent(context, DownloadService::class.java)
                .setAction(DownloadService.ACTION_SCHEDULE_WAKE)
            ContextCompat.startForegroundService(context, svc)
        }
    }

    companion object {
        const val ACTION = "com.tuxundown.android.action.SCHEDULE_WAKE"
    }
}

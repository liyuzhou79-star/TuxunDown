package com.tuxundown.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * v2.6: boot-completed entry for the (default-OFF)「开机后自动
 * 恢复未完成任务」setting.
 *
 * The receiver itself does the minimum: read the switch, and —
 * only when it is ON — start the download service with a
 * dedicated action. The actual recovery (which tasks re-queue,
 * the WiFi-only / traffic guards) lives in the service and
 * DownloadCenter, exactly where a normal start enforces them;
 * a boot start gets no special privileges and no shortcut past
 * any guard. When the switch is off the receiver returns at
 * once and nothing in the app runs at boot beyond this check.
 *
 * Everything is runCatching: a boot receiver must never be able
 * to crash, and a failed kick simply means the tasks wait for
 * the user's next open, like they always did.
 *
 * v6.4 step C: the same receiver also takes the system's
 * TIMEZONE_CHANGED and TIME_SET broadcasts (system broadcasts
 * only — exported=false, no new permission). Those take the
 * light path in [reanchorAfterClockChange]: re-anchor QUEUED
 * daily tasks to their stored wall-clock pick in the zone now
 * in force, then re-arm the backup alarm. The boot path below
 * is untouched by them, and they never kick the service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_TIMEZONE_CHANGED ||
                action == Intent.ACTION_TIME_CHANGED
            ) {
                reanchorAfterClockChange(context)
                return
            }
            if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != ACTION_QUICKBOOT
            ) {
                return
            }
            DownloadCenter.init(context)
            // v3.5: alarms do not survive a reboot — re-arm
            // the scheduled-start backup (see ScheduleWake)
            // whatever the boot-resume switch below says.
            // Arming starts nothing by itself; the service
            // kick stays behind the switch, as before.
            ScheduleWake.arm(context, DownloadCenter.store.tasks)
            if (!DownloadCenter.bootResume) return
            if (!Recovery.anyQueued(DownloadCenter.store.tasks) &&
                Recovery.interrupted(DownloadCenter.store.tasks).isEmpty()
            ) {
                return
            }
            val svc = Intent(context, DownloadService::class.java)
                .setAction(DownloadService.ACTION_BOOT_RESUME)
            ContextCompat.startForegroundService(context, svc)
        }
    }

    /**
     * v6.4 step C light path for TIMEZONE_CHANGED / TIME_SET:
     * the wall clock (or the zone it is read in) just changed,
     * so a QUEUED daily task's already-computed slot may now
     * read as the wrong local time. Re-anchor those slots from
     * their stored picks in the CURRENT zone (the pure
     * ScheduleTime.reanchorQueuedDaily decides what moves —
     * one-shot tasks and pick-less legacy daily tasks are not
     * touched), persist only when something actually changed,
     * and re-arm the backup alarm at the new earliest slot.
     *
     * The service is deliberately NOT kicked: nothing here is
     * due yet by construction (re-anchored slots are strictly
     * in the future), and due-ness stays with the existing
     * paths — the drain loop and ScheduleWake's alarm. If a
     * clock set FORWARD left some OTHER task (a one-shot, say)
     * already due, the re-armed alarm and the next app open
     * pick it up exactly as they did before this path existed;
     * this path changes no due semantics.
     */
    private fun reanchorAfterClockChange(context: Context) {
        DownloadCenter.init(context)
        val tasks = DownloadCenter.store.tasks
        val changed = ScheduleTime.reanchorQueuedDaily(
            tasks, System.currentTimeMillis(),
        )
        if (changed > 0) DownloadCenter.store.saveNow()
        ScheduleWake.arm(context, tasks)
    }

    companion object {
        /** HTC-era alias some ROMs still send instead of BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}

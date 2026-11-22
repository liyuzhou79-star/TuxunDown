package com.tuxundown.android.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * v3.5 audit round 21 (Doze & scheduled-start reliability):
 * the BACKUP trigger for scheduled downloads.
 *
 * How a scheduled start actually happens (audited): the
 * foreground service's drain loop stays alive in its
 * 预约下载待机 state and polls in 2–30 s slices until
 * QueueOrder says a task is due. That works while the
 * process lives — a running foreground service makes the
 * app a foreground app, so Doze does not defer its threads
 * or its network, and the wait is a live thread, not an
 * alarm, so AlarmManager's Doze deferral never applies to
 * it. It does NOT survive the process: a swipe-kill, a
 * Flyme freeze-kill or a force-stop leaves nothing running
 * at the scheduled time, and a reboot clears everything
 * (the boot receiver only kicks the service behind the
 * default-off 开机恢复 switch). In all those cases the
 * task is never LOST — the drain picks it up the moment
 * the service next runs — but it starts late.
 *
 * This object is the cheap insurance: ONE inexact
 * AlarmManager alarm (setAndAllowWhileIdle — needs NO
 * permission, unlike the exact-alarm APIs, which would
 * need SCHEDULE_EXACT_ALARM; deliberately not used) armed
 * at the earliest future scheduled slot. When it fires,
 * ScheduleReceiver re-arms for the next slot and, if a
 * task is actually due, tries to start the service.
 * Honest limits, also stated in the settings/help copy:
 * - inexact: it can land minutes late, and in deep Doze
 *   the system may defer it further (maintenance windows,
 *   a per-app allowance while dozing);
 * - on Android 12+, starting a foreground service from
 *   the background can be refused unless the app is
 *   battery-whitelisted — the receiver runCatches that,
 *   and the task then simply starts at the next app open,
 *   exactly as it did before v3.5.
 * The alarm is a backup for the poll, never the mechanism
 * of record: with the service alive it fires into a drain
 * loop that is already waiting, which is harmless.
 */
object ScheduleWake {

    /** QUEUED tasks whose scheduled time has arrived. */
    fun dueTasks(tasks: List<TaskRecord>, now: Long): List<TaskRecord> =
        tasks.filter {
            it.status == TaskStatus.QUEUED && it.scheduledAt in 1..now
        }

    fun anyDue(tasks: List<TaskRecord>, now: Long): Boolean =
        dueTasks(tasks, now).isNotEmpty()

    /** The instant to arm the backup alarm at (0 = disarm). */
    fun nextWakeAt(tasks: List<TaskRecord>, now: Long): Long =
        QueueOrder.nextScheduledAt(tasks, now)

    // ---- platform side: one shared PendingIntent, re-armed
    // wholesale. Arming is idempotent and cheap, so callers
    // re-arm liberally (init, enqueue, pause, boot, every
    // service start, the receiver itself). ----

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Arm at the earliest future slot, or cancel when none. */
    fun arm(context: Context, tasks: List<TaskRecord>) {
        runCatching {
            val ctx = context.applicationContext
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(ctx)
            val at = nextWakeAt(tasks, System.currentTimeMillis())
            if (at > 0) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.cancel(pi)
            }
        }
    }

    private const val REQUEST_CODE = 0x5CED
}

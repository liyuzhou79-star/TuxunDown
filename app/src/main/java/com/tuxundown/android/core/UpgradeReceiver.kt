package com.tuxundown.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tuxundown.android.widget.WidgetUpdater

/**
 * v3.8 audit round 24 (entry points after an overlay
 * upgrade): the system delivers MY_PACKAGE_REPLACED to the
 * updated app itself, once, right after the install — even
 * when the process was dead, and an updated app is not in
 * the stopped state (only a force-stop puts it there). That
 * makes this broadcast the one reliable moment to put the
 * system-facing entries back in order before the user ever
 * opens the app:
 * - the scheduled-start backup alarm (see ScheduleWake):
 *   DownloadCenter.init already re-arms it via
 *   refreshScheduleAlarm; whether the platform kept the old
 *   alarm across the update or dropped it, it is now
 *   certainly armed at the earliest future slot — and when
 *   it WAS kept, re-arming the same PendingIntent is a
 *   harmless replacement;
 * - the home-screen widget: refreshed so already-placed
 *   widgets are rebuilt from the NEW apk's resources (this
 *   is also what lands the v3.7 night layouts on them).
 * Everything else needs nothing here, by construction
 * (round 24 verified each against the code): the
 * quick-settings tile re-reads live state every time the
 * panel opens (onStartListening), the long-press shortcuts
 * are static XML the launcher re-reads from the new
 * manifest, the boot receiver is manifest-registered and
 * fires at the next boot exactly as before (an update does
 * not replay BOOT_COMPLETED, and none is needed), and
 * FileProvider grants are per-intent while the SAF folder
 * permission is keyed by package+uid — an overlay update
 * keeps both the package and (same signing key) the uid,
 * so grants survive untouched.
 * runCatching throughout: a receiver must never crash.
 */
class UpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            DownloadCenter.init(context)
            DownloadCenter.refreshScheduleAlarm()
            WidgetUpdater.refresh(context)
        }
    }
}

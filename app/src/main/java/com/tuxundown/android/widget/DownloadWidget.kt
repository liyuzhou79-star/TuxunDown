package com.tuxundown.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tuxundown.android.MainActivity
import com.tuxundown.android.R
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.WidgetState

/**
 * v2.1: home-screen widget — how many tasks are in flight and
 * their overall progress; tap opens the app. Hand-written
 * RemoteViews on the platform widget framework, no new
 * dependency (deliberately NOT Glance: a whole Compose-runtime
 * stack for two text lines would break the size red line).
 *
 * Updates are PUSHED by the download service (every notification
 * post refreshes the widget too) and by DownloadCenter after
 * UI-side changes; updatePeriodMillis is 0. Everything here is
 * best-effort by design: a widget must never be able to take
 * the download service down with it, so refresh() swallows all
 * failures.
 */
class DownloadWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdater.refresh(context)
    }
}

object WidgetUpdater {
    fun refresh(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, DownloadWidgetProvider::class.java)
            if (manager.getAppWidgetIds(component).isEmpty()) return
            // A launcher update broadcast can wake the widget
            // before anything else in the app ran — the store
            // must exist before it can be read.
            DownloadCenter.init(context)
            val s = WidgetState.summary(DownloadCenter.store.tasks)
            val views = RemoteViews(context.packageName, R.layout.widget_download)
            views.setTextViewText(
                R.id.widget_title,
                if (s.activeCount > 0) "正在下载 ${s.activeCount} 个任务" else "TuxunDown",
            )
            views.setTextViewText(
                R.id.widget_line,
                if (s.activeCount > 0) "总进度 ${s.totalPct}%"
                else "没有进行中的任务，点这里打开",
            )
            views.setProgressBar(R.id.widget_progress, 100, s.totalPct, false)
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(component, views)
        }
    }
}

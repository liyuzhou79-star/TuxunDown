package com.tuxundown.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.tuxundown.android.MainActivity
import com.tuxundown.android.R
import com.tuxundown.android.core.BulkControl
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.WidgetState

/**
 * v2.1: home-screen widget — how many tasks are in flight and
 * their overall progress; tap opens the app. Hand-written
 * RemoteViews on the platform widget framework, no new
 * dependency (deliberately NOT Glance: a whole Compose-runtime
 * stack for two text lines would break the size red line).
 *
 * v4.1: the widget gains a 全部暂停 / 全部继续 button. Its label
 * and visibility come from the pure [BulkControl] decision the
 * quick-settings tile and the home summary card already share,
 * and its tap is a broadcast back to this provider that runs
 * the exact tile path — BulkControl.action over the store,
 * then DownloadCenter.pauseAll() / resumeAll(). No second
 * implementation of the semantics lives here.
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_BULK_TOGGLE) {
            // Same calls, same order as DownloadTileService
            // .onClick. Wrapped like everything widget-side:
            // a toggle must never crash broadcast delivery.
            runCatching {
                DownloadCenter.init(context)
                when (BulkControl.action(DownloadCenter.store.tasks)) {
                    BulkControl.Action.PAUSE_ALL -> DownloadCenter.pauseAll()
                    BulkControl.Action.RESUME_ALL -> DownloadCenter.resumeAll()
                    BulkControl.Action.NONE -> Unit
                }
            }
            // pauseTask / enqueue already push a widget refresh
            // through DownloadCenter, but on NONE (or a call
            // that failed midway) nothing else would repaint —
            // refresh once more from the store's current truth
            // so the button never sits on its pre-tap label
            // (the v4.0 stale-value lesson).
            WidgetUpdater.refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        /** Explicit-component broadcast action for the widget's
         *  bulk-toggle button; only our own PendingIntent (sent
         *  with this app's identity, provider exported=false)
         *  can deliver it. */
        const val ACTION_BULK_TOGGLE = "com.tuxundown.android.widget.BULK_TOGGLE"
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
            val tasks = DownloadCenter.store.tasks
            val s = WidgetState.summary(tasks)
            val views = RemoteViews(context.packageName, R.layout.widget_download)
            views.setTextViewText(
                R.id.widget_title,
                // v5.3 (audit round 38): the wording verdict is
                // WidgetState.headline's — 正在下载 only when a
                // task is really RUNNING, 排队中 for a queue that
                // is merely waiting / parked (see WidgetState).
                WidgetState.headline(s),
            )
            views.setTextViewText(
                R.id.widget_line,
                // v7.5 (audit round 60): the wording verdict is
                // WidgetState.line's — with nothing active but a
                // task in its finalize window it says 正在收尾
                // instead of 没有进行中的任务 (see WidgetState).
                WidgetState.line(s),
            )
            views.setProgressBar(R.id.widget_progress, 100, s.totalPct, false)
            // v4.1: bulk-toggle button, driven by the same
            // BulkControl decision as every refresh — the label
            // is recomputed here, never remembered, so it cannot
            // go stale. NONE hides the button outright (a dead
            // button on the home screen would just be noise).
            val bulkLabel = BulkControl.buttonLabel(BulkControl.action(tasks))
            if (bulkLabel == null) {
                views.setViewVisibility(R.id.widget_bulk_button, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_bulk_button, View.VISIBLE)
                views.setTextViewText(R.id.widget_bulk_button, bulkLabel)
                val togglePi = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, DownloadWidgetProvider::class.java)
                        .setAction(DownloadWidgetProvider.ACTION_BULK_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_bulk_button, togglePi)
            }
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

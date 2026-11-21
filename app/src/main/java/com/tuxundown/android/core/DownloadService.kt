package com.tuxundown.android.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tuxundown.android.MainActivity

/** Foreground service that drains the task queue one task at a time. */
class DownloadService : Service() {

    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadCenter.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("准备下载…", 0))
        if (worker?.isAlive != true) {
            worker = Thread { drainQueue() }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    private fun drainQueue() {
        val store = DownloadCenter.store
        while (true) {
            val task = DownloadCenter.nextQueued() ?: break
            if (DownloadCenter.wifiOnly && !DownloadCenter.isOnWifi()) {
                task.note = "已开启「仅 WiFi 下载」，等待 WiFi 网络。"
                store.save()
                break
            }
            task.status = TaskStatus.RUNNING
            store.save()
            val engine = DownloadEngine(
                task = task,
                file = store.fileOf(task),
                onProgress = { t ->
                    val pct = (t.progress * 100).toInt()
                    notifyUpdate("${t.fileName} · ${formatSpeed(t.speedBps)}", pct)
                },
                onSave = { store.save() },
            )
            DownloadCenter.registerEngine(task, engine)
            try {
                engine.run()
            } finally {
                DownloadCenter.unregisterEngine(task)
                store.save()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("TuxunDown 正在下载")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, pct, pct == 0)
            .build()
    }

    private fun notifyUpdate(text: String, pct: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, pct))
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1

        fun formatSpeed(bps: Long): String = when {
            bps <= 0 -> "—"
            bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / 1024.0 / 1024.0)
            bps >= 1024 -> "%.0f KB/s".format(bps / 1024.0)
            else -> "$bps B/s"
        }
    }
}

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
                if (task.status == TaskStatus.DONE) exportFinished(task)
            } finally {
                DownloadCenter.unregisterEngine(task)
                store.save()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Move a finished file out of the private scratch area into the
     * user's download folder under its real name. On failure the file
     * stays inside the app and the task note says why.
     */
    private fun exportFinished(task: TaskRecord) {
        val store = DownloadCenter.store
        val src = store.fileOf(task)
        if (!src.exists()) return
        val name = store.finalName(task)
        try {
            val out = FileExporter.export(this, src, name)
            task.savedUri = out.uri.toString()
            task.savedName = out.displayName
            task.note = "已保存到${out.where}：${out.displayName}"
            src.delete()
            notifyUpdate("下载完成：${out.displayName}", 100)
        } catch (e: Exception) {
            task.note = "下载完成，但保存到目标文件夹失败（${e.message ?: "未知错误"}），" +
                "文件暂存在应用内，可用「打开/分享」取出，或在设置里重选下载文件夹后重试。"
        }
        store.save()
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

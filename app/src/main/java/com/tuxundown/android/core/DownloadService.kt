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
        startForeground(NOTIF_ID, buildNotification("准备下载…", "", 0))
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
            val legacy = store.fileOf(task)
            val engine = DownloadEngine(
                task = task,
                targetProvider = {
                    val obtained = PublicStorage.obtainTarget(this, task)
                    // One-time copy of any <= v1.1 private partial into
                    // the new public .part; afterwards the private
                    // directory is never used for downloads again.
                    PublicStorage.migrateLegacyIfNeeded(task, obtained, legacy)
                    store.save()
                    obtained.target
                },
                onProgress = { notifyAggregate() },
                onSave = { store.save() },
            )
            DownloadCenter.registerEngine(task, engine)
            try {
                engine.run()
                if (task.status == TaskStatus.DONE) finalizeFinished(task)
            } finally {
                DownloadCenter.unregisterEngine(task)
                store.save()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The bytes are already in the public folder as "<name>.part";
     * finishing is just an in-place rename to the real name.
     */
    private fun finalizeFinished(task: TaskRecord) {
        val store = DownloadCenter.store
        try {
            val displayName = PublicStorage.finalizeTarget(this, task)
            val where = if (DownloadCenter.downloadDirUri.isNotBlank()) {
                "已保存到你选择的文件夹"
            } else {
                "已保存到公共「下载」目录"
            }
            task.note = "$where：$displayName"
            notifyUpdate("下载完成", displayName, 100)
        } catch (e: Exception) {
            task.note = "下载完成，但改名失败（${e.message ?: "未知错误"}），" +
                "文件在下载目录里，名字带 .part 后缀，去掉后缀即可使用。"
            notifyAggregate()
        }
        store.save()
    }

    // ---------- notification: same Windows-style wording as the UI ----------

    private fun notifyAggregate() {
        val store = DownloadCenter.store
        val active = store.tasks.filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
        }
        val running = store.tasks.firstOrNull { it.status == TaskStatus.RUNNING }
        if (running == null) {
            notifyUpdate("TuxunDown", "准备下载…", 0)
            return
        }
        val totalAll = active.filter { it.totalSize > 0 }.sumOf { it.totalSize }
        val doneAll = active.filter { it.totalSize > 0 }.sumOf { it.downloaded }
        val totalPct = if (totalAll > 0) (doneAll * 100 / totalAll).toInt() else 0
        val eta = running.etaSeconds
        val text = buildString {
            append(Format.speed(running.speedBps))
            if (eta > 0) append(" · 还剩 ${Format.eta(eta)}")
            append(" · 已完成 ${(running.progress * 100).toInt()}%")
        }
        if (active.size > 1) {
            // Multi-task: the notification shows the OVERALL progress.
            notifyUpdate(
                "正在下载 ${active.size} 个任务 · ${running.fileName}",
                text,
                totalPct,
            )
        } else {
            notifyUpdate("正在下载 · ${running.fileName}", text, (running.progress * 100).toInt())
        }
    }

    private fun buildNotification(title: String, text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .build()
    }

    private fun notifyUpdate(title: String, text: String, pct: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, text, pct))
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
    }
}

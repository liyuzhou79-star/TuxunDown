package com.tuxundown.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.DownloadService
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.sniff.SniffResult
import com.tuxundown.android.sniff.SniffedResource
import com.tuxundown.android.sniff.Sniffer
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val sharedUrl = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: ""
        } else ""

        setContent {
            MaterialTheme {
                DownApp(sharedUrl)
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun DownApp(sharedUrl: String) {
    val context = LocalContext.current
    val store = DownloadCenter.store
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick // re-read tasks on every tick

    var wifiOnly by remember { mutableStateOf(DownloadCenter.wifiOnly) }
    var showAdd by remember { mutableStateOf(sharedUrl.isNotBlank()) }
    var urlText by remember { mutableStateOf(sharedUrl) }
    var sniffing by remember { mutableStateOf(false) }
    var sniffList by remember { mutableStateOf<List<SniffedResource>>(emptyList()) }
    var sniffPage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun createTask(url: String, referer: String = "") {
        val t = TaskRecord(url = url)
        if (referer.isNotBlank()) t.headers["Referer"] = referer
        store.add(t)
        DownloadCenter.enqueue(t)
        Toast.makeText(context, "已新建下载任务", Toast.LENGTH_SHORT).show()
    }

    fun openFile(task: TaskRecord) {
        val f: File = store.fileOf(task)
        if (!f.exists()) {
            Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "com.tuxundown.android.files", f)
        val ext = task.fileName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "打开文件")) }
            .onFailure { Toast.makeText(context, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show() }
    }

    fun shareFile(task: TaskRecord) {
        val f: File = store.fileOf(task)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(context, "com.tuxundown.android.files", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件"))
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("TuxunDown", style = MaterialTheme.typography.headlineMedium)
        Text("极速多线程下载器", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("仅 WiFi 下载")
                Text(
                    "开启后流量网络下任务只排队不下载",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = wifiOnly, onCheckedChange = {
                wifiOnly = it
                DownloadCenter.wifiOnly = it
            })
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Text("＋ 新建下载")
        }
        Spacer(Modifier.height(12.dp))
        if (store.tasks.isEmpty()) {
            Text("还没有任务。点「新建下载」，粘贴文件链接或网页链接即可。")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(store.tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onPause = { DownloadCenter.pauseTask(task) },
                    onResume = { DownloadCenter.enqueue(task) },
                    onOpen = { openFile(task) },
                    onShare = { shareFile(task) },
                    onDelete = { store.remove(task) },
                )
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建下载") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("文件链接或网页链接") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sniffing) {
                        Spacer(Modifier.height(8.dp))
                        Text("正在嗅探页面资源…")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = urlText.trim()
                    if (u.isNotBlank()) {
                        createTask(u)
                        showAdd = false
                        urlText = ""
                    }
                }) { Text("直接下载") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val u = urlText.trim()
                        if (u.isNotBlank()) {
                            context.startActivity(
                                Intent(context, SniffActivity::class.java)
                                    .putExtra(SniffActivity.EXTRA_URL, u)
                            )
                            showAdd = false
                        }
                    }) { Text("内置浏览器") }
                    TextButton(onClick = {
                        val u = urlText.trim()
                        if (u.isBlank() || sniffing) return@TextButton
                        sniffing = true
                        message = ""
                        Thread {
                            val result = runCatching { Sniffer().sniff(u) }
                                .getOrElse { SniffResult.Failure(it.message ?: "嗅探失败") }
                            (context as MainActivity).runOnUiThread {
                                sniffing = false
                                when (result) {
                                    is SniffResult.DirectFile -> {
                                        createTask(result.url, u)
                                        showAdd = false
                                        urlText = ""
                                    }
                                    is SniffResult.ResourceList -> {
                                        sniffList = result.resources
                                        sniffPage = result.pageUrl
                                        showAdd = false
                                    }
                                    is SniffResult.Failure -> message = result.reason
                                }
                            }
                        }.start()
                    }) { Text("嗅探网页") }
                    TextButton(onClick = { showAdd = false }) { Text("取消") }
                }
            },
        )
    }

    if (sniffList.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { sniffList = emptyList() },
            title = { Text("页面里的资源（点一个下载）") },
            text = {
                LazyColumn {
                    items(sniffList, key = { it.url }) { res ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    createTask(res.url, sniffPage)
                                    sniffList = emptyList()
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(res.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${res.kind}${if (res.looksLikeDownloadEntry) " · 疑似下载入口" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(res.url, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sniffList = emptyList() }) { Text("关闭") }
            },
        )
    }
}

@androidx.compose.runtime.Composable
fun TaskCard(
    task: TaskRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                task.fileName.ifBlank { task.url },
                style = MaterialTheme.typography.titleMedium,
            )
            val statusText = when (task.status) {
                TaskStatus.QUEUED -> "排队中"
                TaskStatus.RUNNING -> "下载中 · ${task.threads} 线程"
                TaskStatus.PAUSED -> "已暂停"
                TaskStatus.DONE -> "已完成"
                TaskStatus.FAILED -> "失败"
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall)
            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PAUSED) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                val eta = task.etaSeconds
                Text(
                    "${formatSize(task.downloaded)} / ${formatSize(task.totalSize)}" +
                        " · ${DownloadService.formatSpeed(task.speedBps)}" +
                        if (eta > 0) " · 还剩 ${eta} 秒" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (task.status == TaskStatus.DONE) {
                Text(formatSize(task.totalSize), style = MaterialTheme.typography.bodySmall)
            }
            if (task.note.isNotBlank()) {
                Text(task.note, style = MaterialTheme.typography.bodySmall)
            }
            if (task.error.isNotBlank() && task.status == TaskStatus.FAILED) {
                Text("错误：${task.error}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.status) {
                    TaskStatus.RUNNING -> OutlinedButton(onClick = onPause) { Text("暂停") }
                    TaskStatus.PAUSED, TaskStatus.FAILED ->
                        OutlinedButton(onClick = onResume) { Text("继续") }
                    TaskStatus.DONE -> {
                        OutlinedButton(onClick = onOpen) { Text("打开") }
                        OutlinedButton(onClick = onShare) { Text("分享") }
                    }
                    TaskStatus.QUEUED -> OutlinedButton(onClick = onPause) { Text("取消排队") }
                }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

package com.tuxundown.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.DownloadService
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.sniff.LinkRouter
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
    val activity = context as MainActivity
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
    var dirName by remember { mutableStateOf(DownloadCenter.downloadDirName) }
    var showAdd by remember { mutableStateOf(sharedUrl.isNotBlank()) }
    var urlText by remember { mutableStateOf(sharedUrl) }
    var sniffing by remember { mutableStateOf(false) }
    var sniffList by remember { mutableStateOf<List<SniffedResource>>(emptyList()) }
    var sniffTotal by remember { mutableStateOf(0) }
    var sniffPage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            DownloadCenter.downloadDirUri = uri.toString()
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "自定义文件夹"
            DownloadCenter.downloadDirName = name
            dirName = name
            Toast.makeText(context, "下载目录已改为：$name", Toast.LENGTH_SHORT).show()
        }
    }

    fun createTask(url: String, referer: String = "") {
        val t = TaskRecord(url = url)
        if (referer.isNotBlank()) t.headers["Referer"] = referer
        store.add(t)
        DownloadCenter.enqueue(t)
        Toast.makeText(context, "已新建下载任务", Toast.LENGTH_SHORT).show()
    }

    fun handleSniffResult(result: SniffResult, pageUrl: String) {
        when (result) {
            is SniffResult.DirectFile -> {
                createTask(result.url, pageUrl)
                showAdd = false
                urlText = ""
            }
            is SniffResult.ResourceList -> {
                sniffList = result.resources
                sniffTotal = result.totalCount
                sniffPage = result.pageUrl
                showAdd = false
            }
            is SniffResult.Failure -> message = result.reason
        }
    }

    fun startSniff(u: String) {
        sniffing = true
        message = ""
        Thread {
            val result = runCatching { Sniffer().sniff(u) }
                .getOrElse { SniffResult.Failure(it.message ?: "嗅探失败") }
            activity.runOnUiThread {
                sniffing = false
                handleSniffResult(result, u)
            }
        }.start()
    }

    /**
     * Smart entry: route by what the link actually IS, not by which
     * button the user happened to tap. A direct file link (known file
     * extension, or a probe whose headers say "file") starts downloading
     * right away; only real web pages go through the sniffer.
     */
    fun startSmart(raw: String) {
        val u = raw.trim()
        if (u.isBlank() || sniffing) return
        if (LinkRouter.looksLikeFileUrl(u)) {
            createTask(u)
            showAdd = false
            urlText = ""
            return
        }
        sniffing = true
        message = ""
        Thread {
            val route = runCatching { LinkRouter.probe(u) }.getOrNull()
            activity.runOnUiThread {
                if (route == LinkRouter.Route.DIRECT_FILE) {
                    sniffing = false
                    createTask(u)
                    showAdd = false
                    urlText = ""
                } else {
                    // Web page (or probe inconclusive — the sniffer
                    // header-gates too and will say so if it is a file).
                    startSniff(u)
                }
            }
        }.start()
    }

    fun openFile(task: TaskRecord) {
        val name = task.savedName.ifBlank { task.fileName }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (task.savedUri.isNotBlank()) {
            intent.setDataAndType(Uri.parse(task.savedUri), mime)
        } else {
            val f: File = store.fileOf(task)
            if (!f.exists()) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(context, "com.tuxundown.android.files", f)
            intent.setDataAndType(uri, mime)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "打开文件")) }
            .onFailure { Toast.makeText(context, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show() }
    }

    fun shareFile(task: TaskRecord) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (task.savedUri.isNotBlank()) {
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(task.savedUri))
        } else {
            val f: File = store.fileOf(task)
            if (!f.exists()) return
            val uri = FileProvider.getUriForFile(context, "com.tuxundown.android.files", f)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
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
            Column(Modifier.weight(1f)) {
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
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("下载目录")
                Text(
                    if (dirName.isBlank()) "手机「下载/TuxunDown」文件夹"
                    else "自定义：$dirName",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { dirPicker.launch(null) }) { Text("更改") }
            if (dirName.isNotBlank()) {
                OutlinedButton(onClick = {
                    DownloadCenter.downloadDirUri = ""
                    DownloadCenter.downloadDirName = ""
                    dirName = ""
                }) { Text("默认") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Text("＋ 新建下载")
        }
        Spacer(Modifier.height(12.dp))
        if (store.tasks.isEmpty()) {
            Text("还没有任务。点「新建下载」，粘贴文件直链或网页链接即可：直链会直接开下，网页会自动嗅探里面的资源。")
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
            onDismissRequest = { if (!sniffing) showAdd = false },
            title = { Text("新建下载") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("文件直链或网页链接，粘进来就行") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "直链自动直接下载；网页自动嗅探里面的资源。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sniffing) {
                        Spacer(Modifier.height(8.dp))
                        Text("正在识别链接…")
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            val u = urlText.trim()
                            if (u.isNotBlank() && !sniffing) {
                                context.startActivity(
                                    Intent(context, SniffActivity::class.java)
                                        .putExtra(SniffActivity.EXTRA_URL, u)
                                )
                                showAdd = false
                            }
                        }
                    ) { Text("用内置浏览器打开（手动点下载）") }
                }
            },
            confirmButton = {
                TextButton(onClick = { startSmart(urlText) }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { if (!sniffing) showAdd = false }) { Text("取消") }
            },
        )
    }

    if (sniffList.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { sniffList = emptyList() },
            title = {
                Text(
                    if (sniffTotal > sniffList.size)
                        "页面里的资源（共 $sniffTotal 个，先显示 ${sniffList.size} 个）"
                    else "页面里的资源（点一个下载）"
                )
            },
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
    var expanded by remember { mutableStateOf(false) }
    val showChart = task.status == TaskStatus.RUNNING || expanded
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                task.fileName.ifBlank { task.url },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            val statusText = when (task.status) {
                TaskStatus.QUEUED -> "排队中"
                TaskStatus.RUNNING -> "下载中 · ${task.threads} 线程（点文件名看详情）"
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
            if (showChart && task.speedHistory.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // Big current speed, Windows-transfer style.
                Text(
                    DownloadService.formatSpeed(task.speedBps),
                    color = Color(0xFF1F9D2C),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                SpeedChart(
                    history = synchronized(task.speedHistory) { task.speedHistory.toList() },
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
                Text(
                    "速度曲线 · 最近 90 秒",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text("链接：${task.url}", style = MaterialTheme.typography.bodySmall)
                if (task.chunkSize > 0 && task.totalSize > 0) {
                    val total = (task.totalSize + task.chunkSize - 1) / task.chunkSize
                    Text(
                        "分片：已完成 ${task.chunkDone.count { it }}/$total 块 · ${task.threads} 线程",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (task.savedName.isNotBlank()) {
                    Text(
                        "已保存为：${task.savedName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (task.status == TaskStatus.DONE && task.savedName.isBlank()) {
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

/**
 * Speed history chart modelled on the Windows file-transfer graph:
 * light background, thin grid lines, a green line with a translucent
 * green fill under it. History is right-aligned in a 90-second window.
 */
@androidx.compose.runtime.Composable
fun SpeedChart(history: List<Long>, modifier: Modifier = Modifier) {
    val lineColor = Color(0xFF1F9D2C)
    val fillColor = Color(0xFF35B93F)
    val gridColor = Color(0xFFDDDDDD)
    val borderColor = Color(0xFFBDBDBD)
    Canvas(modifier.background(Color(0xFFFCFEFC))) {
        val w = size.width
        val h = size.height
        // Grid: horizontal quarters + verticals every ~15 s of window.
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        for (i in 1..5) {
            val x = w * i / 6f
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        drawRect(borderColor, style = Stroke(width = 1f))
        if (history.size < 2) return@Canvas
        val maxF = (history.maxOrNull() ?: 0).toFloat().coerceAtLeast(1f) * 1.15f
        val stepX = w / 89f
        val startX = w - stepX * (history.size - 1)
        val line = Path()
        history.forEachIndexed { i, v ->
            val x = startX + stepX * i
            val y = h - (v / maxF) * (h - 4f) - 2f
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path()
        fill.addPath(line)
        fill.lineTo(w, h)
        fill.lineTo(startX, h)
        fill.close()
        drawPath(fill, fillColor.copy(alpha = 0.30f))
        drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

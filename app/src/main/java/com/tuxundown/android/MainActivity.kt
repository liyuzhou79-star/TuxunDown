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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.Format
import com.tuxundown.android.core.PublicStorage
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
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(
                        background = Color(0xFF1B1B1B),
                        surface = Color(0xFF242424),
                    )
                } else {
                    lightColorScheme(
                        background = Color(0xFFF7F7F7),
                        surface = Color(0xFFFFFFFF),
                    )
                }
            ) {
                DownApp(sharedUrl)
            }
        }
    }
}

/** Windows file-transfer palette. */
object WinPalette {
    val Green = Color(0xFF06B025)        // Windows transfer green
    val GreenLight = Color(0xFF7BD88A)   // moving highlight on the bar
    val ChartGreen = Color(0xFF1F9D2C)
    val Yellow = Color(0xFFF7B500)       // paused
    val Red = Color(0xFFE81123)          // error
    val TrackLight = Color(0xFFE1E1E1)
    val TrackDark = Color(0xFF3F3F3F)
    val GrayText = Color(0xFF767676)
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

    // ---- v1.3 state: onboarding guide, clipboard prompt, search/sort,
    // speed limit and completion-feedback settings ----
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.3.0"
    }
    var showGuide by remember {
        mutableStateOf(DownloadCenter.guideSeenVersion != appVersion)
    }
    var clipboardLink by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(0) } // 0 默认 1 大小 2 时间
    var limitKBps by remember { mutableStateOf(DownloadCenter.speedLimitKBps) }
    var vibrateOn by remember { mutableStateOf(DownloadCenter.completeVibrate) }
    var soundOn by remember { mutableStateOf(DownloadCenter.completeSound) }

    // Clipboard prompt: if the clipboard holds a link we have not
    // asked about yet, offer a one-tap task when the app opens.
    LaunchedEffect(Unit) {
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
                val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', '，', '。')
                if (!url.isNullOrBlank() && url != DownloadCenter.clipboardPrompted &&
                    store.tasks.none { it.url == url }
                ) {
                    clipboardLink = url
                }
            }
        }
    }

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

    /** Returns false when the same link is already an active task. */
    fun createTask(url: String, referer: String = ""): Boolean {
        // Audit A2 (UI side): the same URL twice would produce two
        // records fighting over one file name — refuse politely.
        val dup = store.tasks.any {
            it.url == url && it.status != TaskStatus.DONE && it.status != TaskStatus.FAILED
        }
        if (dup) {
            Toast.makeText(context, "这个链接已经在任务列表里了", Toast.LENGTH_SHORT).show()
            return false
        }
        val t = TaskRecord(url = url)
        if (referer.isNotBlank()) t.headers["Referer"] = referer
        store.add(t)
        DownloadCenter.enqueue(t)
        Toast.makeText(context, "已新建下载任务", Toast.LENGTH_SHORT).show()
        return true
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
    /**
     * v1.3 batch paste: several links, one per line. Direct files are
     * created immediately; web pages are routed/sniffed one by one in
     * the background — a page that resolves to a single file becomes a
     * task, a page with many resources is counted as skipped (the
     * user can open it separately and pick). Duplicates are skipped.
     */
    fun startBatch(raw: String) {
        val lines = raw.lines().map { it.trim() }.filter { it.startsWith("http") }
        if (lines.isEmpty()) return
        showAdd = false
        urlText = ""
        Toast.makeText(context, "正在批量处理 ${lines.size} 个链接…", Toast.LENGTH_SHORT).show()
        Thread {
            var created = 0
            var skipped = 0
            for (line in lines) {
                val make: String? = when {
                    LinkRouter.looksLikeFileUrl(line) -> line
                    else -> when (val r = runCatching { Sniffer().sniff(line) }.getOrNull()) {
                        is SniffResult.DirectFile -> r.url
                        else -> null
                    }
                }
                if (make == null) {
                    skipped++
                } else {
                    var ok = false
                    val latch = java.util.concurrent.CountDownLatch(1)
                    activity.runOnUiThread {
                        ok = createTask(make)
                        latch.countDown()
                    }
                    latch.await()
                    if (ok) created++ else skipped++
                }
            }
            activity.runOnUiThread {
                Toast.makeText(
                    context,
                    "批量完成：新建 $created 个任务" +
                        if (skipped > 0) "，跳过 $skipped 个（重复或网页资源需单独选）" else "",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    fun startSmart(raw: String) {
        val u = raw.trim()
        if (u.isBlank() || sniffing) return
        // Multi-line paste = batch mode (v1.3).
        if (u.lines().count { it.isNotBlank() } > 1) {
            startBatch(u)
            return
        }
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

    fun deleteTask(task: TaskRecord) {
        // Audit A7: a RUNNING task's engine threads keep writing for
        // a while after pause(). Deleting the .part file immediately
        // raced them (the engine could even "finish" a deleted
        // entry). Now the service owns the cleanup: mark cancelled
        // here; once the engine has fully stopped, the service
        // deletes the file and the record.
        if (task.status == TaskStatus.RUNNING) {
            DownloadCenter.cancelTask(task)
            Toast.makeText(context, "正在停止并删除这个任务…", Toast.LENGTH_SHORT).show()
            return
        }
        // Unfinished tasks: also remove the public .part file.
        // Finished tasks: only the record goes away, the file stays
        // in the Download folder (it belongs to the user now).
        if (task.status != TaskStatus.DONE) {
            PublicStorage.deleteTarget(context, task)
        }
        store.remove(task)
    }

    val activeTasks = store.tasks.filter {
        it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v1.3: home header icon kept deliberately small (36dp) —
            // the user asked twice for the icon NOT to fill its space.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
            Spacer(Modifier.size(10.dp))
            Column {
                Text("TuxunDown", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "极速多线程下载器",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WinPalette.GrayText,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- settings card ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("仅 WiFi 下载")
                        Text(
                            "开启后流量网络下任务只排队不下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = WinPalette.GrayText,
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = {
                        wifiOnly = it
                        DownloadCenter.wifiOnly = it
                    })
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下载目录")
                        Text(
                            if (dirName.isBlank()) "公共「下载」目录（手机下载文件夹第一层）"
                            else "自定义：$dirName",
                            style = MaterialTheme.typography.bodySmall,
                            color = WinPalette.GrayText,
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
                Text(
                    "文件直接保存在公共「下载」目录里，文件管理器第一层就能看到；" +
                        "没下完的 .part 文件也在那里。想换地方，点「更改」选任意文件夹授权一次即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WinPalette.GrayText,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("全局限速（每个任务的最高速度，改了立即生效）")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val options = listOf(
                        0 to "不限", 512 to "512KB", 1024 to "1MB",
                        3072 to "3MB", 5120 to "5MB",
                    )
                    for ((kb, label) in options) {
                        if (kb == limitKBps) {
                            Button(onClick = { }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = {
                                limitKBps = kb
                                DownloadCenter.speedLimitKBps = kb
                            }) { Text(label) }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("下载完成震动")
                    Switch(checked = vibrateOn, onCheckedChange = {
                        vibrateOn = it
                        DownloadCenter.completeVibrate = it
                    })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("下载完成提示音")
                    Switch(checked = soundOn, onCheckedChange = {
                        soundOn = it
                        DownloadCenter.completeSound = it
                    })
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("新手引导")
                        Text(
                            "每个新版本首次打开都会自动播放一次",
                            style = MaterialTheme.typography.bodySmall,
                            color = WinPalette.GrayText,
                        )
                    }
                    OutlinedButton(onClick = { showGuide = true }) { Text("重新查看") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Text("＋ 新建下载")
        }
        Spacer(Modifier.height(12.dp))

        // ---- multi-task summary (Windows multi-file transfer style) ----
        if (activeTasks.size >= 2) {
            SummaryCard(activeTasks)
            Spacer(Modifier.height(12.dp))
        }

        // ---- v1.3: task search + sort ----
        if (store.tasks.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索任务（文件名或链接）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("排序：", style = MaterialTheme.typography.bodySmall, color = WinPalette.GrayText)
                val modes = listOf(0 to "默认", 1 to "大小", 2 to "时间")
                for ((m, label) in modes) {
                    if (m == sortMode) {
                        Button(onClick = { }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { sortMode = m }) { Text(label) }
                    }
                }
            }
        }
        val displayedTasks = store.tasks
            .filter {
                query.isBlank() ||
                    it.fileName.contains(query, true) || it.url.contains(query, true)
            }
            .let { list ->
                when (sortMode) {
                    1 -> list.sortedByDescending { it.totalSize }
                    2 -> list.sortedBy { it.createdAt }
                    else -> list
                }
            }

        if (store.tasks.isEmpty()) {
            Text("还没有任务。点「新建下载」，粘贴文件直链或网页链接即可：直链会直接开下，网页会自动嗅探里面的资源。")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(displayedTasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onPause = { DownloadCenter.pauseTask(task) },
                    onResume = { DownloadCenter.enqueue(task) },
                    onOpen = { openFile(task) },
                    onShare = { shareFile(task) },
                    onDelete = { deleteTask(task) },
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
                        "直链自动直接下载；网页自动嗅探里面的资源。一行一个链接、粘多行可以批量新建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WinPalette.GrayText,
                    )
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = WinPalette.Red,
                        )
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
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                res.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${res.kind}${if (res.looksLikeDownloadEntry) " · 疑似下载入口" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = WinPalette.GrayText,
                            )
                            Text(
                                res.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = WinPalette.GrayText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sniffList = emptyList() }) { Text("关闭") }
            },
        )
    }

    // ---- v1.3: clipboard link prompt ----
    if (clipboardLink.isNotBlank()) {
        AlertDialog(
            onDismissRequest = {
                DownloadCenter.clipboardPrompted = clipboardLink
                clipboardLink = ""
            },
            title = { Text("剪贴板里有链接") },
            text = {
                Text(
                    clipboardLink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = clipboardLink
                    DownloadCenter.clipboardPrompted = u
                    clipboardLink = ""
                    startSmart(u)
                }) { Text("新建下载") }
            },
            dismissButton = {
                TextButton(onClick = {
                    DownloadCenter.clipboardPrompted = clipboardLink
                    clipboardLink = ""
                }) { Text("不用了") }
            },
        )
    }

    // ---- v1.3: per-version onboarding guide ----
    if (showGuide) {
        GuideOverlay(
            version = appVersion,
            onDone = {
                DownloadCenter.guideSeenVersion = appVersion
                showGuide = false
            },
        )
    }
}

/**
 * Full-screen onboarding guide (v1.3, user-requested): shown on the
 * first launch of EVERY new version (the caller compares the stored
 * version), and re-openable from settings. Five pages in the
 * TuxunGo style: welcome / what's new / how it works / speed tips /
 * notes. Content describes this version's real changes and is
 * written for all users, not as a report to one person.
 */
@androidx.compose.runtime.Composable
fun GuideOverlay(version: String, onDone: () -> Unit) {
    val pages = listOf(
        "欢迎使用 TuxunDown" to
            "极速多线程下载器：把一个文件切成很多块、开多条连接同时下，" +
            "在服务器按单条连接限速时，速度可以达到浏览器单线程的数倍乃至十几倍。" +
            "免费开源，没有广告，不用注册账号。",
        "本版更新（v$version）" to
            "· 下载更快：分块更细、断线重试不再重下已下的部分、卡住的连接会被自动换掉\n" +
            "· 修了一批问题：同名文件不再被覆盖、任务记录更抗损坏、WiFi 断开会自动暂停\n" +
            "· 新增：批量粘贴建任务、剪贴板链接提示、任务搜索排序、全局限速、完成震动/提示音",
        "核心用法" to
            "点「新建下载」，把链接粘进来就行：\n" +
            "· 文件直链 → 直接开始多线程下载\n" +
            "· 网页链接 → 自动嗅探出页面里的资源，点一个就下\n" +
            "· 一行一个链接、粘多行 → 批量新建\n" +
            "下好的文件都在手机公共「下载」目录里，文件管理器第一层就能找到。",
        "提速小贴士" to
            "· 优先用 WiFi，并尽量靠近路由器\n" +
            "· 同一个文件浏览器下很慢、这里快很多，是正常现象：浏览器只有一条连接\n" +
            "· 如果加线程后速度不再涨，卡片上会明说——那是服务器按总量限速，谁都绕不过去\n" +
            "· 大文件下到一半可以放心暂停，回来点继续会接着下，不用重来",
        "说明" to
            "· 本软件免费开源（GPLv3），不收集、不上传你的任何数据，所有任务记录只存在你的手机里\n" +
            "· 请只下载你有权下载的内容，并遵守目标网站的规定\n" +
            "· 下载速度取决于你的网络和对方服务器，本软件不能突破你的宽带上限",
    )
    var page by remember { mutableStateOf(0) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                pages[page].first,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                pages[page].second,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    (0 until pages.size).joinToString("  ") { if (it == page) "●" else "○" },
                    color = WinPalette.ChartGreen,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (page > 0) {
                    OutlinedButton(onClick = { page-- }) { Text("上一页") }
                } else {
                    TextButton(onClick = onDone) { Text("跳过") }
                }
                if (page < pages.size - 1) {
                    Button(onClick = { page++ }) { Text("下一页") }
                } else {
                    Button(onClick = onDone) { Text("开始使用") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Aggregate card shown while several tasks are active, like the
 *  Windows multi-file transfer summary. */
@androidx.compose.runtime.Composable
fun SummaryCard(active: List<TaskRecord>) {
    val withSize = active.filter { it.totalSize > 0 }
    val totalSize = withSize.sumOf { it.totalSize }
    val totalDone = withSize.sumOf { it.downloaded }
    val totalProgress = if (totalSize > 0) (totalDone.toFloat() / totalSize).coerceIn(0f, 1f) else 0f
    val totalSpeed = active.filter { it.status == TaskStatus.RUNNING }.sumOf { it.speedBps }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 ${active.size} 个任务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "已完成 ${Format.percent(totalProgress)}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            WinProgressBar(
                progress = totalProgress,
                state = BarState.RUNNING,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "总速度：${Format.speed(totalSpeed)} · ${Format.size(totalDone)} / ${Format.size(totalSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = WinPalette.GrayText,
            )
        }
    }
}

enum class BarState { RUNNING, PAUSED, ERROR, DONE, IDLE }

/**
 * The Windows file-transfer progress bar: bright green fill with a
 * lighter highlight band sweeping across it, on a light gray track.
 * Paused turns yellow, error red — same state colors as Windows.
 */
@androidx.compose.runtime.Composable
fun WinProgressBar(progress: Float, state: BarState, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val track = if (dark) WinPalette.TrackDark else WinPalette.TrackLight
    val fill = when (state) {
        BarState.PAUSED -> WinPalette.Yellow
        BarState.ERROR -> WinPalette.Red
        else -> WinPalette.Green
    }
    val transition = rememberInfiniteTransition(label = "winbar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shine",
    )
    val p = progress.coerceIn(0f, 1f)
    Canvas(
        modifier
            .height(18.dp)
            .clip(RoundedCornerShape(2.dp))
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(track, cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()))
        val fillW = w * p
        if (fillW > 0f) {
            clipRect(left = 0f, top = 0f, right = fillW, bottom = h) {
                drawRect(fill, size = Size(fillW, h))
                if (state == BarState.RUNNING) {
                    // Sweeping lighter band, like the Windows shine.
                    val bandW = 56.dp.toPx()
                    val cx = phase * (fillW + bandW) - bandW / 2
                    drawRect(
                        WinPalette.GreenLight.copy(alpha = 0.55f),
                        topLeft = Offset(cx - bandW / 2, 0f),
                        size = Size(bandW, h),
                    )
                }
                // Subtle top gloss.
                drawRect(
                    Color.White.copy(alpha = 0.18f),
                    size = Size(fillW, h / 2),
                )
            }
        }
    }
}

// ---- small Windows-style glyphs (pause / play / close / check) ----

@androidx.compose.runtime.Composable
fun PauseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val barW = size.width * 0.28f
        val gap = size.width * 0.16f
        val x0 = (size.width - 2 * barW - gap) / 2
        drawRoundRect(
            color,
            topLeft = Offset(x0, 0f),
            size = Size(barW, size.height),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
        )
        drawRoundRect(
            color,
            topLeft = Offset(x0 + barW + gap, 0f),
            size = Size(barW, size.height),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
        )
    }
}

@androidx.compose.runtime.Composable
fun PlayGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.08f)
            lineTo(size.width * 0.88f, size.height * 0.5f)
            lineTo(size.width * 0.22f, size.height * 0.92f)
            close()
        }
        drawPath(path, color)
    }
}

@androidx.compose.runtime.Composable
fun CloseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val sw = 1.8.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = sw)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = sw)
    }
}

@androidx.compose.runtime.Composable
fun CheckGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.55f)
            lineTo(size.width * 0.38f, size.height * 0.85f)
            lineTo(size.width * 0.92f, size.height * 0.18f)
        }
        drawPath(path, color, style = Stroke(width = 2.2.dp.toPx()))
    }
}

@androidx.compose.runtime.Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = WinPalette.GrayText,
            modifier = Modifier.size(width = 76.dp, height = 20.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
    var expanded by remember(task.id) {
        mutableStateOf(DownloadCenter.detailsExpanded)
    }
    val pct = Format.percent(task.progress)
    val barState = when (task.status) {
        TaskStatus.RUNNING -> BarState.RUNNING
        TaskStatus.PAUSED -> BarState.PAUSED
        TaskStatus.FAILED -> BarState.ERROR
        TaskStatus.DONE -> BarState.DONE
        TaskStatus.QUEUED -> BarState.IDLE
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            // ---- header: name + percent, Windows-style buttons top-right ----
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.fileName.ifBlank { task.url },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val headline = when (task.status) {
                        TaskStatus.RUNNING -> "已完成 $pct%"
                        TaskStatus.PAUSED -> "已暂停 · 已完成 $pct%"
                        TaskStatus.QUEUED -> "排队中，等待开始"
                        TaskStatus.DONE -> "已完成"
                        TaskStatus.FAILED -> "下载失败"
                    }
                    Text(
                        headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (task.status) {
                            TaskStatus.FAILED -> WinPalette.Red
                            TaskStatus.DONE -> WinPalette.ChartGreen
                            else -> WinPalette.GrayText
                        },
                    )
                }
                when (task.status) {
                    TaskStatus.RUNNING ->
                        IconButton(onClick = onPause) {
                            PauseGlyph(MaterialTheme.colorScheme.onSurface)
                        }
                    TaskStatus.PAUSED, TaskStatus.FAILED ->
                        IconButton(onClick = onResume) {
                            PlayGlyph(MaterialTheme.colorScheme.onSurface)
                        }
                    TaskStatus.DONE ->
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CheckGlyph(WinPalette.ChartGreen)
                        }
                    TaskStatus.QUEUED -> Spacer(Modifier.size(48.dp))
                }
                IconButton(onClick = onDelete) {
                    CloseGlyph(MaterialTheme.colorScheme.onSurface)
                }
            }

            Spacer(Modifier.height(8.dp))
            WinProgressBar(
                progress = if (task.status == TaskStatus.DONE) 1f else task.progress,
                state = barState,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!expanded) {
                // ---- collapsed ("较少详细信息"): one status line ----
                Spacer(Modifier.height(6.dp))
                val line = when (task.status) {
                    TaskStatus.RUNNING -> {
                        val eta = Format.eta(task.etaSeconds)
                        Format.speed(task.speedBps) +
                            if (eta.isNotBlank()) " · 还剩 $eta" else ""
                    }
                    TaskStatus.PAUSED -> "已下载 ${Format.size(task.downloaded)} / ${Format.size(task.totalSize)}"
                    TaskStatus.QUEUED -> "等待前面的任务完成"
                    TaskStatus.DONE -> Format.size(task.totalSize)
                    TaskStatus.FAILED -> task.error.ifBlank { "下载失败" }
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.status == TaskStatus.FAILED) WinPalette.Red
                    else WinPalette.GrayText,
                )
            } else {
                // ---- expanded ("详细信息"): chart + all info rows ----
                if (task.speedHistory.isNotEmpty() &&
                    (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PAUSED)
                ) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        Format.speed(task.speedBps),
                        color = WinPalette.ChartGreen,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    SpeedChart(
                        history = synchronized(task.speedHistory) { task.speedHistory.toList() },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (task.status) {
                    TaskStatus.RUNNING -> {
                        InfoRow("速度：", Format.speed(task.speedBps))
                        if (task.peakBps > 0) InfoRow("峰值速度：", Format.speed(task.peakBps))
                        val eta = Format.eta(task.etaSeconds)
                        InfoRow("剩余时间：", eta.ifBlank { "计算中…" })
                        InfoRow(
                            "已下载：",
                            "${Format.size(task.downloaded)} / ${Format.size(task.totalSize)}",
                        )
                        if (task.totalSize > 0) {
                            InfoRow("剩余：", Format.size(task.totalSize - task.downloaded))
                        }
                    }
                    TaskStatus.PAUSED -> {
                        InfoRow(
                            "已下载：",
                            "${Format.size(task.downloaded)} / ${Format.size(task.totalSize)}",
                        )
                        if (task.peakBps > 0) InfoRow("峰值速度：", Format.speed(task.peakBps))
                    }
                    TaskStatus.DONE -> {
                        InfoRow("大小：", Format.size(task.totalSize))
                        if (task.savedName.isNotBlank()) {
                            InfoRow("保存位置：", PublicStorage.locationLabel(LocalContext.current))
                            InfoRow("文件名：", task.savedName)
                        }
                    }
                    TaskStatus.FAILED -> {
                        if (task.error.isNotBlank()) {
                            Text(
                                task.error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = WinPalette.Red,
                            )
                        }
                        InfoRow(
                            "已下载：",
                            "${Format.size(task.downloaded)} / ${Format.size(task.totalSize)}",
                        )
                        // v1.3: one-tap retry that KEEPS the progress
                        // already downloaded (chunk bitmap survives).
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onResume) { Text("重试（保留已下载进度）") }
                    }
                    TaskStatus.QUEUED -> {
                        Text(
                            "等待前面的任务完成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WinPalette.GrayText,
                        )
                    }
                }
                if (task.chunkSize > 0 && task.totalSize > 0 && task.status != TaskStatus.DONE) {
                    val total = (task.totalSize + task.chunkSize - 1) / task.chunkSize
                    InfoRow(
                        "分片：",
                        "已完成 ${task.chunkDone.count { it }}/$total 块 · ${task.threads} 线程",
                    )
                }
                if (task.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        task.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = WinPalette.GrayText,
                    )
                }
                if (task.status == TaskStatus.DONE) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpen) { Text("打开") }
                        OutlinedButton(onClick = onShare) { Text("分享") }
                    }
                }
            }

            // ---- Windows-style details toggle ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    expanded = !expanded
                    DownloadCenter.detailsExpanded = expanded
                }) {
                    Text(if (expanded) "较少详细信息 ▴" else "详细信息 ▾")
                }
            }
        }
    }
}

/**
 * Speed history chart modelled on the Windows file-transfer /
 * Task Manager graph: plain background, thin grid lines, a green
 * line with a translucent green fill under it, the Y axis scaled
 * to a clean ceiling whose value is labelled at the top left, and
 * the time window labelled along the bottom. History is
 * right-aligned in a 90-second window.
 */
@androidx.compose.runtime.Composable
fun SpeedChart(history: List<Long>, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val lineColor = WinPalette.ChartGreen
    val gridColor = if (dark) Color(0xFF3A3A3A) else Color(0xFFDDDDDD)
    val borderColor = if (dark) Color(0xFF4A4A4A) else Color(0xFFBDBDBD)
    val bg = if (dark) Color(0xFF202020) else Color(0xFFFCFEFC)
    val ceiling = Format.chartCeiling(history.maxOrNull() ?: 0)
    val topLabel = Format.speed(ceiling) + " 峰值刻度"
    val labelColor = if (dark) 0xFFAAAAAA.toInt() else 0xFF767676.toInt()
    Canvas(modifier.background(bg)) {
        val w = size.width
        val h = size.height
        val labelH = 14.dp.toPx()
        val bottomH = 12.dp.toPx()
        val plotTop = labelH
        val plotH = h - labelH - bottomH
        // Grid: horizontal quarters + verticals every ~15 s of window.
        for (i in 0..4) {
            val y = plotTop + plotH * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        for (i in 1..5) {
            val x = w * i / 6f
            drawLine(gridColor, Offset(x, plotTop), Offset(x, plotTop + plotH), strokeWidth = 1f)
        }
        drawRect(borderColor, topLeft = Offset(0f, plotTop), size = Size(w, plotH), style = Stroke(width = 1f))
        // Axis labels via the native canvas.
        val paint = android.graphics.Paint().apply {
            color = labelColor
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(topLabel, 4.dp.toPx(), 11.dp.toPx(), paint)
        drawContext.canvas.nativeCanvas.drawText("90 秒前", 4.dp.toPx(), h - 2.dp.toPx(), paint)
        val nowText = "现在"
        val nowW = paint.measureText(nowText)
        drawContext.canvas.nativeCanvas.drawText(nowText, w - nowW - 4.dp.toPx(), h - 2.dp.toPx(), paint)
        if (history.size < 2) return@Canvas
        val maxF = ceiling.toFloat().coerceAtLeast(1f)
        val stepX = w / 89f
        val startX = w - stepX * (history.size - 1)
        val line = Path()
        history.forEachIndexed { i, v ->
            val x = startX + stepX * i
            val y = plotTop + plotH - (v / maxF) * (plotH - 4f) - 2f
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path()
        fill.addPath(line)
        fill.lineTo(w, plotTop + plotH)
        fill.lineTo(startX, plotTop + plotH)
        fill.close()
        drawPath(fill, lineColor.copy(alpha = 0.25f))
        drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

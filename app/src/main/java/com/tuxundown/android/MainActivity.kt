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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.tuxundown.android.core.Backup
import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.ChecksumVerifier
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.DownloadedFiles
import com.tuxundown.android.core.Format
import com.tuxundown.android.core.FtpClient
import com.tuxundown.android.core.ProxySettings
import com.tuxundown.android.core.PublicStorage
import com.tuxundown.android.core.ScheduleTime
import com.tuxundown.android.core.SpeedTest
import com.tuxundown.android.core.TaskHeaders
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.UserAgents
import com.tuxundown.android.sniff.LinkRouter
import com.tuxundown.android.sniff.SizeProbe
import com.tuxundown.android.sniff.SniffFilter
import com.tuxundown.android.sniff.SniffResult
import com.tuxundown.android.sniff.SniffSort
import com.tuxundown.android.sniff.SniffedResource
import com.tuxundown.android.sniff.Sniffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** v1.9 audit: upper bound for a backup file read during import. */
private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            // v1.5 startup audit: a permission-request hiccup on an
            // exotic ROM must never cost the launch itself.
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (intent?.action == Intent.ACTION_SEND) {
            SharedLinkState.text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: ""
        }
        AmoledState.enabled = DownloadCenter.amoledBlack

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = when {
                    // v1.7: AMOLED pure black — background and cards
                    // go fully black on OLED screens (dark mode only).
                    dark && AmoledState.enabled -> darkColorScheme(
                        background = Color.Black,
                        surface = Color.Black,
                        surfaceVariant = Color(0xFF1C1C1C),
                    )
                    dark -> darkColorScheme(
                        background = Color(0xFF1B1B1B),
                        surface = Color(0xFF242424),
                    )
                    else -> lightColorScheme(
                        background = Color(0xFFF7F7F7),
                        surface = Color(0xFFFFFFFF),
                    )
                }
            ) {
                DownApp()
            }
        }
    }

    /**
     * v1.9 audit: the activity is singleTop (manifest), so sharing
     * a link while the app is already open lands HERE instead of
     * creating a second MainActivity instance with its own copy of
     * the UI state. DownApp observes [SharedLinkState] and opens
     * the add dialog with the link filled in.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            SharedLinkState.text =
                intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: ""
        }
    }
}

/**
 * v1.9 audit: a link shared into the app (first launch or while
 * already running), as snapshot state so both paths reach the UI
 * the same way. DownApp consumes it (and clears it) on sight.
 */
object SharedLinkState {
    var text by mutableStateOf("")
}

/**
 * v1.9: the last sniff result, held in process memory so that
 * closing the result dialog does NOT throw it away — the home
 * screen offers a "上次嗅探结果" entry that reopens it (a fresh
 * sniff would cost the page fetch and the size probe all over
 * again, and the page might have changed since). Process death
 * still clears it; that is accepted and stated in the guide.
 */
object SniffSession {
    val listState = mutableStateOf<List<SniffedResource>>(emptyList())
    val pageState = mutableStateOf("")
    val totalState = mutableStateOf(0)
    val openState = mutableStateOf(false)
}

/**
 * v1.7: AMOLED pure-black switch as snapshot state, so flipping it
 * in settings re-themes the app immediately (the persisted value
 * lives in DownloadCenter.amoledBlack; this is its UI mirror).
 */
object AmoledState {
    var enabled by mutableStateOf(false)
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

/** File-type badge colors (v1.4 item 8), readable in both themes. */
fun kindColor(kind: String): Color = when (kind) {
    "apk" -> Color(0xFF2E9E4F)
    "archive" -> Color(0xFFB26A00)
    "video" -> Color(0xFF7B4DBA)
    "audio" -> Color(0xFFC2437A)
    "image" -> Color(0xFF1F8A8C)
    "document" -> Color(0xFF3367B5)
    else -> Color(0xFF6B7280)
}

fun kindOfName(name: String): String =
    com.tuxundown.android.sniff.SniffParser.kindOf("https://x/" + name) ?: "other"

@androidx.compose.runtime.Composable
fun DownApp() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val store = DownloadCenter.store
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick // re-read tasks on every tick

    // ---- navigation (v1.4: home / settings are separate screens) ----
    var screen by remember { mutableStateOf("home") }

    // ---- settings state (mirrors DownloadCenter prefs) ----
    var wifiOnly by remember { mutableStateOf(DownloadCenter.wifiOnly) }
    var dirName by remember { mutableStateOf(DownloadCenter.downloadDirName) }
    var limitKBps by remember { mutableStateOf(DownloadCenter.speedLimitKBps) }
    var vibrateOn by remember { mutableStateOf(DownloadCenter.completeVibrate) }
    var soundOn by remember { mutableStateOf(DownloadCenter.completeSound) }
    var maxConc by remember { mutableStateOf(DownloadCenter.maxConcurrent) }
    var autoRetry by remember { mutableStateOf(DownloadCenter.autoRetryCount) }
    var trafficMB by remember { mutableStateOf(DownloadCenter.trafficThresholdMB) }
    var dualChan by remember { mutableStateOf(DownloadCenter.dualChannel) }
    var userAgentUi by remember { mutableStateOf(DownloadCenter.userAgent) }
    var amoledUi by remember { mutableStateOf(DownloadCenter.amoledBlack) }
    // ---- v1.9: proxy mirrors (DownloadCenter owns persistence) ----
    var proxyOn by remember { mutableStateOf(DownloadCenter.proxyEnabled) }
    var proxyTypeUi by remember { mutableStateOf(DownloadCenter.proxyType) }
    var proxyHostUi by remember { mutableStateOf(DownloadCenter.proxyHost) }
    var proxyPortUi by remember {
        mutableStateOf(
            if (DownloadCenter.proxyPort > 0) DownloadCenter.proxyPort.toString() else ""
        )
    }
    var proxyUserUi by remember { mutableStateOf(DownloadCenter.proxyUser) }
    var proxyPassUi by remember { mutableStateOf(DownloadCenter.proxyPass) }

    // ---- v1.5: battery-optimisation whitelist status for the
    // settings keep-alive card. Refreshed every time the settings
    // screen is entered (the user may have just changed it). ----
    var batteryOk by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        if (screen == "settings") {
            batteryOk = runCatching {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE)
                    as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)
        }
    }

    // ---- add-task dialog state ----
    var showAdd by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    // v1.9 audit: consume a link shared into the app (onCreate
    // seeds the same state for the first launch, onNewIntent for
    // shares that arrive while the app is already running).
    val incomingLink = SharedLinkState.text
    LaunchedEffect(incomingLink) {
        if (incomingLink.isNotBlank()) {
            urlText = incomingLink
            showAdd = true
            SharedLinkState.text = ""
        }
    }
    var nameText by remember { mutableStateOf("") }
    var mirrorsText by remember { mutableStateOf("") }
    var scheduleMin by remember { mutableStateOf(0) }
    // v1.8: schedule mode 0 = 一次性（立即/延时）, 1 = 每天定时.
    var scheduleMode by remember { mutableStateOf(0) }
    var dailyTimeText by remember { mutableStateOf("23:30") }
    var pendingScheduleAt by remember { mutableStateOf(0L) }
    var sniffing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    // ---- sniff result dialog state (v1.4: multi-select + filter + sort) ----
    // v1.9: the list/page/total live in SniffSession (process
    // memory) so closing the dialog keeps the session — the home
    // screen can reopen it without sniffing again.
    var sniffList by SniffSession.listState
    var sniffTotal by SniffSession.totalState
    var sniffPage by SniffSession.pageState
    var sniffOpen by SniffSession.openState
    var sniffChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sniffKind by remember { mutableStateOf("") } // "" = all kinds
    var sniffSort by remember { mutableStateOf(0) } // 0 默认 1 名称 2 类型 3 大小
    var sniffProbing by remember { mutableStateOf(false) } // v1.6 size probe phase
    var sniffShowNoise by remember { mutableStateOf(false) } // v1.9 noise folding

    // ---- mirror-sources dialog (v1.6 item 4) ----
    var sourcesTask by remember { mutableStateOf<TaskRecord?>(null) }
    var sourceText by remember { mutableStateOf("") }

    // ---- checksum dialog (v1.7) ----
    var hashTask by remember { mutableStateOf<TaskRecord?>(null) }
    var hashText by remember { mutableStateOf("") }

    // ---- request-header editor dialog (v1.9) ----
    var headersTask by remember { mutableStateOf<TaskRecord?>(null) }
    var headersText by remember { mutableStateOf("") }
    var headersError by remember { mutableStateOf("") }

    // ---- home list state (v1.4: tabs, search, sort, multi-select) ----
    var tab by remember { mutableStateOf(0) } // 0 全部 1 下载中 2 已完成 3 失败
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(0) } // 0 默认 1 大小 2 时间
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ---- rename dialog (v1.4 item 18) ----
    var renameTask by remember { mutableStateOf<TaskRecord?>(null) }
    var renameText by remember { mutableStateOf("") }

    // ---- v1.8: swipe-right delete asks first (the X button keeps
    // its old immediate behaviour; a swipe is easier to trigger by
    // accident, so it earns a confirmation). ----
    var swipeDeleteTask by remember { mutableStateOf<TaskRecord?>(null) }

    // ---- v1.3 state: onboarding guide, clipboard prompt ----
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.9.0"
    }
    var showGuide by remember {
        mutableStateOf(DownloadCenter.guideSeenVersion != appVersion)
    }
    var clipboardLink by remember { mutableStateOf("") }

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

    // ---- v1.8: ONE backup entry point — settings + tasks in a
    // single JSON file (this replaces the v1.4 tasks-only export;
    // old v1.4 files still import, see Backup.parse). The file
    // never contains downloaded bytes or the download directory. ----
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val json = Backup.build(
                store.exportJson(),
                DownloadCenter.settingsSnapshotJson(),
                System.currentTimeMillis(),
            )
            Thread {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("open failed")
                }.isSuccess
                activity.runOnUiThread {
                    Toast.makeText(
                        context,
                        if (ok) "备份已导出（设置＋任务）" else "导出失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.start()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Thread {
                // v1.9 audit: the read is BOUNDED. The old code did
                // readBytes() on whatever file the picker returned —
                // a "backup" that is really a multi-hundred-MB file
                // would be loaded whole into memory (OOM) before
                // any parsing happened. A real backup is kilobytes
                // to a few MB; 32 MB is generous headroom.
                var tooBig = false
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BACKUP_BYTES) {
                                tooBig = true
                                break
                            }
                        }
                        if (tooBig) null else out.toByteArray().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                activity.runOnUiThread {
                    if (text == null) {
                        Toast.makeText(
                            context,
                            if (tooBig) {
                                "导入失败：文件太大了（超过 32 MB），不像正常的备份文件"
                            } else {
                                "导入失败：读不到文件"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        val parsed = Backup.parse(text)
                        if (parsed.tasksJson == null && parsed.settings == null) {
                            Toast.makeText(
                                context,
                                "导入失败：这个文件不是 TuxunDown 的备份",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@runOnUiThread
                        }
                        var msg = ""
                        if (parsed.tasksJson != null) {
                            val n = store.importJson(parsed.tasksJson)
                            msg += "恢复 $n 个任务（重复的跳过了）"
                        }
                        if (parsed.settings != null) {
                            DownloadCenter.applySettingsJson(parsed.settings)
                            // Refresh the settings mirrors so the
                            // settings page shows the restored values.
                            wifiOnly = DownloadCenter.wifiOnly
                            limitKBps = DownloadCenter.speedLimitKBps
                            vibrateOn = DownloadCenter.completeVibrate
                            soundOn = DownloadCenter.completeSound
                            maxConc = DownloadCenter.maxConcurrent
                            autoRetry = DownloadCenter.autoRetryCount
                            trafficMB = DownloadCenter.trafficThresholdMB
                            dualChan = DownloadCenter.dualChannel
                            userAgentUi = DownloadCenter.userAgent
                            amoledUi = DownloadCenter.amoledBlack
                            AmoledState.enabled = DownloadCenter.amoledBlack
                            proxyOn = DownloadCenter.proxyEnabled
                            proxyTypeUi = DownloadCenter.proxyType
                            proxyHostUi = DownloadCenter.proxyHost
                            proxyPortUi = if (DownloadCenter.proxyPort > 0) {
                                DownloadCenter.proxyPort.toString()
                            } else ""
                            proxyUserUi = DownloadCenter.proxyUser
                            // The proxy password is NOT in backups
                            // (by design); proxyPassUi keeps whatever
                            // is currently stored on this device.
                            msg += if (msg.isEmpty()) "设置已恢复" else "，设置也已恢复"
                        }
                        Toast.makeText(
                            context,
                            if (msg.isEmpty()) "备份里没有可恢复的内容" else "导入完成：$msg",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }.start()
        }
    }

    /** Returns false when the same link is already an active task. */
    fun createTask(
        url: String,
        referer: String = "",
        fileName: String = "",
        mirrors: List<String> = emptyList(),
        daily: Boolean = false,
    ): Boolean {
        // Audit A2 (UI side): the same URL twice would produce two
        // records fighting over one file name — refuse politely.
        val dup = store.tasks.any {
            it.url == url && it.status != TaskStatus.DONE && it.status != TaskStatus.FAILED
        }
        if (dup) {
            Toast.makeText(context, "这个链接已经在任务列表里了", Toast.LENGTH_SHORT).show()
            return false
        }
        // v1.6: newTask flags .m3u8 links as HLS stream tasks.
        val t = DownloadCenter.newTask(url)
        if (referer.isNotBlank()) t.headers["Referer"] = referer
        if (fileName.isNotBlank()) t.fileName = fileName.trim()
        // v1.6 multi-source: mirrors of the SAME file (validated by
        // the engine before a single byte is taken from them).
        t.sources = mirrors.map { it.trim() }
            .filter { it.startsWith("http") && it != url }
            .distinct().toMutableList()
        if (daily && pendingScheduleAt > 0) {
            // v1.8: daily recurrence — the service re-queues the
            // task for the same time every day after each run.
            t.scheduleDaily = true
            t.scheduledAt = pendingScheduleAt
            t.note = "每天定时下载：第一次在 ${Format.clockTime(pendingScheduleAt)} 开始，" +
                "之后每天这个时间自动再下一次。"
        } else if (pendingScheduleAt > System.currentTimeMillis()) {
            t.scheduledAt = pendingScheduleAt
            t.note = "预约下载：将在 ${Format.clockTime(pendingScheduleAt)} 开始。"
        }
        store.add(t)
        DownloadCenter.enqueue(t)
        Toast.makeText(
            context,
            when {
                t.scheduleDaily -> "已设每天定时，${Format.clockTime(t.scheduledAt)} 开始"
                t.scheduledAt > 0 -> "已预约，${Format.clockTime(t.scheduledAt)} 自动开始"
                else -> "已新建下载任务"
            },
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    fun handleSniffResult(result: SniffResult, pageUrl: String) {
        when (result) {
            is SniffResult.DirectFile -> {
                createTask(result.url, pageUrl, daily = scheduleMode == 1)
                showAdd = false
                urlText = ""
            }
            is SniffResult.ResourceList -> {
                DownloadCenter.rememberSniffedPage(result.pageUrl)
                sniffList = result.resources
                sniffTotal = result.totalCount
                sniffPage = result.pageUrl
                sniffOpen = true
                sniffShowNoise = false
                sniffChecked = result.resources
                    .filter { it.looksLikeDownloadEntry }
                    .map { it.url }.toSet()
                sniffKind = ""
                sniffSort = 0
                showAdd = false
                // v1.6: second phase — measure sizes in the
                // background (limited, see SizeProbe) and swap the
                // list in when they land; unknown stays unknown.
                sniffProbing = true
                Thread {
                    val sized = runCatching { Sniffer().probeSizes(result.resources) }
                        .getOrDefault(result.resources)
                    activity.runOnUiThread {
                        sniffProbing = false
                        if (sniffList.isNotEmpty()) sniffList = sized
                    }
                }.start()
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
     * v1.3 batch paste: several links, one per line. Direct files are
     * created immediately; web pages are routed/sniffed one by one in
     * the background — a page that resolves to a single file becomes a
     * task, a page with many resources is counted as skipped.
     */
    fun startBatch(raw: String) {
        // v1.7: ftp:// lines are batch-eligible too (direct tasks).
        val lines = raw.lines().map { it.trim() }
            .filter { it.startsWith("http") || FtpClient.isFtpUrl(it) }
        if (lines.isEmpty()) return
        // v1.8: batch creation is always immediate — a daily slot
        // computed for a single task must not leak into the batch.
        if (scheduleMode == 1) pendingScheduleAt = 0L
        showAdd = false
        urlText = ""
        Toast.makeText(context, "正在批量处理 ${lines.size} 个链接…", Toast.LENGTH_SHORT).show()
        Thread {
            var created = 0
            var skipped = 0
            for (line in lines) {
                val make: String? = when {
                    FtpClient.isFtpUrl(line) -> line
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
        if (scheduleMode == 1) {
            // v1.8: daily mode — validate the wall-clock time and
            // resolve it to the next occurrence.
            val hm = ScheduleTime.parseHhMm(dailyTimeText)
            if (hm == null) {
                message = "每天定时的时间格式不对，填 23:30 这样的格式（24 小时制）。"
                return
            }
            pendingScheduleAt = ScheduleTime.nextOccurrence(
                hm.first, hm.second, System.currentTimeMillis()
            )
        } else {
            pendingScheduleAt = if (scheduleMin > 0) {
                System.currentTimeMillis() + scheduleMin * 60_000L
            } else 0L
        }
        // Multi-line paste = batch mode (v1.3).
        if (u.lines().count { it.isNotBlank() } > 1) {
            startBatch(u)
            return
        }
        val mirrors = mirrorsText.lines()
        // v1.7: FTP links are always direct file tasks — no HTTP
        // probe, no sniffing; the engine's FTP path handles them.
        if (FtpClient.isFtpUrl(u)) {
            createTask(u, fileName = nameText, daily = scheduleMode == 1)
            showAdd = false
            urlText = ""
            nameText = ""
            mirrorsText = ""
            return
        }
        if (LinkRouter.looksLikeFileUrl(u)) {
            createTask(u, fileName = nameText, mirrors = mirrors, daily = scheduleMode == 1)
            showAdd = false
            urlText = ""
            nameText = ""
            mirrorsText = ""
            return
        }
        sniffing = true
        message = ""
        Thread {
            val route = runCatching { LinkRouter.probe(u) }.getOrNull()
            activity.runOnUiThread {
                if (route == LinkRouter.Route.DIRECT_FILE) {
                    sniffing = false
                    createTask(u, fileName = nameText, mirrors = mirrors, daily = scheduleMode == 1)
                    showAdd = false
                    urlText = ""
                    nameText = ""
                    mirrorsText = ""
                } else {
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

    /** v1.4 item 9: one-tap install for a finished APK. */
    fun installApk(task: TaskRecord) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (task.savedUri.isNotBlank()) {
            intent.setDataAndType(
                Uri.parse(task.savedUri),
                "application/vnd.android.package-archive",
            )
        } else {
            val f: File = store.fileOf(task)
            if (!f.exists()) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(context, "com.tuxundown.android.files", f)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "调起安装失败，可以去下载目录手动点这个文件安装", Toast.LENGTH_LONG).show()
            }
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
        // a while after pause(). The service owns the cleanup now:
        // mark cancelled here; the service deletes the file and the
        // record once the engine has fully stopped.
        if (task.status == TaskStatus.RUNNING) {
            DownloadCenter.cancelTask(task)
            Toast.makeText(context, "正在停止并删除这个任务…", Toast.LENGTH_SHORT).show()
            return
        }
        // Unfinished tasks: also remove the public .part file.
        // Finished tasks: only the record goes away, the file stays.
        if (task.status != TaskStatus.DONE) {
            PublicStorage.deleteTarget(context, task)
        }
        store.remove(task)
    }

    // One card wiring, reused by the narrow list and the wide
    // two-column rows (v1.6 item 7).
    val TaskCardFor: @androidx.compose.runtime.Composable (TaskRecord) -> Unit = { task ->
        TaskCard(
            task = task,
            selecting = selecting,
            selected = task.id in selectedIds,
            onToggleSelect = {
                selecting = true
                selectedIds = if (task.id in selectedIds) {
                    selectedIds - task.id
                } else {
                    selectedIds + task.id
                }
            },
            onPause = { DownloadCenter.pauseTask(task) },
            onResume = { DownloadCenter.enqueue(task) },
            onOpen = { openFile(task) },
            onInstall = { installApk(task) },
            onShare = { shareFile(task) },
            onDelete = { deleteTask(task) },
            onSwipeDelete = { swipeDeleteTask = task },
            onJumpQueue = {
                if (DownloadCenter.moveToFrontOfQueue(task)) {
                    Toast.makeText(context, "已插队到队列最前", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "它已经是队列里第一个了", Toast.LENGTH_SHORT).show()
                }
            },
            onRename = {
                renameTask = task
                renameText = task.fileName
            },
            onSources = {
                sourcesTask = task
                sourceText = task.sources.joinToString("\n")
            },
            onChecksum = {
                hashTask = task
                hashText = task.expectedSha256.ifBlank { task.expectedMd5 }
            },
            onHeaders = {
                headersTask = task
                headersText = TaskHeaders.toText(task.headers)
                headersError = ""
            },
        )
    }

    // v1.6 item 7: wide screens get two task columns (read here, in
    // composable scope — the LazyColumn content lambda is not one).
    val wideScreen =
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600

    val activeTasks = store.tasks.filter {
        it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
    }

    val displayedTasks = store.tasks
        .filter {
            when (tab) {
                1 -> it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED ||
                    it.status == TaskStatus.PAUSED
                2 -> it.status == TaskStatus.DONE
                3 -> it.status == TaskStatus.FAILED
                else -> true
            }
        }
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

    if (screen == "settings") {
        SettingsScreen(
            version = appVersion,
            wifiOnly = wifiOnly,
            onWifiOnly = { wifiOnly = it; DownloadCenter.wifiOnly = it },
            dirName = dirName,
            onPickDir = { dirPicker.launch(null) },
            onResetDir = {
                DownloadCenter.downloadDirUri = ""
                DownloadCenter.downloadDirName = ""
                dirName = ""
            },
            limitKBps = limitKBps,
            onLimit = { limitKBps = it; DownloadCenter.speedLimitKBps = it },
            maxConc = maxConc,
            onMaxConc = { maxConc = it; DownloadCenter.maxConcurrent = it },
            autoRetry = autoRetry,
            onAutoRetry = { autoRetry = it; DownloadCenter.autoRetryCount = it },
            trafficMB = trafficMB,
            onTraffic = { trafficMB = it; DownloadCenter.trafficThresholdMB = it },
            dualChan = dualChan,
            onDualChan = { dualChan = it; DownloadCenter.dualChannel = it },
            userAgent = userAgentUi,
            onUserAgent = { userAgentUi = it; DownloadCenter.userAgent = it },
            amoled = amoledUi,
            onAmoled = {
                amoledUi = it
                DownloadCenter.amoledBlack = it
                AmoledState.enabled = it
            },
            proxyOn = proxyOn,
            onProxyOn = { proxyOn = it; DownloadCenter.proxyEnabled = it },
            proxyType = proxyTypeUi,
            onProxyType = { proxyTypeUi = it; DownloadCenter.proxyType = it },
            proxyHost = proxyHostUi,
            onProxyHost = { proxyHostUi = it; DownloadCenter.proxyHost = it.trim() },
            proxyPortText = proxyPortUi,
            onProxyPortText = { v ->
                val digits = v.filter { it.isDigit() }.take(5)
                proxyPortUi = digits
                DownloadCenter.proxyPort = digits.toIntOrNull() ?: 0
            },
            proxyUser = proxyUserUi,
            onProxyUser = { proxyUserUi = it; DownloadCenter.proxyUser = it },
            proxyPass = proxyPassUi,
            onProxyPass = { proxyPassUi = it; DownloadCenter.proxyPass = it },
            vibrateOn = vibrateOn,
            onVibrate = { vibrateOn = it; DownloadCenter.completeVibrate = it },
            soundOn = soundOn,
            onSound = { soundOn = it; DownloadCenter.completeSound = it },
            batteryOk = batteryOk,
            onOpenBattery = {
                // v1.5: ONLY guide the user to the system switch —
                // the app never touches battery settings itself.
                val direct = runCatching {
                    context.startActivity(
                        Intent(
                            android.provider.Settings
                                .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + context.packageName),
                        )
                    )
                }.isSuccess
                if (!direct) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings
                                    .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                            )
                        )
                    }
                    Toast.makeText(
                        context,
                        "在列表里找到 TuxunDown，把电池设为不限制",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onShowGuide = { showGuide = true },
            onExport = { exportLauncher.launch("tuxundown-backup.json") },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            onBack = { screen = "home" },
        )
    } else if (screen == "stats") {
        StatsScreen(
            onBack = { screen = "home" },
            onSpeedTest = { screen = "speedtest" },
        )
    } else if (screen == "files") {
        FilesScreen(onBack = { screen = "home" })
    } else if (screen == "speedtest") {
        SpeedTestScreen(onBack = { screen = "stats" })
    } else {
        // ================= HOME — ONE scrolling LazyColumn (v1.4 fix) =================
        // Root cause of "主页不能向下滑动": the home used to be a plain
        // non-scrollable Column; the header/settings/button/search were
        // laid out at their full height and the LazyColumn only got the
        // leftover strip at the bottom, so most of the page could never
        // be scrolled to. Now EVERYTHING (header, summary, tabs, search
        // and the task cards) lives in a single LazyColumn — the whole
        // page scrolls as one, and the task list virtualizes as before.
        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Home header icon kept deliberately small (36dp).
                    // NOTE: never painterResource(R.mipmap.ic_launcher)
                    // here — on API 26+ that resolves to the adaptive-icon
                    // XML and crashes the first frame (v1.3.0 hotfix).
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp)),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TuxunDown", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "极速多线程下载器",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { screen = "stats" }) { Text("统计") }
                    Spacer(Modifier.size(6.dp))
                    OutlinedButton(onClick = { screen = "settings" }) { Text("设置") }
                }
            }

            item(key = "newbtn") {
                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("＋ 新建下载")
                }
            }

            item(key = "filesbtn") {
                OutlinedButton(
                    onClick = { screen = "files" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("已下载文件") }
            }

            // v1.9: reopen the retained sniff session (closing
            // the result dialog no longer discards it).
            if (sniffList.isNotEmpty() && !sniffOpen) {
                item(key = "sniffagain") {
                    OutlinedButton(
                        onClick = { sniffOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("上次嗅探结果（${sniffList.size} 个资源，点开继续选）") }
                }
            }

            if (activeTasks.size >= 2) {
                item(key = "summary") { SummaryCard(activeTasks) }
            }

            // ---- tabs (v1.4 item 2) ----
            item(key = "tabs") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val tabs = listOf(0 to "全部", 1 to "下载中", 2 to "已完成", 3 to "失败")
                    for ((m, label) in tabs) {
                        if (m == tab) {
                            Button(onClick = { }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { tab = m }) { Text(label) }
                        }
                    }
                }
            }

            // ---- search + sort ----
            if (store.tasks.isNotEmpty()) {
                item(key = "search") {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索任务（文件名或链接）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "排序：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                }
            }

            // ---- multi-select action bar (v1.4 item 3) ----
            if (selecting) {
                item(key = "selbar") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "已选 ${selectedIds.size} 个任务（长按任务卡可多选）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(onClick = {
                                    selectedIds = displayedTasks.map { it.id }.toSet()
                                }) { Text("全选") }
                                OutlinedButton(onClick = {
                                    store.tasks.filter { it.id in selectedIds }.forEach {
                                        if (it.status == TaskStatus.RUNNING ||
                                            it.status == TaskStatus.QUEUED
                                        ) DownloadCenter.pauseTask(it)
                                    }
                                }) { Text("暂停") }
                                OutlinedButton(onClick = {
                                    store.tasks.filter { it.id in selectedIds }.forEach {
                                        if (it.status == TaskStatus.PAUSED ||
                                            it.status == TaskStatus.FAILED
                                        ) DownloadCenter.enqueue(it)
                                    }
                                }) { Text("继续/重试") }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(onClick = {
                                    store.tasks.filter {
                                        it.id in selectedIds && it.status == TaskStatus.QUEUED
                                    }.forEach { DownloadCenter.moveToFrontOfQueue(it) }
                                    Toast.makeText(context, "已把选中的排队任务提到最前", Toast.LENGTH_SHORT).show()
                                }) { Text("插队") }
                                OutlinedButton(onClick = {
                                    val victims = store.tasks.filter { it.id in selectedIds }
                                    victims.forEach { deleteTask(it) }
                                    selectedIds = emptySet()
                                    selecting = false
                                }) { Text("删除") }
                                TextButton(onClick = {
                                    selectedIds = emptySet()
                                    selecting = false
                                }) { Text("退出多选") }
                            }
                        }
                    }
                }
            }

            // ---- empty state (v1.4 item 14) ----
            if (store.tasks.isEmpty()) {
                item(key = "empty") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Column(
                            Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.app_icon),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "还没有下载任务",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "把文件直链粘进来就会直接多线程开下；把网页链接粘进来，" +
                                    "会自动嗅探出页面里的安装包、压缩包、视频等资源，点一个就下。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { showAdd = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("粘贴链接，新建下载") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showAdd = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("粘贴网页，嗅探里面的资源") }
                        }
                    }
                }
            } else if (displayedTasks.isEmpty()) {
                item(key = "nomatch") {
                    Text("这个分类下没有任务。")
                }
            }

            // v1.6 item 7: on wide screens (landscape / tablet /
            // unfolded foldable) the tasks flow in TWO columns
            // inside the same single scrolling LazyColumn — cards
            // keep their natural height, nothing is stretched.
            if (wideScreen) {
                items(displayedTasks.chunked(2), key = { "row-" + it.first().id }) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (t in pair) {
                            Box(Modifier.weight(1f)) { TaskCardFor(t) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(displayedTasks, key = { it.id }) { task ->
                    TaskCardFor(task)
                }
            }
        }
    }

    // ================= dialogs =================

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
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("保存文件名（可不填，留空自动识别）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = mirrorsText,
                        onValueChange = { mirrorsText = it },
                        label = { Text("镜像地址（可不填，一行一个，必须是同一个文件）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "预约开始：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // v1.8: one-shot (existing) or daily recurrence.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (scheduleMode == 0) {
                            Button(onClick = { }) { Text("一次性") }
                        } else {
                            OutlinedButton(onClick = { scheduleMode = 0 }) { Text("一次性") }
                        }
                        if (scheduleMode == 1) {
                            Button(onClick = { }) { Text("每天定时") }
                        } else {
                            OutlinedButton(onClick = { scheduleMode = 1 }) { Text("每天定时") }
                        }
                    }
                    if (scheduleMode == 0) {
                        Row(
                            Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val opts = listOf(0 to "立即", 30 to "30分钟后", 60 to "1小时后", 180 to "3小时后")
                            for ((m, label) in opts) {
                                if (m == scheduleMin) {
                                    Button(onClick = { }) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { scheduleMin = m }) { Text(label) }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = dailyTimeText,
                            onValueChange = { dailyTimeText = it },
                            label = { Text("每天几点开始（如 23:30）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        Text(
                            "到点自动下载；下完（或失败）后自动排到第二天同一时间，" +
                                "每次都是重新下载一份新文件。暂停后不会自动恢复，要继续在卡片上点继续。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "直链自动直接下载；网页自动嗅探里面的资源。一行一个链接、粘多行可以批量新建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // ---- sniff history (v1.4 item 16) ----
                    val history = DownloadCenter.sniffHistory
                    if (history.isNotEmpty() && urlText.isBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "最近嗅过的网页（点一个重新嗅探）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (h in history.take(5)) {
                            Text(
                                h,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { urlText = h }
                                    .padding(vertical = 4.dp),
                            )
                        }
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
                    TextButton(
                        onClick = {
                            showAdd = false
                            context.startActivity(
                                Intent(context, ScanActivity::class.java)
                            )
                        }
                    ) { Text("扫二维码添加链接") }
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

    if (sniffOpen && sniffList.isNotEmpty()) {
        val kindsPresent = sniffList.map { it.kind }.distinct()
        // v1.9: duplicate groups (same name + same measured size)
        // and the noise fold (analytics/ads/tiny images).
        val dupCounts = SniffFilter.duplicateCounts(sniffList)
        val noiseCount = sniffList.count { SniffFilter.isNoise(it) }
        val shownSniff = sniffList
            .filter { sniffKind.isBlank() || it.kind == sniffKind }
            .filter { sniffShowNoise || !SniffFilter.isNoise(it) }
            .let { list ->
                when (sniffSort) {
                    1 -> list.sortedBy { it.name.lowercase() }
                    2 -> list.sortedBy { it.kind }
                    3 -> SniffSort.bySizeDesc(list)
                    else -> list
                }
            }
        AlertDialog(
            onDismissRequest = { sniffOpen = false },
            title = {
                Text(
                    if (sniffTotal > sniffList.size)
                        "页面里的资源（共 $sniffTotal 个，先显示 ${sniffList.size} 个）"
                    else "页面里的资源（勾选后下载）"
                )
            },
            text = {
                Column {
                    // ---- filter + sort (v1.4 item 15) ----
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sniffKind.isBlank()) {
                            Button(onClick = { }) { Text("全部") }
                        } else {
                            OutlinedButton(onClick = { sniffKind = "" }) { Text("全部") }
                        }
                        for (k in kindsPresent) {
                            if (k == sniffKind) {
                                Button(onClick = { }) { Text(kindLabel(k)) }
                            } else {
                                OutlinedButton(onClick = { sniffKind = k }) { Text(kindLabel(k)) }
                            }
                        }
                    }
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "排序：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val modes = listOf(0 to "默认", 1 to "名称", 2 to "类型", 3 to "大小")
                        for ((m, label) in modes) {
                            if (m == sniffSort) {
                                Button(onClick = { }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { sniffSort = m }) { Text(label) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            val all = shownSniff.map { it.url }.toSet()
                            sniffChecked = if (all.all { it in sniffChecked }) {
                                sniffChecked - all
                            } else {
                                sniffChecked + all
                            }
                        }) { Text("全选/取消") }
                    }
                    if (dupCounts.isNotEmpty()) {
                        Text(
                            "有 ${dupCounts.size} 个资源和别的资源同名同大小，" +
                                "可能是同一个文件被页面列了两次，勾选时留意别重复下载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (noiseCount > 0) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (sniffShowNoise) {
                                    "噪音资源已展开（统计 / 广告 / 小图片）"
                                } else {
                                    "已折叠 $noiseCount 个疑似噪音资源（统计 / 广告 / 小图片）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { sniffShowNoise = !sniffShowNoise }) {
                                Text(if (sniffShowNoise) "收起" else "展开")
                            }
                        }
                    }
                    if (sniffProbing) {
                        Text(
                            "正在测每个文件的大小（最多测前 ${SizeProbe.MAX_PROBED} 个，测不到的会写大小未知）…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shownSniff, key = { it.url }) { res ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        sniffChecked = if (res.url in sniffChecked) {
                                            sniffChecked - res.url
                                        } else {
                                            sniffChecked + res.url
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = res.url in sniffChecked,
                                    onCheckedChange = { checked ->
                                        sniffChecked = if (checked) {
                                            sniffChecked + res.url
                                        } else {
                                            sniffChecked - res.url
                                        }
                                    },
                                )
                                FileTypeBadge(kind = res.kind, name = res.name)
                                Spacer(Modifier.size(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        res.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${kindLabel(res.kind)}" +
                                            (if (res.looksLikeDownloadEntry) " · 疑似下载入口" else "") +
                                            (if (res.size >= 0) " · ${Format.size(res.size)}"
                                            else " · 大小未知") +
                                            (dupCounts[res.url]?.let {
                                                " · 可能重复（共 $it 个同名同大小）"
                                            } ?: "") +
                                            (if (SniffFilter.isNoise(res)) " · 疑似噪音" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        res.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chosen = sniffList.filter { it.url in sniffChecked }
                        // v1.9: closing keeps the session — the
                        // home screen can reopen this same list.
                        sniffOpen = false
                        var created = 0
                        for (res in chosen) {
                            if (createTask(res.url, sniffPage)) created++
                        }
                        if (chosen.isEmpty()) {
                            Toast.makeText(context, "先勾选要下载的资源", Toast.LENGTH_SHORT).show()
                        } else if (created > 0) {
                            Toast.makeText(context, "已新建 $created 个下载任务", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("下载所选（${sniffChecked.size}）") }
            },
            dismissButton = {
                TextButton(onClick = { sniffOpen = false }) { Text("关闭") }
            },
        )
    }

    // ---- rename dialog (v1.4 item 18) ----
    if (renameTask != null) {
        AlertDialog(
            onDismissRequest = { renameTask = null },
            title = { Text("修改保存文件名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("文件名（带扩展名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameTask
                    val newName = renameText.trim()
                    if (t != null && newName.isNotBlank()) {
                        t.fileName = newName
                        store.save()
                        Toast.makeText(context, "已改名为：$newName", Toast.LENGTH_SHORT).show()
                    }
                    renameTask = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTask = null }) { Text("取消") }
            },
        )
    }

    // ---- v1.8: swipe-delete confirmation ----
    if (swipeDeleteTask != null) {
        val dt = swipeDeleteTask!!
        AlertDialog(
            onDismissRequest = { swipeDeleteTask = null },
            title = { Text("删除这个任务？") },
            text = {
                Text(
                    when (dt.status) {
                        TaskStatus.RUNNING ->
                            "它正在下载：会先停下再删除，下载目录里没下完的半成品文件也会一起删掉。"
                        TaskStatus.DONE ->
                            "只删任务记录；已经下好的文件会保留在下载目录里，不受影响。"
                        else ->
                            "任务记录和没下完的半成品文件都会一起删掉，删了不能恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    swipeDeleteTask = null
                    selectedIds = selectedIds - dt.id
                    deleteTask(dt)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { swipeDeleteTask = null }) { Text("取消") }
            },
        )
    }

    // ---- mirror sources dialog (v1.6 item 4) ----
    if (sourcesTask != null) {
        val st = sourcesTask!!
        AlertDialog(
            onDismissRequest = { sourcesTask = null },
            title = { Text("镜像源（多源并下）") },
            text = {
                Column {
                    Text(
                        "主地址：${st.url}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sourceText,
                        onValueChange = { sourceText = it },
                        label = { Text("镜像地址，一行一个") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "所有地址必须是同一个文件：下载前会逐个校验大小和内容抽样，" +
                            "对不上的会被拒绝并在卡片上说明。某个源中途挂了，它的分片会由其他源接管。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (st.status == TaskStatus.RUNNING) {
                        Text(
                            "任务正在下载：改动会在下次继续时生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    st.sources = sourceText.lines().map { it.trim() }
                        .filter { it.startsWith("http") && it != st.url }
                        .distinct().toMutableList()
                    store.save()
                    Toast.makeText(
                        context,
                        if (st.sources.isEmpty()) "已清空镜像源" else "已保存 ${st.sources.size} 个镜像源",
                        Toast.LENGTH_SHORT,
                    ).show()
                    sourcesTask = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { sourcesTask = null }) { Text("取消") }
            },
        )
    }

    // ---- checksum hash dialog (v1.7) ----
    if (hashTask != null) {
        val ht = hashTask!!
        val detected = Checksum.detectKind(hashText)
        AlertDialog(
            onDismissRequest = { hashTask = null },
            title = { Text("设置校验哈希") },
            text = {
                Column {
                    Text(
                        "把发布页给的哈希值粘进来：64 位自动认成 SHA-256，32 位认成 MD5。" +
                            "下载完成时会自动算一遍对不对得上，卡片上会写明结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hashText,
                        onValueChange = { hashText = it },
                        label = { Text("SHA-256 或 MD5（十六进制）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            hashText.isBlank() -> "未填写"
                            detected != null -> "识别为：$detected ✓"
                            else -> "没认出来：长度不对或含有非十六进制字符"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (detected != null) WinPalette.ChartGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kind = Checksum.detectKind(hashText)
                    if (kind == null) {
                        Toast.makeText(
                            context,
                            "没认出来：要填 32 位（MD5）或 64 位（SHA-256）的十六进制哈希",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@TextButton
                    }
                    val hex = Checksum.normalize(hashText)
                    if (kind == Checksum.SHA256) {
                        ht.expectedSha256 = hex
                        ht.expectedMd5 = ""
                    } else {
                        ht.expectedMd5 = hex
                        ht.expectedSha256 = ""
                    }
                    ht.checksumState = Checksum.STATE_NONE
                    store.saveNow()
                    hashTask = null
                    if (ht.status == TaskStatus.DONE) {
                        // Hash arrived after the download: verify the
                        // finished file right away, off the UI thread.
                        Toast.makeText(context, "开始校验…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val verdict = runCatching {
                                ChecksumVerifier.verify(context, ht)
                            }.getOrDefault("")
                            activity.runOnUiThread {
                                store.saveNow()
                                if (verdict.isNotBlank()) {
                                    Toast.makeText(context, verdict, Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    } else {
                        Toast.makeText(
                            context,
                            "已保存，下完会自动校验",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (ht.expectedSha256.isNotBlank() || ht.expectedMd5.isNotBlank()) {
                        TextButton(onClick = {
                            ht.expectedSha256 = ""
                            ht.expectedMd5 = ""
                            ht.checksumState = Checksum.STATE_NONE
                            store.saveNow()
                            hashTask = null
                        }) { Text("清除") }
                    }
                    TextButton(onClick = { hashTask = null }) { Text("取消") }
                }
            },
        )
    }

    // ---- request-header editor dialog (v1.9) ----
    if (headersTask != null) {
        val ht2 = headersTask!!
        AlertDialog(
            onDismissRequest = { headersTask = null },
            title = { Text("请求头") },
            text = {
                Column {
                    Text(
                        "这个任务每次连接服务器时带上的请求头，一行一个，格式是「名字: 值」。" +
                            "改完保存后，之后新建的连接就会用新的（暂停再继续最稳妥）。" +
                            "Cookie 是登录凭证，只存在这台手机里，导出备份时会被剔除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("如 Cookie: session=…（可留空清空）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                    )
                    if (headersError.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            headersError,
                            style = MaterialTheme.typography.bodySmall,
                            color = WinPalette.Red,
                        )
                    }
                    if (ht2.status == TaskStatus.RUNNING) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "任务正在下载：已经连上的连接不会变，新开的连接会用新请求头。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = TaskHeaders.parse(headersText)
                    if (parsed.problems.isNotEmpty()) {
                        headersError = parsed.problems.joinToString("\n")
                    } else {
                        ht2.headers.clear()
                        ht2.headers.putAll(parsed.headers)
                        store.save()
                        headersTask = null
                        Toast.makeText(
                            context,
                            if (parsed.headers.isEmpty()) "请求头已清空"
                            else "已保存 ${parsed.headers.size} 个请求头",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { headersTask = null }) { Text("取消") }
            },
        )
    }

    // ---- clipboard link prompt (v1.3) ----
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
                    urlText = u
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

    // ---- per-version onboarding guide ----
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

fun kindLabel(kind: String): String = when (kind) {
    "apk" -> "安装包"
    "archive" -> "压缩包"
    "video" -> "视频"
    "audio" -> "音频"
    "image" -> "图片"
    "document" -> "文档"
    else -> "其他"
}

/** Small colored file-type badge (v1.4 item 8). */
@androidx.compose.runtime.Composable
fun FileTypeBadge(kind: String, name: String) {
    val ext = name.substringAfterLast('.', "").uppercase()
    val label = when {
        ext.isNotBlank() && ext.length <= 4 && !ext.contains('/') -> ext
        kind == "apk" -> "APK"
        kind == "archive" -> "ZIP"
        kind == "video" -> "视频"
        kind == "audio" -> "音频"
        kind == "image" -> "图片"
        kind == "document" -> "文档"
        else -> "文件"
    }
    Box(
        Modifier
            .size(width = 40.dp, height = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(kindColor(kind)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * Statistics screen (v1.6 item 6): local download totals — 累计 /
 * 今日 / 完成数 / 平均速度. Everything is counted on-device from
 * completed tasks; nothing is uploaded anywhere.
 */
@androidx.compose.runtime.Composable
fun StatsScreen(onBack: () -> Unit, onSpeedTest: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") refresh // re-read counters after a clear
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "st-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Text(
                    "下载统计",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item(key = "st-main") {
            SettingsGroup("总览（从本版开始统计）") {
                InfoRow("累计下载：", Format.size(DownloadCenter.statsTotalBytes()))
                InfoRow("今日下载：", Format.size(DownloadCenter.statsTodayBytes()))
                InfoRow("完成任务：", "${DownloadCenter.statsDoneCount()} 个")
                val avg = DownloadCenter.statsAvgBps()
                InfoRow(
                    "平均速度：",
                    if (avg > 0) Format.speed(avg) else "还没有完成的任务",
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    DownloadCenter.clearStats()
                    refresh++
                }) { Text("清除统计") }
                Text(
                    "统计只存在这台手机里，不上传、不分享；清除后从零开始重新计。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item(key = "st-test") {
            SettingsGroup("网络测速") {
                Text(
                    "用下载引擎同款方式，测这台手机现在的真实下载速度：" +
                        "先单条连接测一遍，再 4 条连接一起测一遍，对比着看。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onSpeedTest) { Text("开始测速") }
            }
        }
    }
}

/**
 * Built-in speed test screen (v1.8): single connection vs four
 * parallel connections against a public test endpoint. The copy
 * is explicit that this measures the speed TO THE TEST SERVER —
 * any given file's server can be slower or faster.
 */
/**
 * Downloaded-files manager (v1.9): every finished file in the
 * current download location — task records first (authoritative),
 * plus a directory scan for files whose record was deleted
 * (deleting a record keeps the file, by design). Open / share /
 * delete; deleting the FILE and deleting the RECORD are two
 * separate actions, each confirmed on its own.
 */
@androidx.compose.runtime.Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<DownloadedFiles.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(DownloadedFiles.SORT_TIME) }
    var reloadKey by remember { mutableStateOf(0) }
    var deleteEntry by remember { mutableStateOf<DownloadedFiles.Entry?>(null) }

    LaunchedEffect(reloadKey) {
        loading = true
        Thread {
            val fromRecords = DownloadCenter.store.tasks
                .filter { it.status == TaskStatus.DONE && it.savedUri.isNotBlank() }
                .map { t ->
                    DownloadedFiles.Entry(
                        name = t.savedName.ifBlank { t.fileName },
                        uri = t.savedUri,
                        size = if (t.totalSize > 0) t.totalSize else t.downloaded,
                        modifiedAt = t.finishedAt,
                        taskId = t.id,
                    )
                }
            val fromScan = runCatching { PublicStorage.scanDownloadedFiles(context) }
                .getOrDefault(emptyList())
                .map {
                    DownloadedFiles.Entry(
                        it.name, it.uri.toString(), it.size, it.modifiedAt, ""
                    )
                }
            val merged = DownloadedFiles.merge(fromRecords, fromScan)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                entries = merged
                loading = false
            }
        }.start()
    }

    fun openEntry(e: DownloadedFiles.Entry) {
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(Uri.parse(e.uri), mime)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "打开文件")) }
            .onFailure {
                Toast.makeText(context, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show()
            }
    }

    fun shareEntry(e: DownloadedFiles.Entry) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri))
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "分享文件")) }
    }

    val shown = DownloadedFiles.sort(entries, sortMode)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "f-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Text(
                    "已下载文件",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item(key = "f-summary") {
            Column {
                Text(
                    when {
                        loading -> "正在扫描下载目录…"
                        entries.isEmpty() -> "还没有下好的文件"
                        else -> "共 ${entries.size} 个文件 · 合计占用 " +
                            Format.size(DownloadedFiles.totalSize(entries))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "位置：${PublicStorage.locationLabel(context)}" +
                        "（含以前删过任务记录、只剩下文件本体的）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "排序：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val modes = listOf(
                        DownloadedFiles.SORT_TIME to "时间",
                        DownloadedFiles.SORT_SIZE to "大小",
                    )
                    for ((m, label) in modes) {
                        if (m == sortMode) {
                            Button(onClick = { }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { sortMode = m }) { Text(label) }
                        }
                    }
                }
            }
        }
        if (!loading && entries.isEmpty()) {
            item(key = "f-empty") {
                Text("下好的文件会出现在这里，可以打开、分享、删除。")
            }
        }
        items(shown, key = { it.uri }) { e ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FileTypeBadge(kind = kindOfName(e.name), name = e.name)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Format.size(e.size) +
                                    (if (e.modifiedAt > 0) {
                                        " · " + Format.dateTime(e.modifiedAt)
                                    } else "") +
                                    (if (e.taskId.isBlank()) " · 仅剩文件（记录已删）" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { openEntry(e) }) { Text("打开") }
                        OutlinedButton(onClick = { shareEntry(e) }) { Text("分享") }
                        OutlinedButton(onClick = { deleteEntry = e }) { Text("删除") }
                    }
                }
            }
        }
    }

    // Delete: FILE and RECORD are separate choices (v1.9) — the
    // dialog spells out what each one does before anything goes.
    if (deleteEntry != null) {
        val e = deleteEntry!!
        val task = if (e.taskId.isNotBlank()) {
            DownloadCenter.store.byId(e.taskId)
        } else null
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("删除") },
            text = {
                Text(
                    "「${e.name}」（${Format.size(e.size)}）\n" +
                        if (task != null) {
                            "· 只删记录：任务记录去掉，文件保留在下载目录里，之后在本页显示为「仅剩文件」。\n" +
                                "· 删文件：文件本体删掉，任务记录也一起清掉，不能恢复。"
                        } else {
                            "这个文件已经没有任务记录了，删除就是把文件本体删掉，不能恢复。"
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteEntry = null
                    Thread {
                        val ok = PublicStorage.deleteByUri(context, e.uri)
                        if (task != null) DownloadCenter.store.remove(task)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(
                                context,
                                if (ok) "文件已删除" else "删除失败，系统没有允许",
                                Toast.LENGTH_SHORT,
                            ).show()
                            reloadKey++
                        }
                    }.start()
                }) { Text(if (task != null) "删文件（连记录）" else "删除文件") }
            },
            dismissButton = {
                Row {
                    if (task != null) {
                        TextButton(onClick = {
                            DownloadCenter.store.remove(task)
                            deleteEntry = null
                            reloadKey++
                            Toast.makeText(
                                context, "记录已删除，文件保留", Toast.LENGTH_SHORT
                            ).show()
                        }) { Text("只删记录") }
                    }
                    TextButton(onClick = { deleteEntry = null }) { Text("取消") }
                }
            },
        )
    }
}

@androidx.compose.runtime.Composable
fun SpeedTestScreen(onBack: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var endpointLabel by remember { mutableStateOf("") }
    var liveBps by remember { mutableStateOf(0L) }
    var singleBps by remember { mutableStateOf(-1L) }
    var multiBps by remember { mutableStateOf(-1L) }
    var testRef by remember { mutableStateOf<SpeedTest?>(null) }
    // Leaving the screen stops the test (it would keep burning
    // data in the background otherwise).
    DisposableEffect(Unit) {
        onDispose { testRef?.cancel() }
    }
    fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
    fun startTest() {
        if (running) return
        running = true
        phase = "准备测速…"
        endpointLabel = ""
        liveBps = 0
        singleBps = -1
        multiBps = -1
        val test = SpeedTest()
        testRef = test
        Thread {
            var finished = false
            for (ep in SpeedTest.ENDPOINTS) {
                if (test.cancelled) break
                post {
                    endpointLabel = ep.label
                    phase = "单线程测速中（1 条连接）…"
                    liveBps = 0
                }
                val s = test.runPhase(ep.url, 1, SpeedTest.PHASE_MS) { b ->
                    post { liveBps = b }
                }
                if (test.cancelled) break
                if (s <= 0) continue // this endpoint moved nothing: try the next
                post {
                    singleBps = s
                    phase = "多线程测速中（4 条连接）…"
                    liveBps = 0
                }
                val m = test.runPhase(ep.url, 4, SpeedTest.PHASE_MS) { b ->
                    post { liveBps = b }
                }
                if (!test.cancelled) {
                    post { multiBps = m }
                    finished = true
                }
                break
            }
            post {
                running = false
                phase = when {
                    test.cancelled -> "已停止"
                    finished -> "测速完成"
                    else -> "测速失败：测速点都连不上，检查一下网络再试"
                }
            }
        }.start()
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "sp-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Text(
                    "网络测速",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item(key = "sp-main") {
            SettingsGroup("单线程 vs 多线程") {
                Text(
                    "测的是这台手机到测速服务器的速度（当前网络下）。" +
                        "下载某个具体文件能跑多快，还要看那个文件的服务器给不给力——" +
                        "这里的数字是网络本身的上限参考，不是承诺。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (endpointLabel.isNotBlank()) {
                    Text(
                        "测速点：$endpointLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (phase.isNotBlank()) {
                    Text(
                        phase,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (running) {
                    Text(
                        Format.speed(liveBps),
                        color = WinPalette.ChartGreen,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (singleBps >= 0) {
                    InfoRow("单线程：", Format.speed(singleBps))
                }
                if (multiBps >= 0) {
                    InfoRow("多线程（4 条）：", Format.speed(multiBps))
                }
                if (singleBps > 0 && multiBps > 0) {
                    val ratio = multiBps.toDouble() / singleBps.toDouble()
                    InfoRow(
                        "对比：",
                        if (ratio >= 1.05) {
                            "多线程约为单线程的 ${"%.1f".format(ratio)} 倍——" +
                                "连接数加上去确实能提速，这正是本 App 多线程下载的原理"
                        } else {
                            "多线程没比单线程快多少——当前网络或测速服务器是按总量给速度的，" +
                                "这种情况下加连接帮不上忙（下载真实文件时同理）"
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        OutlinedButton(onClick = { testRef?.cancel() }) { Text("停止") }
                    } else {
                        Button(onClick = { startTest() }) {
                            Text(if (singleBps >= 0) "再测一次" else "开始测速")
                        }
                    }
                }
                Text(
                    "一次完整测速约 12 秒，大约消耗 10–100 MB 流量（网速越快用得越多），建议在 WiFi 下测。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Settings screen (v1.4 item 20): grouped sections on their own
 * scrolling page — 下载 / 提示 / 引导与数据 / 关于 — instead of one
 * giant card squeezed onto the home page.
 */
@androidx.compose.runtime.Composable
fun SettingsScreen(
    version: String,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    dirName: String,
    onPickDir: () -> Unit,
    onResetDir: () -> Unit,
    limitKBps: Int,
    onLimit: (Int) -> Unit,
    maxConc: Int,
    onMaxConc: (Int) -> Unit,
    autoRetry: Int,
    onAutoRetry: (Int) -> Unit,
    trafficMB: Int,
    onTraffic: (Int) -> Unit,
    dualChan: Boolean,
    onDualChan: (Boolean) -> Unit,
    userAgent: String,
    onUserAgent: (String) -> Unit,
    amoled: Boolean,
    onAmoled: (Boolean) -> Unit,
    proxyOn: Boolean,
    onProxyOn: (Boolean) -> Unit,
    proxyType: Int,
    onProxyType: (Int) -> Unit,
    proxyHost: String,
    onProxyHost: (String) -> Unit,
    proxyPortText: String,
    onProxyPortText: (String) -> Unit,
    proxyUser: String,
    onProxyUser: (String) -> Unit,
    proxyPass: String,
    onProxyPass: (String) -> Unit,
    vibrateOn: Boolean,
    onVibrate: (Boolean) -> Unit,
    soundOn: Boolean,
    onSound: (Boolean) -> Unit,
    batteryOk: Boolean,
    onOpenBattery: () -> Unit,
    onShowGuide: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // v1.7: custom-UA text field + orphan .part scan state.
    var uaCustom by remember(userAgent) { mutableStateOf(userAgent) }
    var orphanScan by remember { mutableStateOf<List<PublicStorage.OrphanPart>?>(null) }
    var orphanScanning by remember { mutableStateOf(false) }
    fun startOrphanScan() {
        orphanScanning = true
        Thread {
            val found = runCatching { PublicStorage.scanOrphanParts(context) }
                .getOrDefault(emptyList())
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                orphanScanning = false
                orphanScan = found
            }
        }.start()
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "s-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Text(
                    "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item(key = "s-download") {
            SettingsGroup("下载") {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = onWifiOnly)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("同时下载任务数")
                ChipRow(
                    options = (1..5).map { it to "${it} 个" },
                    selected = maxConc,
                    onPick = onMaxConc,
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("WiFi＋流量双通道")
                        Text(
                            "开启后向系统申请流量网络，和 WiFi 一起下载同一个文件；" +
                                "当前环境拿不到流量网络时，任务卡片上会明说用不了，不会假装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = dualChan, onCheckedChange = onDualChan)
                }
                Text(
                    "注意：开双通道等于你同意用流量下载，流量保护不会再拦；" +
                        "和「仅 WiFi 下载」同时开时，以「仅 WiFi」为准（双通道不启用）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("全局限速（所有任务合计的上限，多任务时按优先级分配，改了立即生效）")
                ChipRow(
                    options = listOf(
                        0 to "不限", 512 to "512KB", 1024 to "1MB",
                        3072 to "3MB", 5120 to "5MB",
                    ),
                    selected = limitKBps,
                    onPick = onLimit,
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("流量保护（流量网络下，文件超过这个大小先暂停问你）")
                ChipRow(
                    options = listOf(0 to "关", 50 to "50MB", 200 to "200MB", 1024 to "1GB"),
                    selected = trafficMB,
                    onPick = onTraffic,
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("下载失败自动重试（等一会儿再试，间隔越来越长）")
                ChipRow(
                    options = listOf(0 to "关", 1 to "1 次", 3 to "3 次", 5 to "5 次"),
                    selected = autoRetry,
                    onPick = onAutoRetry,
                )
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onPickDir) { Text("更改") }
                    if (dirName.isNotBlank()) {
                        OutlinedButton(onClick = onResetDir) { Text("默认") }
                    }
                }
                Text(
                    "文件直接保存在公共「下载」目录里，文件管理器第一层就能看到；" +
                        "没下完的 .part 文件也在那里。想换地方，点「更改」选任意文件夹授权一次即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("User-Agent（服务器看到的浏览器标识）")
                Text(
                    "有些服务器会按这个标识给不同的内容。选预置或自己填；" +
                        "从嗅探、内置浏览器抓到的任务用的是抓到时那个标识，不受这里影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (row in UserAgents.PRESETS.chunked(2)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((label, value) in row) {
                            if (userAgent == value) {
                                Button(
                                    onClick = { },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            } else {
                                OutlinedButton(
                                    onClick = { onUserAgent(value); uaCustom = value },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = uaCustom,
                    onValueChange = { uaCustom = it },
                    label = { Text("自定义 User-Agent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedButton(
                    onClick = { onUserAgent(uaCustom.trim()) },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("用这个自定义 UA") }
            }
        }
        item(key = "s-proxy") {
            SettingsGroup("代理") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下载代理")
                        Text(
                            "开启后，下载和嗅探的网络请求都走这个代理；默认关",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = proxyOn, onCheckedChange = onProxyOn)
                }
                if (proxyOn) {
                    Text(
                        "代理类型",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    ChipRow(
                        options = listOf(
                            ProxySettings.TYPE_HTTP to "HTTP",
                            ProxySettings.TYPE_SOCKS to "SOCKS5",
                        ),
                        selected = proxyType,
                        onPick = onProxyType,
                    )
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = onProxyHost,
                        label = { Text("主机地址（如 127.0.0.1）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = proxyPortText,
                        onValueChange = onProxyPortText,
                        label = { Text("端口（如 7890）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = proxyUser,
                        onValueChange = onProxyUser,
                        label = { Text("用户名（可不填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = proxyPass,
                        onValueChange = onProxyPass,
                        label = { Text("密码（可不填）") },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    val problem = ProxySettings.validate(true, proxyHost, proxyPortText)
                    if (problem != null) {
                        Text(
                            problem,
                            style = MaterialTheme.typography.bodySmall,
                            color = WinPalette.Red,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Text(
                    "代理连不上时，任务会停下来并写明原因，绝不会悄悄改走直连。" +
                        "两个例外请注意：FTP 链接不走代理（协议限制）；内置浏览器打开网页本身不走代理" +
                        "（系统限制），但它抓到链接后建的下载任务走代理。" +
                        "代理密码只存在这台手机的设置里，不会进导出备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item(key = "s-look") {
            SettingsGroup("外观") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AMOLED 纯黑")
                        Text(
                            "深色模式下背景和卡片变成纯黑，OLED 屏幕更省电、晚上看不刺眼",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = amoled, onCheckedChange = onAmoled)
                }
            }
        }
        item(key = "s-notify") {
            SettingsGroup("提示") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("下载完成震动")
                    Switch(checked = vibrateOn, onCheckedChange = onVibrate)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("下载完成提示音")
                    Switch(checked = soundOn, onCheckedChange = onSound)
                }
            }
        }
        item(key = "s-power") {
            SettingsGroup("后台运行") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("后台保活（电池白名单）")
                        Text(
                            if (batteryOk) {
                                "已加入白名单：锁屏后系统不会冻住下载，后台下大文件更稳。"
                            } else {
                                "还没加入白名单：锁屏久了，系统可能把下载冻住、甚至杀掉。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!batteryOk) {
                        OutlinedButton(onClick = onOpenBattery) { Text("去设置") }
                    }
                }
                Text(
                    "点「去设置」会打开系统的电池优化页面，把 TuxunDown 设为「不限制」即可，" +
                        "只能由你亲手开，App 不会也不能替你改系统设置。\n" +
                        "魅族 Flyme 也可以这样找：设置 → 应用管理 → TuxunDown → 电池 → 选「不限制」；" +
                        "再在最近任务界面长按 TuxunDown 的卡片，点「锁定」，双保险。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item(key = "s-data") {
            SettingsGroup("引导与数据") {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onShowGuide) { Text("重新查看") }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport) { Text("导出全部备份") }
                    OutlinedButton(onClick = onImport) { Text("导入备份") }
                }
                Text(
                    "一个 JSON 文件装下全部设置＋任务记录（不含文件本体），换手机或重装后导入一次全恢复。" +
                        "下载目录不在备份里（文件夹授权没法跨设备，导入后在设置里重选一次）。" +
                        "旧版导出的任务列表文件也能导入，只是没有设置部分。" +
                        "敏感信息不进备份：每个任务的 Cookie 和代理密码都会被剔除，" +
                        "导入后需要登录态的任务要重新从内置浏览器抓一次链接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("清理孤儿 .part 文件")
                        Text(
                            "下载目录里没有任务认领的半成品（删任务、异常退出留下的），" +
                                "扫出来确认后删除，腾出空间",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { startOrphanScan() },
                        enabled = !orphanScanning,
                    ) { Text(if (orphanScanning) "扫描中…" else "扫描") }
                }
            }
        }
        item(key = "s-about") {
            SettingsGroup("关于") {
                Text("TuxunDown v$version")
                Text(
                    "极速多线程下载器 · 免费开源（GPLv3）· 无广告 · 不收集、不上传任何数据，" +
                        "所有任务记录只存在你的手机里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/liyuzhou79-star/TuxunDown/releases"),
                            )
                        )
                    }
                }) { Text("检查更新（打开 GitHub 发布页）") }
            }
        }
    }
    // v1.7: orphan .part scan results — list with sizes, delete on confirm.
    if (orphanScan != null) {
        val list = orphanScan!!
        AlertDialog(
            onDismissRequest = { orphanScan = null },
            title = { Text("孤儿 .part 文件") },
            text = {
                if (list.isEmpty()) {
                    Text("没有发现孤儿文件：下载目录里的 .part 都还有任务认领。")
                } else {
                    Column {
                        Text(
                            "找到 ${list.size} 个没有任务认领的半成品文件，" +
                                "合计 ${Format.size(list.sumOf { it.size.coerceAtLeast(0) })}：",
                        )
                        Spacer(Modifier.height(6.dp))
                        for (o in list.take(20)) {
                            Text(
                                "· ${o.name}（${Format.size(o.size)}）",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (list.size > 20) {
                            Text(
                                "…还有 ${list.size - 20} 个",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "删除后不能恢复；正在下载或暂停中的任务文件不会出现在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (list.isNotEmpty()) {
                    TextButton(onClick = {
                        val targets = list
                        orphanScan = null
                        Thread {
                            var ok = 0
                            for (o in targets) {
                                if (PublicStorage.deleteOrphan(context, o)) ok++
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(
                                    context,
                                    "已删除 $ok/${targets.size} 个孤儿文件",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }.start()
                    }) { Text("全部删除") }
                }
            },
            dismissButton = {
                TextButton(onClick = { orphanScan = null }) { Text("关闭") }
            },
        )
    }
}

@androidx.compose.runtime.Composable
fun SettingsGroup(title: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@androidx.compose.runtime.Composable
fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((v, label) in options) {
            if (v == selected) {
                Button(onClick = { }) { Text(label) }
            } else {
                OutlinedButton(onClick = { onPick(v) }) { Text(label) }
            }
        }
    }
}

/**
 * Full-screen onboarding guide: shown on the first launch of EVERY
 * new version (the caller compares the stored version), and
 * re-openable from settings. Content describes this version's real
 * changes and is written for all users.
 */
@androidx.compose.runtime.Composable
fun GuideOverlay(version: String, onDone: () -> Unit) {
    val pages = listOf(
        "欢迎使用 TuxunDown" to
            "极速多线程下载器：把一个文件切成很多块、开多条连接同时下，" +
            "在服务器按单条连接限速时，速度可以达到浏览器单线程的数倍乃至十几倍。" +
            "免费开源，没有广告，不用注册账号。",
        "本版更新（v$version）" to
            "· 多了「已下载文件」页（主页进入）：下好的文件按时间/大小排好，打开、分享、删除都在这儿；删记录和删文件是两个分开的动作，不会误删文件\n" +
            "· 设置里多了下载代理（HTTP / SOCKS5）：下载和嗅探都走它，任务卡上会标明；代理连不上会停下报错，绝不偷偷改走直连\n" +
            "· 每个任务能改请求头了：卡片详情里查看/修改它带的 Cookie、Referer 等，改完续传时生效；导出备份会自动剔除 Cookie 和代理密码\n" +
            "· 嗅探结果更清爽：统计、广告、小图片这类噪音默认折叠（可展开）；名字和大小都一样的疑似重复资源会标出来\n" +
            "· 内置浏览器里长按某个链接，可以直接把这个链接丢给 TuxunDown 下载\n" +
            "· 嗅探结果关掉不再丢：主页有「上次嗅探结果」，点开接着选，不用重新嗅探\n" +
            "· 又过了一轮安全审计：导入备份加了大小上限和内容校验（坏文件搞不崩 App），往已打开的 App 分享链接也不会再开出第二个界面",
        "核心用法" to
            "点「新建下载」，把链接粘进来就行：\n" +
            "· 文件直链 → 直接开始多线程下载，还能预先改好保存文件名\n" +
            "· 网页链接 → 自动嗅探出页面里的资源，勾选后批量下载\n" +
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
                // Raster copy, NOT R.mipmap.ic_launcher — see the
                // header-icon note in DownApp (adaptive-icon XML
                // crashes painterResource on API 26+).
                painter = androidx.compose.ui.res.painterResource(R.drawable.app_icon),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun WinProgressBar(
    progress: Float,
    state: BarState,
    modifier: Modifier = Modifier,
    // v1.7 (audit B4): the server never told us the total size
    // (chunked response) — show a sweeping band instead of a
    // forever-empty 0% bar.
    indeterminate: Boolean = false,
) {
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
        if (indeterminate) {
            // A green band sweeping the full track, Windows style.
            val bandW = w * 0.35f
            val cx = phase * (w + bandW) - bandW
            clipRect(left = 0f, top = 0f, right = w, bottom = h) {
                drawRect(fill, topLeft = Offset(cx, 0f), size = Size(bandW, h))
                drawRect(
                    Color.White.copy(alpha = 0.18f),
                    topLeft = Offset(cx, 0f),
                    size = Size(bandW, h / 2),
                )
            }
            return@Canvas
        }
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 76.dp, height = 20.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
fun TaskCard(
    task: TaskRecord,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onSwipeDelete: () -> Unit,
    onJumpQueue: () -> Unit,
    onRename: () -> Unit,
    onSources: () -> Unit,
    onChecksum: () -> Unit,
    onHeaders: () -> Unit,
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
    val displayName = task.fileName.ifBlank { task.url }
    val kind = kindOfName(task.savedName.ifBlank { task.fileName })

    // ---- v1.8 swipe gestures: slide LEFT = pause/resume, slide
    // RIGHT = delete (the caller confirms first). Raw horizontal
    // drag + an offset Animatable — no new dependency. The action
    // layer sits BEHIND the card and is revealed as it slides.
    // Long-press multi-select is untouched (a press without
    // horizontal movement never becomes a drag), and while
    // selecting, swipes are off entirely. ----
    val swipeScope = rememberCoroutineScope()
    val swipeOffset = remember(task.id) { Animatable(0f) }
    val canPauseResume = task.status == TaskStatus.RUNNING ||
        task.status == TaskStatus.QUEUED ||
        task.status == TaskStatus.PAUSED ||
        task.status == TaskStatus.FAILED
    val swipeLabel = when (task.status) {
        TaskStatus.RUNNING, TaskStatus.QUEUED -> "暂停"
        TaskStatus.PAUSED, TaskStatus.FAILED -> "继续"
        else -> ""
    }
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        swipeOffset.value > 8f -> WinPalette.Red
                        swipeOffset.value < -8f && swipeLabel == "暂停" -> WinPalette.Yellow
                        swipeOffset.value < -8f && swipeLabel.isNotBlank() -> WinPalette.Green
                        else -> Color.Transparent
                    }
                ),
        ) {
            if (swipeOffset.value > 8f) {
                Text(
                    "松开删除",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                )
            } else if (swipeOffset.value < -8f && swipeLabel.isNotBlank()) {
                Text(
                    "松开$swipeLabel",
                    color = if (swipeLabel == "暂停") Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
                )
            }
        }
    Card(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
            .then(
                if (selecting) Modifier else Modifier.pointerInput(task.id, task.status) {
                    val threshold = 100.dp.toPx()
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val maxDrag = size.width * 0.45f
                            val minDrag = if (canPauseResume) -maxDrag else 0f
                            val target = (swipeOffset.value + dragAmount)
                                .coerceIn(minDrag, maxDrag)
                            swipeScope.launch { swipeOffset.snapTo(target) }
                        },
                        onDragEnd = {
                            swipeScope.launch {
                                when {
                                    swipeOffset.value > threshold -> {
                                        swipeOffset.animateTo(0f)
                                        onSwipeDelete()
                                    }
                                    swipeOffset.value < -threshold && canPauseResume -> {
                                        swipeOffset.animateTo(0f)
                                        if (task.status == TaskStatus.RUNNING ||
                                            task.status == TaskStatus.QUEUED
                                        ) onPause() else onResume()
                                    }
                                    else -> swipeOffset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = {
                            swipeScope.launch { swipeOffset.animateTo(0f) }
                        },
                    )
                }
            )
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() },
                onLongClick = { onToggleSelect() },
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            // ---- header: type badge + name + percent, buttons top-right ----
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                }
                FileTypeBadge(kind = kind, name = displayName)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // v1.7 (audit B4): unknown total (chunked
                    // response) must not read as "已完成 0%" while
                    // bytes are flowing — say what we know.
                    val unknownSize = task.totalSize <= 0 && !task.isHls
                    val headline = when (task.status) {
                        TaskStatus.RUNNING ->
                            if (unknownSize) "正在下载 · 大小未知" else "已完成 $pct%"
                        TaskStatus.PAUSED ->
                            if (unknownSize) "已暂停 · 大小未知" else "已暂停 · 已完成 $pct%"
                        TaskStatus.QUEUED ->
                            if (task.scheduleDaily)
                                "每天 ${Format.clockTime(task.scheduledAt)} 定时下载"
                            else if (task.scheduledAt > System.currentTimeMillis())
                                "预约 ${Format.clockTime(task.scheduledAt)} 开始"
                            else "排队中，等待开始"
                        TaskStatus.DONE -> "已完成"
                        TaskStatus.FAILED -> "下载失败"
                    }
                    Text(
                        headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (task.status) {
                            TaskStatus.FAILED -> WinPalette.Red
                            TaskStatus.DONE -> WinPalette.ChartGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                indeterminate = task.totalSize <= 0 && !task.isHls &&
                    task.status == TaskStatus.RUNNING,
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
                    TaskStatus.QUEUED ->
                        if (task.scheduleDaily)
                            "每天 ${Format.clockTime(task.scheduledAt)} 自动下载一次"
                        else if (task.scheduledAt > System.currentTimeMillis())
                            "预约在 ${Format.clockTime(task.scheduledAt)} 自动开始"
                        else "等待前面的任务完成"
                    TaskStatus.DONE -> Format.size(task.totalSize)
                    TaskStatus.FAILED -> task.error.ifBlank { "下载失败" }
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.status == TaskStatus.FAILED) WinPalette.Red
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // ---- expanded ("详细信息"): chart + stats + all rows ----
                val historySnapshot = synchronized(task.speedHistory) { task.speedHistory.toList() }
                val showChart = historySnapshot.size >= 2 &&
                    (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PAUSED ||
                        task.status == TaskStatus.DONE)
                if (showChart) {
                    Spacer(Modifier.height(10.dp))
                    if (task.status == TaskStatus.RUNNING) {
                        Text(
                            Format.speed(task.speedBps),
                            color = WinPalette.ChartGreen,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    } else if (task.status == TaskStatus.DONE) {
                        Text(
                            "下载速度曲线（已保留，可回看）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SpeedChart(
                        history = historySnapshot,
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (task.status) {
                    TaskStatus.RUNNING -> {
                        InfoRow("速度：", Format.speed(task.speedBps))
                        if (task.avgBps > 0) InfoRow("平均速度：", Format.speed(task.avgBps))
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
                        if (task.avgBps > 0) InfoRow("平均速度：", Format.speed(task.avgBps))
                        if (task.peakBps > 0) InfoRow("峰值速度：", Format.speed(task.peakBps))
                    }
                    TaskStatus.DONE -> {
                        InfoRow("大小：", Format.size(task.totalSize))
                        if (task.avgBps > 0) InfoRow("平均速度：", Format.speed(task.avgBps))
                        if (task.peakBps > 0) InfoRow("峰值速度：", Format.speed(task.peakBps))
                        if (task.elapsedMs > 0) InfoRow("总用时：", Format.duration(task.elapsedMs))
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
                            if (task.scheduleDaily)
                                "每天 ${Format.clockTime(task.scheduledAt)} 自动下载一次；" +
                                    "想停掉这个每日计划，暂停它或直接删除任务即可"
                            else if (task.scheduledAt > System.currentTimeMillis())
                                "预约在 ${Format.clockTime(task.scheduledAt)} 自动开始"
                            else "等待前面的任务完成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onJumpQueue) { Text("插队到最前") }
                    }
                }
                if (task.chunkSize > 0 && task.totalSize > 0 && task.status != TaskStatus.DONE) {
                    val total = (task.totalSize + task.chunkSize - 1) / task.chunkSize
                    InfoRow(
                        "分片：",
                        "已完成 ${task.chunkDone.count { it }}/$total 块 · ${task.threads} 线程",
                    )
                }
                // v1.6: HLS merge progress + multi-source state.
                if (task.isHls && task.hlsTotalSegments > 0) {
                    InfoRow(
                        "流媒体分片：",
                        "已合并 ${task.hlsDone.count { it }}/${task.hlsTotalSegments} 段",
                    )
                }
                if (task.sources.isNotEmpty()) {
                    InfoRow("下载源：", "${1 + task.sources.size} 个地址（含镜像）")
                }
                // v1.9: proxy marker — while a proxy is active,
                // this task's connections ride it (FTP excepted:
                // the hand-written client cannot use one, and the
                // settings copy says so).
                val proxyNow = DownloadCenter.proxySettings()
                if (proxyNow.active && !FtpClient.isFtpUrl(task.url) &&
                    task.status != TaskStatus.DONE
                ) {
                    InfoRow("下载代理：", "${proxyNow.typeLabel()} ${proxyNow.label()}")
                }
                if (task.status != TaskStatus.DONE) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onSources) {
                        Text(if (task.sources.isEmpty()) "加镜像源（多源并下）" else "管理镜像源")
                    }
                }
                // v1.7: integrity check — the expected hash the
                // finished file is verified against at completion.
                val expectedHash = Checksum.expectedOf(task)
                if (expectedHash != null) {
                    InfoRow(
                        "完整性校验：",
                        "${expectedHash.first} · ${Checksum.stateLabel(task.checksumState)}",
                    )
                }
                if (task.status != TaskStatus.RUNNING) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onChecksum) {
                        Text(if (expectedHash == null) "设置校验哈希" else "改校验哈希")
                    }
                }
                // v1.9: per-task request headers — view/edit the
                // Cookie / Referer / UA this task sends. Edits hit
                // the live map the engine reads per connection.
                if (task.status != TaskStatus.DONE) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onHeaders) {
                        Text(
                            if (task.headers.isEmpty()) "加请求头（Cookie 等）"
                            else "查看 / 改请求头（${task.headers.size} 个）"
                        )
                    }
                }
                if (task.retryCount > 0) {
                    InfoRow("重试次数：", "${task.retryCount} 次（分片级）")
                }
                if (task.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        task.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.status == TaskStatus.DONE) {
                    // ---- quick actions (v1.4 item 9) ----
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ext = displayName.substringAfterLast('.', "").lowercase()
                        if (ext == "apk") {
                            Button(onClick = onInstall) { Text("安装") }
                            OutlinedButton(onClick = onOpen) { Text("打开") }
                        } else if (kind == "video" || kind == "audio") {
                            Button(onClick = onOpen) { Text("播放") }
                            OutlinedButton(onClick = onShare) { Text("分享") }
                        } else {
                            OutlinedButton(onClick = onOpen) { Text("打开") }
                            OutlinedButton(onClick = onShare) { Text("分享") }
                        }
                        if (ext == "apk") {
                            OutlinedButton(onClick = onShare) { Text("分享") }
                        }
                    }
                }
                // Rename is only safe before any byte/target exists.
                if (task.status != TaskStatus.DONE && task.status != TaskStatus.RUNNING &&
                    task.targetUri.isBlank() && task.downloaded == 0L
                ) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onRename) { Text("改保存文件名") }
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
    } // v1.8: swipe Box
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

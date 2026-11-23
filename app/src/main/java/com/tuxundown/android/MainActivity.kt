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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tuxundown.android.core.AbnormalFilter
import com.tuxundown.android.core.Backup
import com.tuxundown.android.core.BatchRename
import com.tuxundown.android.core.ChargeGuard
import com.tuxundown.android.core.BulkControl
import com.tuxundown.android.core.CardFields
import com.tuxundown.android.core.Changelog
import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.ChunkMap
import com.tuxundown.android.core.ChunkPlanner
import com.tuxundown.android.core.ConnCompare
import com.tuxundown.android.core.CrashGuard
import com.tuxundown.android.core.CreateDedup
import com.tuxundown.android.core.Errors
import com.tuxundown.android.core.FailureAdvice
import com.tuxundown.android.core.ChecksumVerifier
import com.tuxundown.android.core.DaySeries
import com.tuxundown.android.core.Diagnostics
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.DownloadedFiles
import com.tuxundown.android.core.DuplicateFinder
import com.tuxundown.android.core.TaskPriority
import com.tuxundown.android.core.Format
import com.tuxundown.android.core.HealthCheck
import com.tuxundown.android.core.ThermalStatus
import com.tuxundown.android.core.HostMemory
import com.tuxundown.android.core.FtpClient
import com.tuxundown.android.core.LinkExtract
import com.tuxundown.android.core.MirrorAccess
import com.tuxundown.android.core.LinkSchemes
import com.tuxundown.android.core.QuietHours
import com.tuxundown.android.core.ThunderLink
import com.tuxundown.android.core.ProxySettings
import com.tuxundown.android.core.PublicStorage
import com.tuxundown.android.core.ScheduleTime
import com.tuxundown.android.core.SniffHistory
import com.tuxundown.android.core.SpeedCurveExport
import com.tuxundown.android.core.SpeedSchedule
import com.tuxundown.android.core.SpeedCompare
import com.tuxundown.android.core.SpeedHistory
import com.tuxundown.android.core.SpeedTest
import com.tuxundown.android.core.SpeedVerdict
import com.tuxundown.android.core.StartupTiming
import com.tuxundown.android.core.TaskHeaders
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.UpdateCheck
import com.tuxundown.android.core.UserAgents
import com.tuxundown.android.core.WidgetState
import com.tuxundown.android.sniff.LinkRouter
import com.tuxundown.android.sniff.SizeProbe
import com.tuxundown.android.sniff.SniffEstimate
import com.tuxundown.android.sniff.SniffExport
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
        // v5.1 (Step C): fallback start for the cold-start
        // measurement — one monotonic clock read, nothing else.
        // StartupTiming prefers the process start time; this
        // onCreate-entry read is only the fallback (see
        // StartupTiming's 口径 note).
        val onCreateEntryUptimeMs = android.os.SystemClock.uptimeMillis()
        // v2.2 crash self-rescue: install FIRST, before anything
        // that could itself crash, so even a launch-path failure
        // leaves a report the user can read and forward. Local
        // only — see CrashGuard.
        CrashGuard.install(this)
        // v5.1 (Step C): claim this process's cold-start slot
        // (first MainActivity creation only; crash-report /
        // safe-mode launches are not measured). Clock reads
        // only — no IO before the first frame.
        StartupTiming.beginColdStart(this, onCreateEntryUptimeMs)
        DownloadCenter.init(this)
        // v2.1 safe-area pass: targetSdk 35 means Android 15 draws
        // this window edge-to-edge whether we ask or not, sliding
        // content under the status and gesture bars. Opt in
        // explicitly on every version so the behaviour is the
        // same everywhere; the Compose root (DownApp) and the
        // full-screen guide pad themselves by the system-bar
        // insets, and the bar icon contrast follows the theme.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 33) {
            // v1.5 startup audit: a permission-request hiccup on an
            // exotic ROM must never cost the launch itself.
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (intent?.action == Intent.ACTION_SEND) {
            // v2.8 audit round 14: shared text is another app's
            // input — trim AND cap it before it can land in the
            // add dialog / link extractor (see LinkSchemes).
            SharedLinkState.text =
                LinkSchemes.sanitizeSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
        // v2.7: long-press icon shortcuts land here (and in
        // onNewIntent while running); DownApp consumes the
        // pending action once it is composed.
        if (intent?.action == ShortcutActions.NEW_DOWNLOAD ||
            intent?.action == ShortcutActions.OPEN_SNIFF
        ) {
            ShortcutState.action = intent.action!!
        }
        AmoledState.enabled = DownloadCenter.amoledBlack

        setContent {
            val dark = isSystemInDarkTheme()
            // v2.1 safe-area: with edge-to-edge windows, the
            // system-bar icon contrast must follow the APP's
            // (Compose-driven) theme, not the platform theme.
            val composeView = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                val controller = androidx.core.view.WindowCompat
                    .getInsetsController(window, composeView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
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
                // v2.2 crash self-rescue: a pending crash report
                // is shown BEFORE the normal UI — a friendly page
                // with 复制日志 / 继续打开 (see CrashGuard). The
                // read inside CrashGuard is fully guarded, and
                // the page itself uses only the safest widgets.
                var crashInfo by remember {
                    mutableStateOf(CrashGuard.pendingCrash(this@MainActivity))
                }
                val info = crashInfo
                if (info != null) {
                    CrashReportScreen(
                        info = info,
                        onContinue = {
                            CrashGuard.clearPending(this@MainActivity)
                            crashInfo = null
                        },
                    )
                } else {
                    DownApp()
                }
            }
        }

        // v5.1 (Step C): the END of the cold-start measurement —
        // a one-shot OnDrawListener on the decor view: the first
        // time a frame is actually drawn, the measurement is
        // finished and persisted (StartupTiming itself no-ops
        // when this process is not measuring: warm start,
        // recreation, crash-report launch). The listener
        // unregisters via a posted runnable — mutating the
        // observer from inside its own draw dispatch is not
        // allowed, and the posted removal lands right after
        // this frame. Registration is the only pre-frame cost.
        val decorView = window.decorView
        val firstDrawFired = java.util.concurrent.atomic.AtomicBoolean(false)
        val firstDrawListener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!firstDrawFired.compareAndSet(false, true)) return
                decorView.post {
                    runCatching {
                        if (decorView.viewTreeObserver.isAlive) {
                            decorView.viewTreeObserver.removeOnDrawListener(this)
                        }
                    }
                }
                StartupTiming.onFirstFrameDrawn(this@MainActivity)
            }
        }
        decorView.viewTreeObserver.addOnDrawListener(firstDrawListener)
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
                LinkSchemes.sanitizeSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
        if (intent.action == ShortcutActions.NEW_DOWNLOAD ||
            intent.action == ShortcutActions.OPEN_SNIFF
        ) {
            ShortcutState.action = intent.action!!
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
 * v3.8: a pending HLS variant choice — the variants a master
 * playlist offered, plus everything the interrupted create was
 * carrying, so picking a row can finish the create exactly as
 * if the user had pasted the variant link directly.
 */
class HlsPick(
    val variants: List<com.tuxundown.android.core.Hls.Variant>,
    val referer: String,
    val fileName: String,
    val mirrors: List<String>,
    val daily: Boolean,
    val onCreated: () -> Unit,
)

/** v2.7: long-press icon shortcut actions (res/xml/shortcuts.xml). */
object ShortcutActions {
    const val NEW_DOWNLOAD = "com.tuxundown.android.action.NEW_DOWNLOAD"
    const val OPEN_SNIFF = "com.tuxundown.android.action.OPEN_SNIFF"
}

/**
 * v2.7: a pending icon-shortcut action, seeded by MainActivity
 * (onCreate / onNewIntent) and consumed by DownApp — the same
 * shape as [SharedLinkState], for the same reason: the activity
 * may already be running when the shortcut fires.
 */
object ShortcutState {
    var action by mutableStateOf("")
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
    val truncatedState = mutableStateOf(false)
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
    // v2.9 (audit round 15): ChartGreen (#1F9D2C) on a light
    // background is ~3.5:1 — fine for charts, bars and the big
    // bold speed readouts (large text / graphics), NOT for
    // normal-size status TEXT. TextGreen is ~6.6:1 on white.
    val TextGreen = Color(0xFF146C1E)
}

/**
 * v2.9 (audit round 15): the green for normal-size status
 * text — ChartGreen in dark theme (~5.9:1 on black), the
 * darker TextGreen in light theme (~6.6:1 on white). Both
 * clear WCAG AA's 4.5:1 for body text; the bright greens
 * stay on graphics, where 3:1 is the bar.
 */
@androidx.compose.runtime.Composable
fun statusTextGreen(): Color =
    if (isSystemInDarkTheme()) WinPalette.ChartGreen else WinPalette.TextGreen

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
    // v4.9 (audit round 34): the 1 s UI tick is now lifecycle-
    // gated. Before this the LaunchedEffect below ran for as
    // long as the composition survived — including while the
    // activity was stopped and the app invisible — so the UI
    // woke the main thread every second for recompositions
    // nobody could see, fighting the very suspend the
    // screen-off power work tries to respect. The gate is a
    // plain flag flipped by a LifecycleEventObserver on the
    // ACTIVITY's lifecycle (androidx.lifecycle only, no new
    // dependency): ON_STOP closes it and the loop just polls
    // it on a long delay — no tick, no recomposition;
    // ON_START reopens it and ticks ONCE IMMEDIATELY, so the
    // first thing the user sees on return is the current
    // progress, then the normal 1 s beat resumes. Nothing
    // else changes: every existing tick consumer (TaskCard /
    // SummaryCard liveTick, the screens taking tick) reads
    // the same state as before, and the engine/service
    // samplers never read this tick — they run on their own
    // threads in DownloadEngine / DownloadService, so pausing
    // the UI beat cannot slow a download.
    val tickGate = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    tickGate.set(true)
                    tick++
                }
                Lifecycle.Event.ON_STOP -> tickGate.set(false)
                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (tickGate.get()) {
                delay(1000)
                if (tickGate.get()) tick++
            } else {
                // Stopped: poll the gate on a long delay only —
                // no tick and no recomposition while invisible.
                delay(5000)
            }
        }
    }
    // v2.2: the NORMAL UI staying alive for a few seconds ends
    // any crash streak (CrashGuard). Only this UI runs it — the
    // crash report page must not clear the streak by itself.
    LaunchedEffect(Unit) {
        delay(5_000)
        CrashGuard.markStable(context)
    }
    @Suppress("UNUSED_EXPRESSION") tick // re-read tasks on every tick

    // ---- navigation (v1.4: home / settings are separate screens) ----
    var screen by remember { mutableStateOf("home") }

    // ---- settings state (mirrors DownloadCenter prefs) ----
    var wifiOnly by remember { mutableStateOf(DownloadCenter.wifiOnly) }
    // v3.9: charge-only downloads switch mirror.
    var chargeOnlyUi by remember { mutableStateOf(DownloadCenter.chargeOnlyDownload) }
    // v4.9: screen-off speed keeping (wake lock) switch mirror.
    var keepCpuAwakeUi by remember { mutableStateOf(DownloadCenter.keepCpuAwake) }
    var dirName by remember { mutableStateOf(DownloadCenter.downloadDirName) }
    var limitKBps by remember { mutableStateOf(DownloadCenter.speedLimitKBps) }
    // v3.0: speed-limit schedule (switch + HH:MM window + window limit).
    var schedOn by remember { mutableStateOf(DownloadCenter.speedSchedEnabled) }
    var schedStartUi by remember {
        mutableStateOf(QuietHours.formatMin(DownloadCenter.speedSchedStartMin))
    }
    var schedEndUi by remember {
        mutableStateOf(QuietHours.formatMin(DownloadCenter.speedSchedEndMin))
    }
    var schedLimitKBps by remember { mutableStateOf(DownloadCenter.speedSchedLimitKBps) }
    var vibrateOn by remember { mutableStateOf(DownloadCenter.completeVibrate) }
    var soundOn by remember { mutableStateOf(DownloadCenter.completeSound) }
    // v2.5: notification quiet hours (switch + HH:MM window).
    var quietOn by remember { mutableStateOf(DownloadCenter.notifyQuietEnabled) }
    var quietStartUi by remember {
        mutableStateOf(QuietHours.formatMin(DownloadCenter.notifyQuietStartMin))
    }
    var quietEndUi by remember {
        mutableStateOf(QuietHours.formatMin(DownloadCenter.notifyQuietEndMin))
    }
    // v2.1: completion auto actions (both default off).
    var autoInstallUi by remember { mutableStateOf(DownloadCenter.autoInstallApk) }
    var autoOpenUi by remember { mutableStateOf(DownloadCenter.autoOpenFile) }
    var autoVerifyUi by remember { mutableStateOf(DownloadCenter.autoVerifyHash) }
    // v5.8: sidecar checksum files at task creation (default off).
    var autoSidecarUi by remember { mutableStateOf(DownloadCenter.autoReadChecksumFile) }
    // v3.0: auto-extract finished .zip tasks (default off).
    var autoExtractUi by remember { mutableStateOf(DownloadCenter.autoExtractZip) }
    var maxConc by remember { mutableStateOf(DownloadCenter.maxConcurrent) }
    // v3.9: global connection cap (all running tasks combined).
    var connCap by remember { mutableStateOf(DownloadCenter.globalConnectionCap) }
    var autoRetry by remember { mutableStateOf(DownloadCenter.autoRetryCount) }
    var trafficMB by remember { mutableStateOf(DownloadCenter.trafficThresholdMB) }
    var dualChan by remember { mutableStateOf(DownloadCenter.dualChannel) }
    // v2.6: boot resume + category subfolders (both default off).
    var bootResumeUi by remember { mutableStateOf(DownloadCenter.bootResume) }
    var sortFoldersUi by remember { mutableStateOf(DownloadCenter.sortSubfolders) }
    var archiveDaysUi by remember { mutableStateOf(DownloadCenter.archiveAfterDays) }
    var userAgentUi by remember { mutableStateOf(DownloadCenter.userAgent) }
    var amoledUi by remember { mutableStateOf(DownloadCenter.amoledBlack) }
    // v3.7: task-card optional detail rows (see CardFields).
    var cardFieldsUi by remember { mutableStateOf(DownloadCenter.cardFieldsSnapshot()) }
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
    // v2.7: consume a long-press icon shortcut. 新建下载 opens
    // the add dialog; 打开嗅探 opens the sniff browser (blank
    // page — the user navigates to the page they want, exactly
    // like opening it from the add dialog with nothing typed).
    val pendingShortcut = ShortcutState.action
    LaunchedEffect(pendingShortcut) {
        when (pendingShortcut) {
            ShortcutActions.NEW_DOWNLOAD -> {
                ShortcutState.action = ""
                showAdd = true
            }
            ShortcutActions.OPEN_SNIFF -> {
                ShortcutState.action = ""
                runCatching {
                    context.startActivity(
                        Intent(context, SniffActivity::class.java)
                            .putExtra(SniffActivity.EXTRA_URL, "")
                    )
                }
            }
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
    var sniffTruncated by SniffSession.truncatedState
    var sniffPage by SniffSession.pageState
    var sniffOpen by SniffSession.openState
    var sniffChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sniffKind by remember { mutableStateOf("") } // "" = all kinds
    var sniffSort by remember { mutableStateOf(0) } // 0 默认 1 名称 2 类型 3 大小
    var sniffMinMB by remember { mutableStateOf(0) } // v3.1: minimum-size filter, MB (0 = off)
    var sniffProbing by remember { mutableStateOf(false) } // v1.6 size probe phase
    var sniffShowNoise by remember { mutableStateOf(false) } // v1.9 noise folding

    // ---- HLS variant picker (v3.8): set when a single-create
    // flow found a master playlist with more than one variant;
    // the dialog below offers the choice BEFORE the task exists.
    var hlsPick by remember { mutableStateOf<HlsPick?>(null) }

    // ---- mirror-sources dialog (v1.6 item 4) ----
    var sourcesTask by remember { mutableStateOf<TaskRecord?>(null) }
    var sourceText by remember { mutableStateOf("") }
    // v5.4: snapshot of the open dialog task's manual mirror
    // overrides — the record's set is not Compose state, so the
    // dialog reads/writes this snapshot (and the record with it)
    // to repaint the 放行 buttons on tap.
    var mirrorOverridesUi by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ---- checksum dialog (v1.7) ----
    var hashTask by remember { mutableStateOf<TaskRecord?>(null) }
    var hashText by remember { mutableStateOf("") }

    // ---- request-header editor dialog (v1.9) ----
    var headersTask by remember { mutableStateOf<TaskRecord?>(null) }
    var headersText by remember { mutableStateOf("") }
    var headersError by remember { mutableStateOf("") }

    // ---- user-note editor dialog (v2.8) ----
    var noteTask by remember { mutableStateOf<TaskRecord?>(null) }
    var noteText by remember { mutableStateOf("") }

    // ---- home list state (v1.4: tabs, search, sort, multi-select) ----
    var tab by remember { mutableStateOf(0) } // 0 全部 1 下载中 2 已完成 3 失败
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(0) } // 0 默认 1 大小 2 时间
    // ---- v3.3: site-grouped view (persisted in DownloadCenter) ----
    var groupBySite by remember { mutableStateOf(DownloadCenter.taskGroupBySite) }
    var collapsedSites by remember { mutableStateOf(DownloadCenter.collapsedSites) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ---- rename dialog (v1.4 item 18) ----
    var renameTask by remember { mutableStateOf<TaskRecord?>(null) }
    var renameText by remember { mutableStateOf("") }

    // ---- v3.2: batch rename over the multi-selection ----
    var batchRenameOpen by remember { mutableStateOf(false) }
    // v3.7: batch strategy-retry dialog (multi-select, FAILED only).
    var retryStrategyOpen by remember { mutableStateOf(false) }
    // v5.3: explicit target set for the SAME dialog when it is
    // opened from the 异常 tab's batch entry — the ids of
    // AbnormalFilter.failedSubset. null = the original
    // multi-select source (targets come from selectedIds).
    // Cleared on every dialog exit so the multi-select path is
    // never polluted by a stale abnormal-tab target set.
    var retryTargetIds by remember { mutableStateOf<Set<String>?>(null) }
    var retryUaChoice by remember { mutableStateOf(-1) } // -1 = 不改 UA
    var retrySingleThread by remember { mutableStateOf(false) }
    var batchFind by remember { mutableStateOf("") }
    var batchReplace by remember { mutableStateOf("") }
    var batchPrefix by remember { mutableStateOf("") }
    var batchSuffix by remember { mutableStateOf("") }

    // ---- v1.8: swipe-right delete asks first (the X button keeps
    // its old immediate behaviour; a swipe is easier to trigger by
    // accident, so it earns a confirmation). ----
    var swipeDeleteTask by remember { mutableStateOf<TaskRecord?>(null) }

    // ---- v1.3 state: onboarding guide, clipboard prompt ----
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "6.1.0"
    }
    var showGuide by remember {
        // v2.2 safe mode: after 2+ consecutive crashes, first-
        // launch extras (this guide, the clipboard prompt below)
        // are skipped for the launch — a crash inside one of
        // them must not loop forever.
        mutableStateOf(
            !CrashGuard.safeMode && DownloadCenter.guideSeenVersion != appVersion
        )
    }
    var clipboardLink by remember { mutableStateOf("") }

    // v2.5 cold-start round 2: non-first-frame startup work
    // (today: the archive sweep) runs AFTER the first frame —
    // see DownloadCenter.runDeferredStartup. Safe mode skips
    // it inside, aligned with the guide/clipboard skips below.
    LaunchedEffect(Unit) {
        DownloadCenter.runDeferredStartup()
    }

    // Clipboard prompt: if the clipboard holds a link we have not
    // asked about yet, offer a one-tap task when the app opens.
    LaunchedEffect(Unit) {
        // v2.2 safe mode: skipped after repeated crashes.
        if (CrashGuard.safeMode) return@LaunchedEffect
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
                // v2.1: same extractor as the add dialog (adds
                // ftp:// and proper CJK punctuation handling).
                // v2.5: a clipboard holding only a thunder://
                // envelope extracts to nothing above — decode it
                // as the fallback so the prompt still offers it
                // (the prompt then shows the REAL address).
                val url = LinkExtract.extract(text).firstOrNull()
                    ?: ThunderLink.findDecoded(text).firstOrNull()
                // v5.4: the prompt suppression follows the same
                // CreateDedup verdict as creation itself — an
                // unfinished task for this link silences the
                // prompt; DONE history does not (re-downloading
                // it is allowed, so offering it is right).
                if (!url.isNullOrBlank() && url != DownloadCenter.clipboardPrompted &&
                    !CreateDedup.isDuplicate(store.tasks, url)
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
    // ---- v3.6: the FULL migration pack — the v1.8 backup
    // sections plus statistics and per-host learning data, for
    // moving to a new phone in one file. It imports through the
    // same 导入备份 picker below (Backup.parse reads the extra
    // sections when present and ignores them when absent). ----
    val migrationExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val json = Backup.buildMigration(
                store.exportJson(),
                DownloadCenter.settingsSnapshotJson(),
                DownloadCenter.statsSnapshotJson(),
                DownloadCenter.hostMemorySnapshot(),
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
                        if (ok) {
                            "迁移包已导出（设置＋任务＋统计＋学习数据）"
                        } else {
                            "导出失败"
                        },
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
                            chargeOnlyUi = DownloadCenter.chargeOnlyDownload
                            keepCpuAwakeUi = DownloadCenter.keepCpuAwake
                            limitKBps = DownloadCenter.speedLimitKBps
                            schedOn = DownloadCenter.speedSchedEnabled
                            schedStartUi =
                                QuietHours.formatMin(DownloadCenter.speedSchedStartMin)
                            schedEndUi =
                                QuietHours.formatMin(DownloadCenter.speedSchedEndMin)
                            schedLimitKBps = DownloadCenter.speedSchedLimitKBps
                            vibrateOn = DownloadCenter.completeVibrate
                            soundOn = DownloadCenter.completeSound
                            quietOn = DownloadCenter.notifyQuietEnabled
                            quietStartUi =
                                QuietHours.formatMin(DownloadCenter.notifyQuietStartMin)
                            quietEndUi =
                                QuietHours.formatMin(DownloadCenter.notifyQuietEndMin)
                            autoInstallUi = DownloadCenter.autoInstallApk
                            autoOpenUi = DownloadCenter.autoOpenFile
                            autoVerifyUi = DownloadCenter.autoVerifyHash
                            autoSidecarUi = DownloadCenter.autoReadChecksumFile
                            autoExtractUi = DownloadCenter.autoExtractZip
                            maxConc = DownloadCenter.maxConcurrent
                            connCap = DownloadCenter.globalConnectionCap
                            autoRetry = DownloadCenter.autoRetryCount
                            trafficMB = DownloadCenter.trafficThresholdMB
                            dualChan = DownloadCenter.dualChannel
                            // v3.1 audit (round 17): these two
                            // mirrors were missing from this
                            // refresh — after a restore the
                            // settings screen kept showing the
                            // pre-restore values while
                            // DownloadCenter already held the
                            // restored ones (the value WAS
                            // applied; only the display lied).
                            bootResumeUi = DownloadCenter.bootResume
                            sortFoldersUi = DownloadCenter.sortSubfolders
                            archiveDaysUi = DownloadCenter.archiveAfterDays
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
                        // v3.6: migration-pack sections. Absent
                        // in v1.8 backups and v1.4 files — those
                        // leave the current statistics and host
                        // learning exactly as they are.
                        if (parsed.stats != null) {
                            DownloadCenter.applyStatsJson(parsed.stats)
                            msg += if (msg.isEmpty()) {
                                "统计已恢复"
                            } else {
                                "，统计也已恢复"
                            }
                        }
                        if (parsed.hostMemory != null) {
                            DownloadCenter.applyHostMemory(parsed.hostMemory)
                            msg += if (msg.isEmpty()) {
                                "按主机学习数据已恢复"
                            } else {
                                "，按主机学习数据也已恢复"
                            }
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

    /**
     * The single-create funnel for this screen: paste / batch /
     * text extract / share / .txt import / sniff picks all end
     * up here. [quietDuplicate] is for batch callers — they
     * count DUPLICATE outcomes and report 「跳过了 N 个重复」
     * in their own summary instead of one toast per link.
     */
    fun createTask(
        url: String,
        referer: String = "",
        fileName: String = "",
        mirrors: List<String> = emptyList(),
        daily: Boolean = false,
        quietDuplicate: Boolean = false,
    ): CreateOutcome {
        // v2.8 audit round 14: the intake gate. Only http /
        // https / ftp links may become tasks at all — a file://,
        // content:// or intent:// string arriving via a share, a
        // sniffed page or a paste is refused HERE, in plain
        // words, instead of failing later inside the engine
        // (see LinkSchemes).
        if (!LinkSchemes.isSupported(url)) {
            Toast.makeText(
                context,
                "这个链接的格式不支持：只支持 http、https、ftp 开头的下载链接",
                Toast.LENGTH_LONG,
            ).show()
            return CreateOutcome.REJECTED
        }
        // v1.6 multi-source: mirrors of the SAME file (validated by
        // the engine before a single byte is taken from them).
        // Cleaned BEFORE the dedup verdict so the verdict judges
        // exactly the mirror set the record would carry.
        val cleanMirrors = mirrors.map { it.trim() }
            .filter { it.startsWith("http") && it != url }
            .distinct()
        // v5.4: the ONE dedup verdict (see CreateDedup) — an
        // unfinished task (QUEUED / RUNNING / PAUSED / FAILED)
        // holding this link (as primary or as a mirror) blocks
        // creation; DONE history does not. On a block: no
        // record, no enqueue, no service start.
        if (CreateDedup.isDuplicate(store.tasks, url, cleanMirrors)) {
            if (!quietDuplicate) {
                Toast.makeText(context, "这个链接已经在任务列表里了", Toast.LENGTH_SHORT).show()
            }
            return CreateOutcome.DUPLICATE
        }
        // v1.6: newTask flags .m3u8 links as HLS stream tasks.
        val t = DownloadCenter.newTask(url)
        if (referer.isNotBlank()) t.headers["Referer"] = referer
        if (fileName.isNotBlank()) t.fileName = fileName.trim()
        t.sources = cleanMirrors.toMutableList()
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
        return CreateOutcome.CREATED
    }

    /**
     * v3.8: single-create entry that offers the HLS 清晰度
     * choice first. A non-HLS link (and any HLS link whose
     * probe finds no real choice — media playlist, single
     * variant, probe failed) goes straight to [createTask],
     * unchanged. Only a master playlist with 2+ variants
     * pauses the create and raises the picker dialog; picking
     * a row creates the task against the CHOSEN media playlist
     * (its resume fingerprint then covers that playlist's
     * segments, as with any HLS task), and cancelling creates
     * nothing. Batch creation deliberately does NOT come
     * through here — a queue of links must not raise a queue
     * of dialogs; batch HLS tasks keep the engine's automatic
     * highest-bandwidth pick.
     */
    fun createTaskWithHlsChoice(
        url: String,
        referer: String = "",
        fileName: String = "",
        mirrors: List<String> = emptyList(),
        daily: Boolean = false,
        onCreated: () -> Unit = {},
    ) {
        if (!com.tuxundown.android.core.DownloadEngine.isHlsUrl(url)) {
            if (createTask(url, referer, fileName, mirrors, daily) == CreateOutcome.CREATED) onCreated()
            return
        }
        Thread {
            val headers = if (referer.isNotBlank()) {
                mapOf("Referer" to referer)
            } else {
                emptyMap()
            }
            val variants = com.tuxundown.android.core.HlsProbe.fetchVariants(
                url, headers, DownloadCenter.userAgent,
            )
            activity.runOnUiThread {
                if (variants != null && variants.size > 1) {
                    hlsPick = HlsPick(
                        com.tuxundown.android.core.Hls.sortedForChoice(variants),
                        referer, fileName, mirrors, daily, onCreated,
                    )
                } else if (createTask(url, referer, fileName, mirrors, daily) == CreateOutcome.CREATED) {
                    onCreated()
                }
            }
        }.start()
    }

    fun handleSniffResult(result: SniffResult, pageUrl: String) {
        when (result) {
            is SniffResult.DirectFile -> {
                // v3.8: a sniffed .m3u8 master gets the variant
                // picker; the dialog closes only once a task
                // really exists (a duplicate keeps it open so
                // the user sees why nothing happened).
                createTaskWithHlsChoice(
                    result.url, referer = pageUrl, daily = scheduleMode == 1,
                ) {
                    showAdd = false
                    urlText = ""
                }
            }
            is SniffResult.ResourceList -> {
                DownloadCenter.rememberSniffedPage(result.pageUrl)
                sniffList = result.resources
                sniffTotal = result.totalCount
                sniffTruncated = result.truncated
                sniffPage = result.pageUrl
                sniffOpen = true
                sniffShowNoise = false
                sniffChecked = result.resources
                    .filter { it.looksLikeDownloadEntry }
                    .map { it.url }.toSet()
                // v3.1: restore the remembered filter (kind +
                // minimum size, see DownloadCenter) instead of
                // always resetting to "everything". A remembered
                // kind this page does not have falls back to
                // 全部 — restoring it blindly would filter the
                // fresh list to empty with no chip to undo it.
                val savedKind = DownloadCenter.sniffFilterKind
                sniffKind = if (result.resources.any { it.kind == savedKind }) {
                    savedKind
                } else ""
                sniffMinMB = DownloadCenter.sniffFilterMinMB
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
                .getOrElse { SniffResult.Failure(Errors.human(it)) }
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
        // v2.5: a thunder:// line decodes to its real address
        // first; an undecodable one is counted and named in the
        // final toast, never silently dropped.
        var thunderFailed = 0
        val lines = raw.lines().map { it.trim() }.mapNotNull { line ->
            if (ThunderLink.isThunderLink(line)) {
                val d = ThunderLink.decode(line)
                if (d == null) thunderFailed++
                d
            } else {
                line
            }
        }
            // v1.7: ftp:// lines are batch-eligible too (direct tasks).
            .filter { it.startsWith("http") || FtpClient.isFtpUrl(it) }
        if (lines.isEmpty()) {
            if (thunderFailed > 0) {
                Toast.makeText(
                    context,
                    "迅雷链接解不开（里面的地址不完整或已损坏）",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        // v1.8: batch creation is always immediate — a daily slot
        // computed for a single task must not leak into the batch.
        if (scheduleMode == 1) pendingScheduleAt = 0L
        showAdd = false
        urlText = ""
        Toast.makeText(context, "正在批量处理 ${lines.size} 个链接…", Toast.LENGTH_SHORT).show()
        Thread {
            var created = 0
            var skipped = 0
            // v5.4: duplicates are counted separately from the
            // "not a direct file" skips and named in the summary
            // (the per-link duplicate toast is quieted here —
            // the summary below is the batch's one report).
            // Batch self-dedup falls out of the funnel: each
            // accepted link is in the store before the next line
            // is judged, so a link appearing twice in one batch
            // creates once and counts once as a duplicate.
            var dupSkipped = 0
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
                    var outcome = CreateOutcome.REJECTED
                    val latch = java.util.concurrent.CountDownLatch(1)
                    activity.runOnUiThread {
                        outcome = createTask(make, quietDuplicate = true)
                        latch.countDown()
                    }
                    latch.await()
                    when (outcome) {
                        CreateOutcome.CREATED -> created++
                        CreateOutcome.DUPLICATE -> dupSkipped++
                        CreateOutcome.REJECTED -> skipped++
                    }
                }
            }
            activity.runOnUiThread {
                Toast.makeText(
                    context,
                    "批量完成：新建 $created 个任务" +
                        (if (dupSkipped > 0) "，跳过了 $dupSkipped 个重复" else "") +
                        (if (skipped > 0) "，跳过 $skipped 个（网页资源需单独选）" else "") +
                        (if (thunderFailed > 0) "，$thunderFailed 个迅雷链接解不开" else ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    fun startSmart(raw: String) {
        var u = raw.trim()
        if (u.isBlank() || sniffing) return
        // v2.5: thunder:// links are envelopes, not addresses —
        // decode them back to the real address IN PLACE first,
        // so every path below (single, batch, probe, sniff)
        // sees ordinary links. A paste whose thunder links ALL
        // fail to decode says so plainly instead of sniffing
        // the envelope.
        if (ThunderLink.containsToken(u)) {
            val prep = ThunderLink.replaceTokens(u)
            if (prep.decoded == 0) {
                message = "这个迅雷链接解不开（里面的地址不完整或已损坏），换一个链接试试。"
                return
            }
            if (prep.failed > 0) {
                Toast.makeText(
                    context,
                    "有 ${prep.failed} 个迅雷链接解不开，已跳过",
                    Toast.LENGTH_LONG,
                ).show()
            }
            u = prep.text.trim()
        }
        // v2.1: arbitrary text — a paragraph shared from a chat
        // app, a forum post — can carry the link(s) inside prose.
        // Extract first (LinkExtract): several links go down the
        // batch path; exactly one embedded link continues as if it
        // had been pasted bare; a bare link extracts to itself and
        // nothing changes.
        val foundLinks = LinkExtract.extract(u)
        if (foundLinks.size > 1) {
            startBatch(foundLinks.joinToString("\n"))
            return
        }
        if (foundLinks.size == 1 && foundLinks[0] != u) u = foundLinks[0]
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
            createTaskWithHlsChoice(
                u, fileName = nameText, mirrors = mirrors, daily = scheduleMode == 1,
            ) {
                showAdd = false
                urlText = ""
                nameText = ""
                mirrorsText = ""
            }
            return
        }
        sniffing = true
        message = ""
        Thread {
            val route = runCatching { LinkRouter.probe(u) }.getOrNull()
            activity.runOnUiThread {
                if (route == LinkRouter.Route.DIRECT_FILE) {
                    sniffing = false
                    createTaskWithHlsChoice(
                        u, fileName = nameText, mirrors = mirrors, daily = scheduleMode == 1,
                    ) {
                        showAdd = false
                        urlText = ""
                        nameText = ""
                        mirrorsText = ""
                    }
                } else {
                    startSniff(u)
                }
            }
        }.start()
    }

    // ---- v2.4: import links from a .txt file (batch create) ----
    // Declared AFTER startBatch on purpose: this launcher is a
    // lambda, and a lambda body cannot forward-reference a local
    // function declared later in the same scope (it fails with
    // "Unresolved reference" — a lesson already paid for once).
    // One link per line is the expected shape; prose with links
    // buried in it also works (LinkExtract fallback). The read is
    // bounded like the backup import — a "txt" that is really a
    // huge file must not be loaded whole into memory.
    val txtImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Thread {
                var tooBig = false
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > 4 * 1024 * 1024) {
                                tooBig = true
                                break
                            }
                        }
                        if (tooBig) null else out.toByteArray().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                activity.runOnUiThread {
                    when {
                        text == null -> Toast.makeText(
                            context,
                            if (tooBig) "导入失败：文件太大了（超过 4 MB），不像链接清单"
                            else "导入失败：读不到文件",
                            Toast.LENGTH_SHORT,
                        ).show()
                        else -> {
                            val lineLinks = text.lines().map { it.trim() }.filter {
                                it.startsWith("http") || FtpClient.isFtpUrl(it) ||
                                    ThunderLink.isThunderLink(it)
                            }
                            val effective = when {
                                lineLinks.isNotEmpty() -> text
                                // v2.5: prose fallback also decodes
                                // thunder envelopes buried in text.
                                else -> (LinkExtract.extract(text) +
                                    ThunderLink.findDecoded(text))
                                    .distinct().joinToString("\n")
                            }
                            if (effective.isBlank()) {
                                Toast.makeText(
                                    context, "这个文件里没找到链接",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                startBatch(effective)
                            }
                        }
                    }
                }
            }.start()
        }
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
        // Audit round 43: a verify of this (finished) task may
        // be streaming a multi-GB file right now — ask it to
        // stop at the next buffer instead of reading on for a
        // record that is about to be gone. No-op when no
        // verify is running.
        ChecksumVerifier.requestCancel(task.id)
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
        // v2.1: a deleted queued task may never wake the service —
        // refresh the home-screen widget from here too.
        com.tuxundown.android.widget.WidgetUpdater.refresh(context)
    }

    // One card wiring, reused by the narrow list and the wide
    // two-column rows (v1.6 item 7).
    val TaskCardFor: @androidx.compose.runtime.Composable (TaskRecord) -> Unit = { task ->
        TaskCard(
            task = task,
            liveTick = tick,
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
                mirrorOverridesUi = task.sourceOverrides.toSet()
            },
            onChecksum = {
                hashTask = task
                hashText = task.expectedSha256.ifBlank { task.expectedMd5 }
            },
            onVerifyHash = {
                // v2.9: manual verify — same engine as the
                // dialog's save-and-verify and the service's
                // auto-verify; runs off the UI thread, verdict
                // lands as a toast + on the card.
                Toast.makeText(context, "开始校验…", Toast.LENGTH_SHORT).show()
                Thread {
                    val verdict = runCatching {
                        ChecksumVerifier.verify(context, task)
                    }.getOrDefault("")
                    activity.runOnUiThread {
                        store.saveNow()
                        if (verdict.isNotBlank()) {
                            Toast.makeText(context, verdict, Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            },
            onCancelVerify = { ChecksumVerifier.requestCancel(task.id) },
            onHeaders = {
                headersTask = task
                headersText = TaskHeaders.toText(task.headers)
                headersError = ""
            },
            onUserNote = {
                noteTask = task
                noteText = task.userNote
            },
            onTaskLimit = { kbps ->
                // v2.1: per-task cap lives on the record; the
                // engine's limiter reads it live on every tick.
                task.taskLimitKBps = kbps
                store.save()
            },
            onPriority = { level ->
                // v3.1: three-tier priority — DownloadCenter
                // clamps + persists; drain order and the budget
                // split both read the record live.
                DownloadCenter.setPriority(task, level)
            },
            onOpenInBrowser = {
                // v2.7 (failure advice): open this task's page in
                // the sniff browser — logging in there and tapping
                // the download carries the session Cookie into a
                // fresh task, which is the fix for a 403.
                runCatching {
                    context.startActivity(
                        Intent(context, SniffActivity::class.java)
                            .putExtra(SniffActivity.EXTRA_URL, task.url)
                    )
                }
            },
            onOpenFiles = { screen = "files" },
        )
    }

    // v1.6 item 7: wide screens get two task columns (read here, in
    // composable scope — the LazyColumn content lambda is not one).
    val wideScreen =
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600

    val activeTasks = store.tasks.filter {
        it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
    }

    // v2.4: archived records live ONLY under the 已归档 tab (4);
    // every other tab excludes them — including 已完成， which is
    // the tab they were folded away from.
    val anyArchived = store.tasks.any { it.archived }
    // v5.2: the 异常 tab's gate context, read ONCE here on every
    // recomposition from the live gate state (see AbnormalFilter
    // for why the WiFi / charge half is judged by the gates and
    // not by per-task flags: the drain stamps a flag/note only
    // on the queue head, so tasks behind it would be missed).
    // An unreadable WiFi state counts as "not on WiFi" — the
    // same conservative read enqueue's gate makes.
    val abnormalGates = AbnormalFilter.GateContext(
        wifiGateClosed = DownloadCenter.wifiOnly &&
            !runCatching { DownloadCenter.isOnWifi() }.getOrDefault(false),
        chargeGateClosed = ChargeGuard.shouldPark(
            DownloadCenter.chargeOnlyDownload, DownloadCenter.isCharging()
        ),
        nowMs = System.currentTimeMillis(),
    )
    // The tab's count and the tab's list share ONE predicate
    // (AbnormalFilter.count is literally its filter's size).
    val abnormalCount = AbnormalFilter.count(store.tasks, abnormalGates)
    // v5.3: the FAILED subset of that same abnormal list — the
    // 异常 tab's batch entry targets exactly these (parked tasks
    // wait for a condition to recover; they are not retried).
    val abnormalFailed = AbnormalFilter.failedSubset(store.tasks, abnormalGates)
    val anyAbnormal = abnormalCount >= 1
    // v5.2: never strand the user on an invisible tab — the
    // 异常 tab is hidden while searching (below) and disappears
    // when its count reaches zero, so either event bounces a
    // selected 异常 tab back to 全部.
    LaunchedEffect(query, abnormalCount) {
        if (tab == 5 && (query.isNotBlank() || abnormalCount == 0)) tab = 0
    }
    val displayedTasks = store.tasks
        .filter {
            when (tab) {
                4 -> it.archived
                5 -> AbnormalFilter.isAbnormal(it, abnormalGates)
                1 -> !it.archived && (it.status == TaskStatus.RUNNING ||
                    it.status == TaskStatus.QUEUED || it.status == TaskStatus.PAUSED)
                2 -> !it.archived && it.status == TaskStatus.DONE
                3 -> !it.archived && it.status == TaskStatus.FAILED
                else -> !it.archived
            }
        }
        .filter {
            query.isBlank() ||
                it.fileName.contains(query, true) || it.url.contains(query, true) ||
                // v2.8: the user's own note is searchable too —
                // "给娃下的那批" should be findable by its note.
                it.userNote.contains(query, true)
        }
        .let { list ->
            when (sortMode) {
                1 -> list.sortedByDescending { it.totalSize }
                2 -> list.sortedBy { it.createdAt }
                else -> list
            }
        }

    // v3.3: a non-blank query switches the home list into
    // GLOBAL search mode — tasks across all tabs (archived
    // included), downloaded files and sniff history, in three
    // groups INSTEAD of the tab-filtered list (see
    // GlobalSearch). The task group honours the same sort
    // modes as the flat list. Null = normal browsing.
    val globalResult = if (query.isNotBlank()) {
        val r = com.tuxundown.android.core.GlobalSearch.search(
            store.tasks, DownloadCenter.sniffHistory, query,
        )
        val sortedTasks = when (sortMode) {
            1 -> r.tasks.sortedByDescending { it.totalSize }
            2 -> r.tasks.sortedBy { it.createdAt }
            else -> r.tasks
        }
        r.copy(tasks = sortedTasks)
    } else {
        null
    }

    // v3.3: the site-grouped view of the SAME tab-filtered,
    // sorted list — grouping preserves the list's order inside
    // each group and only adds headers (see SiteGroups for the
    // coexistence rules). Empty unless the view is on.
    val siteGroups = if (groupBySite && globalResult == null) {
        com.tuxundown.android.core.SiteGroups.group(displayedTasks)
    } else {
        emptyList()
    }

    // v2.1 safe-area: the whole UI sits inside the system-bar
    // insets (see MainActivity.onCreate) — screens and their
    // bottom buttons never slide under the status or gesture bar
    // on any Android version. Dialogs are separate windows; the
    // full-screen guide pads itself.
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
            ),
    ) {
    if (screen == "settings") {
        SettingsScreen(
            version = appVersion,
            wifiOnly = wifiOnly,
            onWifiOnly = { wifiOnly = it; DownloadCenter.wifiOnly = it },
            chargeOnly = chargeOnlyUi,
            onChargeOnly = { chargeOnlyUi = it; DownloadCenter.chargeOnlyDownload = it },
            keepCpuAwake = keepCpuAwakeUi,
            onKeepCpuAwake = { keepCpuAwakeUi = it; DownloadCenter.keepCpuAwake = it },
            dirName = dirName,
            onPickDir = { dirPicker.launch(null) },
            onResetDir = {
                DownloadCenter.downloadDirUri = ""
                DownloadCenter.downloadDirName = ""
                dirName = ""
            },
            limitKBps = limitKBps,
            onLimit = { limitKBps = it; DownloadCenter.speedLimitKBps = it },
            schedOn = schedOn,
            onSchedOn = { schedOn = it; DownloadCenter.speedSchedEnabled = it },
            schedStartText = schedStartUi,
            onSchedStartText = { v ->
                schedStartUi = v
                ScheduleTime.parseHhMm(v)?.let {
                    DownloadCenter.speedSchedStartMin = it.first * 60 + it.second
                }
            },
            schedEndText = schedEndUi,
            onSchedEndText = { v ->
                schedEndUi = v
                ScheduleTime.parseHhMm(v)?.let {
                    DownloadCenter.speedSchedEndMin = it.first * 60 + it.second
                }
            },
            schedLimitKBps = schedLimitKBps,
            onSchedLimit = { schedLimitKBps = it; DownloadCenter.speedSchedLimitKBps = it },
            maxConc = maxConc,
            onMaxConc = { maxConc = it; DownloadCenter.maxConcurrent = it },
            connCap = connCap,
            onConnCap = { connCap = it; DownloadCenter.globalConnectionCap = it },
            autoRetry = autoRetry,
            onAutoRetry = { autoRetry = it; DownloadCenter.autoRetryCount = it },
            trafficMB = trafficMB,
            onTraffic = { trafficMB = it; DownloadCenter.trafficThresholdMB = it },
            dualChan = dualChan,
            onDualChan = { dualChan = it; DownloadCenter.dualChannel = it },
            bootResume = bootResumeUi,
            onBootResume = { bootResumeUi = it; DownloadCenter.bootResume = it },
            sortFolders = sortFoldersUi,
            onSortFolders = { sortFoldersUi = it; DownloadCenter.sortSubfolders = it },
            archiveDays = archiveDaysUi,
            onArchiveDays = { days ->
                archiveDaysUi = days
                DownloadCenter.archiveAfterDays = days
                val n = DownloadCenter.applyArchiveSweep()
                if (n > 0) {
                    Toast.makeText(
                        context, "已把 $n 条旧记录收进「已归档」",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            userAgent = userAgentUi,
            onUserAgent = { userAgentUi = it; DownloadCenter.userAgent = it },
            amoled = amoledUi,
            onAmoled = {
                amoledUi = it
                DownloadCenter.amoledBlack = it
                AmoledState.enabled = it
            },
            cardFields = cardFieldsUi,
            onCardField = { key, v ->
                DownloadCenter.setCardField(key, v)
                cardFieldsUi = DownloadCenter.cardFieldsSnapshot()
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
            quietOn = quietOn,
            onQuiet = { quietOn = it; DownloadCenter.notifyQuietEnabled = it },
            quietStartText = quietStartUi,
            onQuietStartText = { v ->
                quietStartUi = v
                ScheduleTime.parseHhMm(v)?.let {
                    DownloadCenter.notifyQuietStartMin = it.first * 60 + it.second
                }
            },
            quietEndText = quietEndUi,
            onQuietEndText = { v ->
                quietEndUi = v
                ScheduleTime.parseHhMm(v)?.let {
                    DownloadCenter.notifyQuietEndMin = it.first * 60 + it.second
                }
            },
            autoInstall = autoInstallUi,
            onAutoInstall = { autoInstallUi = it; DownloadCenter.autoInstallApk = it },
            autoOpen = autoOpenUi,
            onAutoOpen = { autoOpenUi = it; DownloadCenter.autoOpenFile = it },
            autoVerify = autoVerifyUi,
            onAutoVerify = { autoVerifyUi = it; DownloadCenter.autoVerifyHash = it },
            autoSidecar = autoSidecarUi,
            onAutoSidecar = { autoSidecarUi = it; DownloadCenter.autoReadChecksumFile = it },
            autoExtract = autoExtractUi,
            onAutoExtract = { autoExtractUi = it; DownloadCenter.autoExtractZip = it },
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
            onShowHelp = { screen = "help" },
            onShowDiag = { screen = "diag" },
            onExport = { exportLauncher.launch("tuxundown-backup.json") },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            onMigrationExport = { migrationExportLauncher.launch("tuxundown-migration.json") },
            onBack = { screen = "home" },
        )
    } else if (screen == "stats") {
        StatsScreen(
            onBack = { screen = "home" },
            onSpeedTest = { screen = "speedtest" },
            tick = tick,
        )
    } else if (screen == "files") {
        FilesScreen(onBack = { screen = "home" })
    } else if (screen == "help") {
        HelpScreen(onBack = { screen = "settings" })
    } else if (screen == "diag") {
        DiagnosticsScreen(version = appVersion, onBack = { screen = "settings" })
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

            // v2.9: share every UNFINISHED task as one link list
            // (the multi-select bar shares just the selection).
            // The list re-imports through 新建下载's batch paste
            // or the .txt import — ShareList's header says so.
            val unfinishedForShare =
                com.tuxundown.android.core.ShareList.unfinished(store.tasks)
            if (unfinishedForShare.isNotEmpty()) {
                item(key = "sharelistbtn") {
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    com.tuxundown.android.core.ShareList
                                        .build(unfinishedForShare),
                                )
                                putExtra(Intent.EXTRA_SUBJECT, "TuxunDown 链接清单")
                            }
                            context.startActivity(
                                Intent.createChooser(send, "分享链接清单")
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("分享未完成任务的链接清单（${unfinishedForShare.size} 个）") }
                }
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

            // v2.7: the summary card also appears when everything
            // is paused (2+ resumable) — that is exactly when its
            // new 全部继续 button is the point of the card.
            if (activeTasks.size >= 2 ||
                BulkControl.resumeTargets(store.tasks).size >= 2
            ) {
                item(key = "summary") { SummaryCard(activeTasks, store.tasks, liveTick = tick) }
            }

            // ---- tabs (v1.4 item 2) ----
            item(key = "tabs") {
                Row(
                    // v2.0: sideways-scrollable so max font scale
                    // cannot push the last tab off the screen.
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // v2.4: the 已归档 tab appears only when at
                    // least one archived record exists.
                    // v5.2: same rule for the 异常 tab (5) — it
                    // appears only when its count is at least 1,
                    // and never while searching (a query switches
                    // to global search, whose groups replace the
                    // tab list; the LaunchedEffect above bounces
                    // a selected 异常 tab back to 全部 first).
                    // Its label carries the count in the app's
                    // parenthesised-count style （分享清单（N 个） /
                    // 任务（N）), from AbnormalFilter.count — the
                    // same predicate the tab's list filters by.
                    val abnormalTab = if (anyAbnormal && query.isBlank()) {
                        listOf(5 to "异常（$abnormalCount）")
                    } else {
                        emptyList()
                    }
                    val archiveTab =
                        if (anyArchived) listOf(4 to "已归档") else emptyList()
                    val tabs = listOf(0 to "全部", 1 to "下载中", 2 to "已完成", 3 to "失败") +
                        abnormalTab + archiveTab
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
            // v3.3: the box is a GLOBAL search now (tasks /
            // downloaded files / sniff history), so it also
            // shows when only sniff history exists.
            if (store.tasks.isNotEmpty() || DownloadCenter.sniffHistory.isNotEmpty()) {
                item(key = "search") {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索任务、已下载文件、嗅探历史") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (globalResult != null) {
                            Text(
                                "全局搜索中：下面按任务 / 已下载文件 / 嗅探历史分组显示，" +
                                    "清空搜索就回到任务列表。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp)
                                .horizontalScroll(rememberScrollState()),
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
                        // v3.3: list view toggle — only while
                        // browsing (a query switches to global
                        // search, which has its own groups).
                        if (query.isBlank()) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "视图：",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val views = listOf(false to "平铺", true to "按站点")
                                for ((v, label) in views) {
                                    if (v == groupBySite) {
                                        Button(onClick = { }) { Text(label) }
                                    } else {
                                        OutlinedButton(onClick = {
                                            groupBySite = v
                                            DownloadCenter.taskGroupBySite = v
                                        }) { Text(label) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- v2.4: archive restore bar (only on the 已归档 tab) ----
            if (tab == 4) {
                item(key = "archbar") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "这里是自动归档的已完成记录：只是把记录收起来，" +
                                    "下载好的文件本体没动、还在原来的地方。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(
                                onClick = {
                                    var n = 0
                                    for (t in store.tasks) {
                                        if (t.archived) {
                                            t.archived = false
                                            n++
                                        }
                                    }
                                    if (n > 0) store.saveNow()
                                    Toast.makeText(
                                        context, "已恢复 $n 条记录到「已完成」",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                modifier = Modifier.padding(top = 6.dp),
                            ) { Text("全部恢复显示") }
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
                                    // v3.3: in global-search mode
                                    // the rendered set is the
                                    // search hits, not the tab list.
                                    selectedIds = (globalResult?.tasks ?: displayedTasks)
                                        .map { it.id }.toSet()
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
                                if (tab == 4) {
                                    OutlinedButton(onClick = {
                                        var n = 0
                                        for (t in store.tasks) {
                                            if (t.id in selectedIds && t.archived) {
                                                t.archived = false
                                                n++
                                            }
                                        }
                                        if (n > 0) store.saveNow()
                                        selectedIds = emptySet()
                                        selecting = false
                                        Toast.makeText(
                                            context, "已恢复 $n 条记录",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }) { Text("取消归档") }
                                }
                                OutlinedButton(onClick = { batchRenameOpen = true }) {
                                    Text("批量改名")
                                }
                                if (store.tasks.any {
                                        it.id in selectedIds && it.status == TaskStatus.FAILED
                                    }
                                ) {
                                    OutlinedButton(onClick = {
                                        retryUaChoice = -1
                                        retrySingleThread = false
                                        retryStrategyOpen = true
                                    }) {
                                        Text("换个方式重试")
                                    }
                                }
                                OutlinedButton(onClick = {
                                    // v2.9: share the selected tasks
                                    // as a plain-text link list (the
                                    // import side is batch paste /
                                    // .txt import — see ShareList).
                                    val picked = store.tasks.filter { it.id in selectedIds }
                                    if (picked.isEmpty()) {
                                        Toast.makeText(
                                            context, "先选几个任务再分享",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                com.tuxundown.android.core.ShareList.build(picked),
                                            )
                                            putExtra(Intent.EXTRA_SUBJECT, "TuxunDown 链接清单")
                                        }
                                        context.startActivity(
                                            Intent.createChooser(send, "分享链接清单")
                                        )
                                    }
                                }) { Text("分享清单") }
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

            // ---- v3.3: global search results (three groups) ----
            // Rendered INSTEAD of the tab list while a query is
            // present. A task hit is the full card (that is its
            // location — every action works right there); a
            // file hit jumps to the files page (the search does
            // not open files itself); a history hit re-opens
            // the add dialog with the page filled in, exactly
            // like tapping the entry inside that dialog.
            if (globalResult != null) {
                if (globalResult.isEmpty) {
                    item(key = "g-none") {
                        Text("全局搜索没有找到和「${query.trim()}」有关的内容。")
                    }
                }
                if (globalResult.tasks.isNotEmpty()) {
                    item(key = "g-tasks-head") {
                        Text(
                            "任务（${globalResult.tasks.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(globalResult.tasks, key = { "g-" + it.id }) { task ->
                        TaskCardFor(task)
                    }
                }
                if (globalResult.files.isNotEmpty()) {
                    item(key = "g-files-head") {
                        Text(
                            "已下载文件（${globalResult.files.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(globalResult.files, key = { "gf-" + it.taskId }) { f ->
                        Card(
                            Modifier.fillMaxWidth().clickable { screen = "files" },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${com.tuxundown.android.core.Format.size(f.size)} · 点开去「已下载文件」页",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (globalResult.history.isNotEmpty()) {
                    item(key = "g-hist-head") {
                        Text(
                            "嗅探历史（${globalResult.history.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(globalResult.history, key = { "gh-$it" }) { h ->
                        Text(
                            h,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { urlText = h; showAdd = true }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }

            // ---- empty state (v1.4 item 14) ----
            if (globalResult == null && store.tasks.isEmpty()) {
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
            } else if (globalResult == null && displayedTasks.isEmpty()) {
                item(key = "nomatch") {
                    Text("这个分类下没有任务。")
                }
            }

            // v5.3: the 异常 tab's batch entry — sits at the top
            // of the abnormal list only (tab == 5; the tab itself
            // never exists while searching, and globalResult is
            // null-checked too, so this cannot leak onto another
            // tab or into search results). Shown only at 2+
            // FAILED (AbnormalFilter.showFailedBatch); the parked
            // entries below it are deliberately NOT its targets —
            // the subtitle says why. Opens the v3.7 strategy-
            // retry dialog with the FAILED subset as its explicit
            // target set (retryTargetIds).
            if (tab == 5 && globalResult == null &&
                AbnormalFilter.showFailedBatch(abnormalFailed.size)
            ) {
                item(key = "failedbatch") {
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            retryUaChoice = -1
                            retrySingleThread = false
                            retryTargetIds = abnormalFailed.map { it.id }.toSet()
                            retryStrategyOpen = true
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "失败任务批量处理（${abnormalFailed.size} 个）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "只处理失败的任务；停泊中的在等网络/充电等条件恢复，" +
                                    "不用重试，不在这里处理。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // v1.6 item 7: on wide screens (landscape / tablet /
            // unfolded foldable) the tasks flow in TWO columns
            // inside the same single scrolling LazyColumn — cards
            // keep their natural height, nothing is stretched.
            // v3.3: in global-search mode the groups above ARE
            // the list; the flat list renders only when browsing.
            if (globalResult == null) {
                if (groupBySite) {
                    // v3.3: site-grouped view — a header per
                    // site (host, count, aggregate progress),
                    // foldable, folds remembered across
                    // launches. Single column even on wide
                    // screens so headers span the full width;
                    // tabs/sort already shaped the list above.
                    for (g in siteGroups) {
                        item(key = "site-" + g.host) {
                            Card(
                                Modifier.fillMaxWidth().clickable {
                                    collapsedSites =
                                        if (g.host in collapsedSites) {
                                            collapsedSites - g.host
                                        } else {
                                            collapsedSites + g.host
                                        }
                                    DownloadCenter.collapsedSites = collapsedSites
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            g.host,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val pct = g.progressPct
                                        Text(
                                            if (pct != null) {
                                                "${g.tasks.size} 个任务 · 总进度 $pct%"
                                            } else {
                                                "${g.tasks.size} 个任务"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (pct != null) {
                                            Spacer(Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = pct / 100f,
                                                modifier = Modifier.fillMaxWidth(),
                                                color = WinPalette.Green,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        if (g.host in collapsedSites) "展开" else "折叠",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (g.host !in collapsedSites) {
                            items(g.tasks, key = { it.id }) { task ->
                                TaskCardFor(task)
                            }
                        }
                    }
                } else if (wideScreen) {
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
                            Modifier.padding(top = 6.dp)
                                .horizontalScroll(rememberScrollState()),
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
                            "最近嗅过的网页：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (h in history.take(5)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    h,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        // v2.9 (audit 15): a bare text
                                        // link was a ~24 dp touch
                                        // target; give it the 48 dp
                                        // minimum like every button.
                                        .heightIn(min = 48.dp)
                                        .clickable { urlText = h }
                                        .padding(vertical = 16.dp),
                                )
                                // v5.7: one-tap re-sniff — runs the
                                // entry's saved page URL through the
                                // SAME startSniff a fresh paste uses
                                // (static fetch, intermediate-page
                                // tracking, result list, size probe
                                // all shared; see SniffHistory for
                                // the session/history semantics). A
                                // failure lands in `message` exactly
                                // like a fresh sniff's and leaves the
                                // entry and the old session untouched.
                                TextButton(
                                    onClick = {
                                        if (!sniffing) {
                                            startSniff(SniffHistory.resniffTarget(h))
                                        }
                                    },
                                ) { Text("重新嗅探") }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            // v2.5: the browser can only open a real
                            // address — decode a thunder envelope.
                            val rawU = urlText.trim()
                            val u = ThunderLink.decode(rawU) ?: rawU
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
                    TextButton(
                        onClick = {
                            txtImportLauncher.launch(
                                arrayOf("text/plain", "text/*")
                            )
                        }
                    ) { Text("从 .txt 文件导入链接（批量新建）") }
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
            .filter {
                SniffFilter.passesMinSize(it, sniffMinMB.toLong() * 1024 * 1024)
            }
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
                    // v4.3: the list is partial — the parse budget
                    // stopped early or the page had more resources
                    // than the shown cap. Say so up front.
                    if (sniffTruncated || sniffTotal > sniffList.size) {
                        Text(
                            "这个页面资源太多，只列出了一部分（先找到的排在前面）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    // ---- filter + sort (v1.4 item 15) ----
                    // v2.0: both rows scroll sideways — a page can
                    // have many resource kinds, and at max font
                    // scale even the sort row outgrows the dialog.
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (sniffKind.isBlank()) {
                            Button(onClick = { }) { Text("全部") }
                        } else {
                            OutlinedButton(onClick = {
                                sniffKind = ""
                                DownloadCenter.sniffFilterKind = ""
                            }) { Text("全部") }
                        }
                        for (k in kindsPresent) {
                            if (k == sniffKind) {
                                Button(onClick = { }) { Text(kindLabel(k)) }
                            } else {
                                OutlinedButton(onClick = {
                                    sniffKind = k
                                    DownloadCenter.sniffFilterKind = k
                                }) { Text(kindLabel(k)) }
                            }
                        }
                    }
                    // ---- v3.1: minimum-size filter (remembered
                    // across sniffs, like the kind above) ----
                    Row(
                        Modifier.padding(top = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "最小大小：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val minModes = listOf(
                            0 to "不限", 1 to "≥1MB", 10 to "≥10MB", 100 to "≥100MB",
                        )
                        for ((mb, label) in minModes) {
                            if (mb == sniffMinMB) {
                                Button(onClick = { }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = {
                                    sniffMinMB = mb
                                    DownloadCenter.sniffFilterMinMB = mb
                                }) { Text(label) }
                            }
                        }
                        Text(
                            "（大小未知的照常显示）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.padding(top = 6.dp)
                            .horizontalScroll(rememberScrollState()),
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
                        // Fixed spacer, not weight: inside a
                        // horizontally scrolling row a weighted
                        // spacer has no bounded width to fill.
                        Spacer(Modifier.size(8.dp))
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
                    // ---- v3.1: pre-commit estimate — what the
                    // ticked set will actually create, with the
                    // measured total; unmeasured resources are
                    // counted, never guessed into the sum. ----
                    if (sniffChecked.isNotEmpty()) {
                        val est = SniffEstimate.of(
                            sniffList.filter { it.url in sniffChecked }
                        )
                        Text(
                            "已选 ${est.count} 个：已知大小合计约 " +
                                Format.size(est.knownBytes) +
                                if (est.unknownCount > 0) {
                                    "（另有 ${est.unknownCount} 个大小未知，未计入）"
                                } else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // ---- v3.2: export this sniff session as a
                    // .txt list in the download folder — the
                    // checked resources when any are ticked,
                    // otherwise the ones currently shown (filters
                    // applied). Same format as the shared queue
                    // list, so the .txt import eats it back. ----
                    val exportRes = if (sniffChecked.isNotEmpty()) {
                        sniffList.filter { it.url in sniffChecked }
                    } else {
                        shownSniff
                    }
                    TextButton(
                        onClick = {
                            val text = SniffExport.build(exportRes)
                            Thread {
                                val saved = runCatching {
                                    PublicStorage.saveTextFile(
                                        context, SniffExport.FILE_NAME, text,
                                    )
                                }
                                activity.runOnUiThread {
                                    saved.onSuccess { name ->
                                        Toast.makeText(
                                            context,
                                            "已导出 $name 到下载目录（共 ${exportRes.size} 个链接），" +
                                                "用「从 .txt 文件导入」就能整批建回来",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            "导出失败：${Errors.human(it)}",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }.start()
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            if (sniffChecked.isNotEmpty()) {
                                "导出已选 ${exportRes.size} 个为 .txt 清单"
                            } else {
                                "导出当前列表 ${exportRes.size} 个为 .txt 清单"
                            }
                        )
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
                        // v5.4: batch summary names the duplicates
                        // it skipped (per-link toast quieted).
                        var dupSkipped = 0
                        for (res in chosen) {
                            when (createTask(res.url, sniffPage, quietDuplicate = true)) {
                                CreateOutcome.CREATED -> created++
                                CreateOutcome.DUPLICATE -> dupSkipped++
                                CreateOutcome.REJECTED -> Unit
                            }
                        }
                        if (chosen.isEmpty()) {
                            Toast.makeText(context, "先勾选要下载的资源", Toast.LENGTH_SHORT).show()
                        } else if (created > 0 || dupSkipped > 0) {
                            Toast.makeText(
                                context,
                                (if (created > 0) "已新建 $created 个下载任务" else "") +
                                    (if (dupSkipped > 0) {
                                        (if (created > 0) "，" else "") + "跳过了 $dupSkipped 个重复"
                                    } else ""),
                                Toast.LENGTH_SHORT,
                            ).show()
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

    // ---- v3.7: batch strategy-retry dialog (multi-select) ----
    // Shown only when the selection contains a FAILED task.
    // The pure decisions live in RetryStrategy; applying them
    // (headers / single-thread / enqueue) is
    // DownloadCenter.retryWithStrategy.
    // v5.3: the SAME dialog also serves the 异常 tab's batch
    // entry — retryTargetIds (the FAILED subset's ids) is then
    // the target source instead of the multi-selection. The
    // dialog body, RetryStrategy and retryWithStrategy are
    // unchanged; every exit below clears retryTargetIds so the
    // multi-select path never inherits a stale target set, and
    // the selection itself is cleared only on the multi-select
    // path, exactly as before.
    if (retryStrategyOpen) {
        val fromAbnormalTab = retryTargetIds != null
        val targetIds = retryTargetIds ?: selectedIds
        val failedPicked = store.tasks.filter {
            it.id in targetIds && it.status == TaskStatus.FAILED
        }
        // retryUaChoice: -1 = 不改 UA; 0..PRESETS.lastIndex picks that preset.
        val uaChoice = retryUaChoice
        val singleThread = retrySingleThread
        val canApply = uaChoice >= 0 || singleThread
        AlertDialog(
            onDismissRequest = {
                retryStrategyOpen = false
                retryTargetIds = null
            },
            title = { Text("换个方式重试（${failedPicked.size} 个失败任务）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "有些失败不是链接坏了，是「谈法」不对：\n" +
                            "· 换浏览器标识（UA）：有些服务器按 UA 区别对待——" +
                            "只认浏览器、防盗链挡下载工具，或者反过来。换个身份再谈一次。\n" +
                            "· 改单线程：有些服务器连接一多就不稳（断流、拒绝），" +
                            "一条连接慢慢下反而能下完。\n" +
                            "改完会统一重新排队，已下载的进度保留，不会白下。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "浏览器标识（UA）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { retryUaChoice = -1 },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = uaChoice == -1, onClick = { retryUaChoice = -1 })
                        Text("不改（沿用每个任务现在的）")
                    }
                    UserAgents.PRESETS.forEachIndexed { idx, (label, _) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { retryUaChoice = idx },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = uaChoice == idx, onClick = { retryUaChoice = idx })
                            Text(if (idx == 0) "$label（去掉任务自己的 UA）" else label)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("改用单线程重试")
                            Text(
                                "每个任务只开一条连接从头下，速度会慢一些",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = singleThread, onCheckedChange = { retrySingleThread = it })
                    }
                    if (!canApply) {
                        Text(
                            "先选至少一种改法（换 UA 或改单线程），不然和普通重试没区别。",
                            style = MaterialTheme.typography.bodySmall,
                            color = WinPalette.Red,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canApply,
                    onClick = {
                        val uaPreset =
                            if (uaChoice >= 0) UserAgents.PRESETS[uaChoice].second else null
                        val uaLabel =
                            if (uaChoice >= 0) UserAgents.PRESETS[uaChoice].first else ""
                        val n = DownloadCenter.retryWithStrategy(
                            failedPicked, uaPreset, uaLabel, singleThread,
                        )
                        retryStrategyOpen = false
                        retryTargetIds = null
                        if (!fromAbnormalTab) {
                            selectedIds = emptySet()
                            selecting = false
                        }
                        Toast.makeText(
                            context, "已让 $n 个失败任务换个方式重试",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) { Text("改好并重试") }
            },
            dismissButton = {
                TextButton(onClick = {
                    retryStrategyOpen = false
                    retryTargetIds = null
                }) { Text("取消") }
            },
        )
    }

    // ---- v3.2: batch rename dialog (multi-select) ----
    if (batchRenameOpen) {
        val picked = store.tasks.filter { it.id in selectedIds }
        val plan = BatchRename.plan(picked, batchFind, batchReplace, batchPrefix, batchSuffix)
        val willChange = plan.count { it.changed }
        val skipped = plan.count { !it.eligible }
        AlertDialog(
            onDismissRequest = { batchRenameOpen = false },
            title = { Text("批量改名（已选 ${picked.size} 个）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "改的是这些任务之后保存时用的文件名：查找替换是纯文字匹配" +
                            "（不是正则），后缀加在扩展名前面。正在下载和已完成的不会改；" +
                            "已经下好的文件也不会动。改完重名也没关系，保存时会自动加 (1)。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = batchFind,
                        onValueChange = { batchFind = it },
                        label = { Text("查找（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = batchReplace,
                        onValueChange = { batchReplace = it },
                        label = { Text("替换为（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = batchPrefix,
                            onValueChange = { batchPrefix = it },
                            label = { Text("前缀（可留空）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = batchSuffix,
                            onValueChange = { batchSuffix = it },
                            label = { Text("后缀（可留空）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "预览：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    for (p in plan.take(8)) {
                        Text(
                            when {
                                !p.eligible -> "${p.oldName}（正在下载或已完成，不改）"
                                p.changed -> "${p.oldName} → ${p.newName}"
                                else -> "${p.oldName}（不变）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (plan.size > 8) {
                        Text(
                            "……还有 ${plan.size - 8} 个，规则相同",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        var n = 0
                        for (p in plan) {
                            if (!p.changed) continue
                            val t = store.byId(p.id) ?: continue
                            t.fileName = p.newName
                            n++
                        }
                        if (n > 0) store.saveNow()
                        batchRenameOpen = false
                        selectedIds = emptySet()
                        selecting = false
                        Toast.makeText(
                            context,
                            when {
                                n > 0 && skipped > 0 ->
                                    "已改名 $n 个；$skipped 个正在下载或已完成，没改"
                                n > 0 -> "已改名 $n 个"
                                skipped > 0 -> "选中的都在下载中或已完成，没改"
                                else -> "没有名字发生变化"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    enabled = plan.isNotEmpty(),
                ) { Text("改名（$willChange 个）") }
            },
            dismissButton = {
                TextButton(onClick = { batchRenameOpen = false }) { Text("取消") }
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
                // v2.9 (audit 15): editor dialogs scroll their
                // content — at 1.3x font scale on a small phone
                // the un-scrolled column could push the buttons
                // off the bottom of the dialog.
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    // v5.4: per-mirror state, read from the verdicts
                    // the engine's last validation pass RECORDED on
                    // the task — opening this dialog probes nothing.
                    if (st.sources.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "每个镜像的状态（来自上次下载时的校验记录）：",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        for (src in st.sources) {
                            val decision = MirrorAccess.decision(
                                st.sourceVerdicts[src], src in mirrorOverridesUi,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                src,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (decision) {
                                    MirrorAccess.Decision.USABLE_CONFIRMED ->
                                        "已确认：是同一个文件，会参与下载。"
                                    MirrorAccess.Decision.USABLE_OVERRIDDEN ->
                                        "待确认（可能需要登录）：你已手动放行，会参与下载。"
                                    MirrorAccess.Decision.BLOCKED_UNCONFIRMED ->
                                        "待确认：没能确认是不是同一个文件（可能需要登录），先不用。"
                                    MirrorAccess.Decision.BLOCKED_DIFFERENT ->
                                        "已拒绝：已经证明是别的文件，不能放行。"
                                    MirrorAccess.Decision.BLOCKED_UNCHECKED ->
                                        "还没校验：开始下载时会先校验，确认了才用。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (decision ==
                                MirrorAccess.Decision.BLOCKED_UNCONFIRMED
                            ) {
                                Text(
                                    "放行就是没确认也用它：万一它其实是别的文件，" +
                                        "可能会把文件下坏；下载完的哈希校验还会再兜底检查一次。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = {
                                    synchronized(st.sourceOverrides) {
                                        st.sourceOverrides.add(src)
                                    }
                                    mirrorOverridesUi = mirrorOverridesUi + src
                                    store.save()
                                }) { Text("仍然使用这个镜像") }
                            }
                            if (decision ==
                                MirrorAccess.Decision.USABLE_OVERRIDDEN
                            ) {
                                TextButton(onClick = {
                                    synchronized(st.sourceOverrides) {
                                        st.sourceOverrides.remove(src)
                                    }
                                    mirrorOverridesUi = mirrorOverridesUi - src
                                    store.save()
                                }) { Text("取消放行") }
                            }
                        }
                    }
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
                    // v5.4: a mirror removed from the list takes
                    // its recorded verdict and any override with
                    // it — no orphan records left behind.
                    synchronized(st.sourceVerdicts) {
                        val prunedV = MirrorAccess.prunedVerdicts(
                            st.sourceVerdicts, st.sources,
                        )
                        st.sourceVerdicts.clear()
                        st.sourceVerdicts.putAll(prunedV)
                    }
                    synchronized(st.sourceOverrides) {
                        val prunedO = MirrorAccess.prunedOverrides(
                            mirrorOverridesUi, st.sources,
                        )
                        st.sourceOverrides.clear()
                        st.sourceOverrides.addAll(prunedO)
                        mirrorOverridesUi = prunedO
                    }
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
        // Audit round 43: parse what release pages actually
        // print (algorithm prefix / whole "<hash>  filename"
        // line), not just a bare hex string.
        val parsed = Checksum.parseExpected(hashText)
        val detected = parsed?.first
        AlertDialog(
            onDismissRequest = { hashTask = null },
            title = { Text("设置校验哈希") },
            text = {
                Column {
                    Text(
                        "把发布页给的哈希值粘进来：64 位自动认成 SHA-256，32 位认成 MD5。" +
                            "带「sha256:」这样的前缀、或者连文件名一整行粘进来也认得。" +
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
                        color = if (detected != null) statusTextGreen()
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsedNow = Checksum.parseExpected(hashText)
                    val kind = parsedNow?.first
                    if (parsedNow == null || kind == null) {
                        Toast.makeText(
                            context,
                            "没认出来：要填 32 位（MD5）或 64 位（SHA-256）的十六进制哈希",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@TextButton
                    }
                    val hex = parsedNow.second
                    if (kind == Checksum.SHA256) {
                        ht.expectedSha256 = hex
                        ht.expectedMd5 = ""
                    } else {
                        ht.expectedMd5 = hex
                        ht.expectedSha256 = ""
                    }
                    // v5.8: this value is hand-typed now, whatever
                    // a sidecar may have supplied before.
                    ht.checksumFromSidecar = false
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
                            if (DownloadCenter.autoVerifyHash) {
                                "已保存，下完会自动校验"
                            } else {
                                "已保存；自动校验是关的，下完后在详情里点「立即校验」"
                            },
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
                            ht.checksumFromSidecar = false
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

    // ---- HLS variant picker (v3.8) ----
    if (hlsPick != null) {
        val pick = hlsPick!!
        AlertDialog(
            onDismissRequest = { hlsPick = null },
            title = { Text("选择清晰度") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "这个链接是一个流媒体主播放列表，里面有 ${pick.variants.size} 档清晰度。" +
                            "选一档就按这一档下载（合并成一个 .ts 文件）；" +
                            "之后想换一档，删掉任务重新新建即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    pick.variants.forEach { v ->
                        OutlinedButton(
                            onClick = {
                                hlsPick = null
                                if (createTask(
                                        v.url, pick.referer, pick.fileName,
                                        pick.mirrors, pick.daily,
                                    ) == CreateOutcome.CREATED
                                ) {
                                    // The task must be an HLS task
                                    // even when the variant URL does
                                    // not end in .m3u8, and the card
                                    // should say which quality was
                                    // chosen (the engine's resume
                                    // fingerprint covers THIS
                                    // playlist's segments).
                                    store.tasks.firstOrNull { it.url == v.url }?.let { t ->
                                        t.isHls = true
                                        val label =
                                            com.tuxundown.android.core.Hls.variantLabel(v)
                                        t.note = if (t.note.isBlank()) {
                                            "已选清晰度：$label"
                                        } else {
                                            t.note + "（已选清晰度：$label）"
                                        }
                                        store.save()
                                    }
                                    pick.onCreated()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                com.tuxundown.android.core.Hls.variantLabel(v),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { hlsPick = null }) { Text("取消") }
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
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

    // ---- user-note editor dialog (v2.8) ----
    if (noteTask != null) {
        val nt2 = noteTask!!
        AlertDialog(
            onDismissRequest = { noteTask = null },
            title = { Text("备注") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "给这个任务写一句你自己的备注，方便以后找——比如「给娃下的课件」。" +
                            "备注只存在这台手机里，跟着任务一起进备份；主页搜索框里搜备注内容也能找到它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = {
                            noteText = it.take(LinkSchemes.MAX_USER_NOTE_CHARS)
                        },
                        label = { Text("备注（留空保存 = 清除）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DownloadCenter.setUserNote(nt2, noteText)
                    noteTask = null
                    Toast.makeText(
                        context,
                        if (nt2.userNote.isBlank()) "备注已清除" else "备注已保存",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteTask = null }) { Text("取消") }
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
    } // v2.1 safe-area Box
}

/**
 * v5.4: the outcome of the home screen's single-create funnel
 * (see CreateDedup for the dedup verdict behind DUPLICATE).
 * Batch callers count DUPLICATE outcomes and report them in
 * their summary; single-create callers let the funnel's own
 * toast speak.
 */
enum class CreateOutcome { CREATED, DUPLICATE, REJECTED }

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
fun StatsScreen(onBack: () -> Unit, onSpeedTest: () -> Unit, tick: Int) {
    // v4.0: tick exists so this screen subscribes to DownApp's 1 s
    // tick and its parameters change every second, so Compose
    // cannot skip it. Totals only move when a task completes, but
    // without this a user sitting on the stats screen (whose other
    // parameters are two stable lambdas) would never see a
    // completion land; this (otherwise no-op) read fixes that.
    @Suppress("UNUSED_EXPRESSION") tick
    val context = LocalContext.current
    // v3.4: the report is built at tap time from the live
    // counters + the records still on the device (see
    // DownloadReport for the honesty rules).
    fun reportText(): String = com.tuxundown.android.core.DownloadReport.build(
        DownloadCenter.store.tasks,
        com.tuxundown.android.core.DownloadReport.Counters(
            DownloadCenter.statsTotalBytes(),
            DownloadCenter.statsDoneCount(),
            DownloadCenter.statsAvgBps(),
        ),
        DownloadCenter.hostStatsTop(),
        System.currentTimeMillis(),
    )
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
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    DownloadCenter.clearHostMemory()
                    refresh++
                }) { Text("清除按主机学习数据") }
                Text(
                    "每个站点记住的最佳连接数和限流上限都会清掉，下次下载重新试探；只存本机，不上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item(key = "st-week") {
            SettingsGroup("最近 7 天下载量") {
                val week = DownloadCenter.statsLastDays(7)
                if (week.all { it.bytes <= 0 }) {
                    Text(
                        "还没有记录。下完文件之后，这里会一天一天长起来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    WeekChart(
                        days = week,
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                }
                Text(
                    "按天记录从 v2.7 才开始：今天的量是完整的，前面几天要等新版本" +
                        "一天天记满才会长齐，不会拿旧数据糊弄。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item(key = "st-hosts") {
            SettingsGroup("下载最多的站点（本机统计）") {
                val hosts = DownloadCenter.hostStatsTop()
                if (hosts.isEmpty()) {
                    Text(
                        "还没有完成的下载，下完第一个文件这里就会开始排行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    hosts.forEachIndexed { i, h ->
                        InfoRow(
                            "第 ${i + 1} 名：",
                            "${h.host} · ${Format.size(h.bytes)} · ${h.count} 个文件",
                        )
                    }
                }
            }
        }
        item(key = "st-report") {
            SettingsGroup("下载报告") {
                Text(
                    "把本月和全部的下载情况拼成一段文字，可以复制或分享给别人看。" +
                        "数字全部来自这台手机上的统计和任务记录，现算现拼；" +
                        "没有数据的部分直接省略，不编。报告只在本机生成，不上传。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val cm = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE,
                        ) as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "TuxunDown 下载报告", reportText(),
                            ),
                        )
                        Toast.makeText(context, "报告已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制报告") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, reportText())
                        }
                        context.startActivity(
                            Intent.createChooser(send, "分享下载报告"),
                        )
                    }) { Text("分享报告") }
                }
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

    // ---- v3.1: duplicate finder — phase 1 groups by exact
    // size (free), phase 2 streams only those candidates
    // through SHA-256 on a background thread (cancellable).
    // Read-only until the user ticks copies and confirms. ----
    var dupScanning by remember { mutableStateOf(false) }
    var dupProgress by remember { mutableStateOf("") }
    var dupGroups by remember { mutableStateOf<List<DuplicateFinder.Group>?>(null) }
    var dupChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dupConfirm by remember { mutableStateOf(false) }
    val dupCancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun startDupScan() {
        dupGroups = null
        dupChecked = emptySet()
        dupCancelFlag.set(false)
        dupScanning = true
        dupProgress = "正在按大小分组…"
        Thread {
            val candidates = DuplicateFinder.sizeCandidates(entries)
            val hashes = mutableMapOf<String, String>()
            var done = 0
            for (e in candidates) {
                if (dupCancelFlag.get()) break
                done++
                val label = "正在校验第 $done / ${candidates.size} 个同大小文件…"
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    dupProgress = label
                }
                val h = runCatching {
                    context.contentResolver.openInputStream(Uri.parse(e.uri))?.use {
                        Checksum.digestCancellable(it, Checksum.SHA256) {
                            !dupCancelFlag.get()
                        }
                    }
                }.getOrNull()
                if (h != null) hashes[e.uri] = h
            }
            val cancelled = dupCancelFlag.get()
            val groups = if (cancelled) null
                else DuplicateFinder.confirmedGroups(candidates, hashes)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                dupScanning = false
                dupProgress = ""
                if (groups != null) dupGroups = groups
            }
        }.start()
    }

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
                // ---- v3.1: duplicate finder controls ----
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dupScanning) {
                        Text(
                            dupProgress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { dupCancelFlag.set(true) }) {
                            Text("取消")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { startDupScan() },
                            enabled = !loading && entries.size >= 2,
                        ) { Text("找重复文件") }
                        if (dupGroups != null) {
                            TextButton(onClick = {
                                dupGroups = null
                                dupChecked = emptySet()
                            }) { Text("收起结果") }
                        }
                    }
                }
            }
        }
        // ---- v3.1: duplicate results ----
        val dGroups = dupGroups
        if (dGroups != null) {
            item(key = "f-dup-head") {
                val plan = DuplicateFinder.deletionPlan(dGroups, dupChecked)
                Column {
                    Text(
                        if (dGroups.isEmpty()) {
                            "没有发现重复文件（大小相同、内容也完全一样的文件一组都没有）。"
                        } else {
                            "找到 ${dGroups.size} 组重复文件，每组只留一份" +
                                "可回收 ${Format.size(DuplicateFinder.totalReclaimable(dGroups))}。" +
                                "勾选要删的副本，最后统一确认——不勾就不会删。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dupChecked.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { dupConfirm = true }) {
                            Text(
                                "删除所选 ${dupChecked.size} 个" +
                                    "（可回收 ${Format.size(plan.freedBytes)}）"
                            )
                        }
                    }
                }
            }
            dGroups.forEachIndexed { gi, g ->
                item(key = "f-dup-g$gi") {
                    Text(
                        "第 ${gi + 1} 组 · 每份 ${Format.size(g.size)} · 共 ${g.entries.size} 份" +
                            "（留一份可回收 ${Format.size(g.reclaimableBytes)}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(g.entries, key = { "f-dup-m" + it.uri }) { e ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dupChecked = if (e.uri in dupChecked) {
                                        dupChecked - e.uri
                                    } else {
                                        dupChecked + e.uri
                                    }
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = e.uri in dupChecked,
                                onCheckedChange = { checked ->
                                    dupChecked = if (checked) {
                                        dupChecked + e.uri
                                    } else {
                                        dupChecked - e.uri
                                    }
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    (if (e.modifiedAt > 0) {
                                        Format.dateTime(e.modifiedAt)
                                    } else "时间未知") +
                                        if (e.taskId.isBlank()) " · 仅剩文件（记录已删）" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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

    // ---- v3.1: duplicate deletion — same rule as the single
    // delete below (file AND its record go together), spelled
    // out up front, with an extra warning when a ticked group
    // would lose its LAST copy. ----
    if (dupConfirm) {
        val groupsNow = dupGroups ?: emptyList()
        val plan = DuplicateFinder.deletionPlan(groupsNow, dupChecked)
        val picked = entries.filter { it.uri in dupChecked }
        AlertDialog(
            onDismissRequest = { dupConfirm = false },
            title = { Text("删除这些重复副本？") },
            text = {
                Text(
                    "将删除 ${picked.size} 个文件，共回收 ${Format.size(plan.freedBytes)}。" +
                        "有任务记录的文件会连记录一起清掉，删了不能恢复。\n" +
                        if (plan.wipedGroups > 0) {
                            "⚠ 其中 ${plan.wipedGroups} 组的所有副本都被勾选了——" +
                                "删完这组文件就一份都不剩了，请确认不是误勾。"
                        } else {
                            "每组至少还留了一份，没勾的副本不受影响。"
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dupConfirm = false
                    val targets = picked.toList()
                    Thread {
                        var okCount = 0
                        for (e in targets) {
                            if (PublicStorage.deleteByUri(context, e.uri)) okCount++
                            if (e.taskId.isNotBlank()) {
                                DownloadCenter.store.byId(e.taskId)?.let {
                                    DownloadCenter.store.remove(it)
                                }
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(
                                context,
                                "已删除 $okCount 个重复文件",
                                Toast.LENGTH_SHORT,
                            ).show()
                            dupGroups = null
                            dupChecked = emptySet()
                            reloadKey++
                        }
                    }.start()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { dupConfirm = false }) { Text("取消") }
            },
        )
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
                        if (task != null) {
                            ChecksumVerifier.requestCancel(task.id)
                            DownloadCenter.store.remove(task)
                        }
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
                            ChecksumVerifier.requestCancel(task.id)
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
    var singleWindowMs by remember { mutableStateOf(0L) }
    var multiWindowMs by remember { mutableStateOf(0L) }
    var testRef by remember { mutableStateOf<SpeedTest?>(null) }
    // ---- v3.2: multi-point comparison state ----
    var compareRunning by remember { mutableStateOf(false) }
    var comparePhase by remember { mutableStateOf("") }
    var compareResults by remember {
        mutableStateOf<List<SpeedCompare.PointResult>>(emptyList())
    }
    var showCompareConfirm by remember { mutableStateOf(false) }
    // v5.9 audit round 44: RUNNING tasks at the moment a
    // quick test or multi-point run STARTED (read once in
    // startTest / startCompare) — the same start-moment
    // snapshot the connection comparison has shown since
    // v4.3 (see ConnCompare.runningTasksNote). Neither test
    // pauses or budget-shares running downloads; they contend
    // for the same network, so a run started mid-download
    // reads low and the results must say so.
    var stRunningAtStart by remember { mutableStateOf(0) }
    // ---- v4.1: quick-test history (last 10, local only) ----
    // Held as Compose state and RE-READ from the store when a
    // test finishes (see startTest's final post): the fresh list
    // instance makes the history item recompose immediately —
    // the v4.0 liveTick lesson (in-place mutation of an equal
    // list would be skipped as "unchanged").
    var history by remember { mutableStateOf(DownloadCenter.speedHistory()) }
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
        // Audit round 44: snapshot the contention at the start
        // moment only (see ConnCompare.runningTasksNote).
        stRunningAtStart = DownloadCenter.store.tasks.count {
            it.status == TaskStatus.RUNNING
        }
        running = true
        phase = "准备测速…"
        endpointLabel = ""
        liveBps = 0
        singleBps = -1
        multiBps = -1
        singleWindowMs = 0
        multiWindowMs = 0
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
                if (s.bps <= 0) continue // this endpoint moved nothing: try the next
                post {
                    singleBps = s.bps
                    singleWindowMs = s.windowMs
                    phase = "多线程测速中（4 条连接）…"
                    liveBps = 0
                }
                val m = test.runPhase(ep.url, 4, SpeedTest.PHASE_MS) { b ->
                    post { liveBps = b }
                }
                if (!test.cancelled) {
                    // v4.1: a completed quick test (both phases
                    // moved bytes AND a verdict was reached —
                    // verdict() is null otherwise) joins the
                    // local history. The multi-point comparison
                    // below deliberately does NOT record: its
                    // phases run shorter and cover dead points
                    // too, a different口径 whose numbers are not
                    // comparable with a quick test's.
                    val v = SpeedVerdict.verdict(s.bps, m.bps)
                    if (v != null) {
                        DownloadCenter.recordSpeedTest(
                            SpeedHistory.Entry(
                                System.currentTimeMillis(),
                                ep.label, s.bps, m.bps, v,
                            ),
                        )
                    }
                    post {
                        multiBps = m.bps
                        multiWindowMs = m.windowMs
                    }
                    finished = true
                }
                break
            }
            post {
                running = false
                // v4.1: re-read the history so a just-recorded
                // entry shows up the moment the test completes.
                history = DownloadCenter.speedHistory()
                phase = when {
                    test.cancelled -> "已停止"
                    finished -> "测速完成"
                    else -> "测速失败：测速点都连不上，检查一下网络再试"
                }
            }
        }.start()
    }
    // v3.2: multi-point comparison — EVERY endpoint in turn
    // (no early exit: a dead point is a result too, recorded as
    // 没测通）, single phase then multi phase per point, results
    // appended live so the chart grows point by point.
    fun startCompare() {
        if (running || compareRunning) return
        // Audit round 44: same start-moment snapshot as the
        // quick test (see stRunningAtStart).
        stRunningAtStart = DownloadCenter.store.tasks.count {
            it.status == TaskStatus.RUNNING
        }
        compareRunning = true
        compareResults = emptyList()
        comparePhase = "准备多点对比…"
        liveBps = 0
        val test = SpeedTest()
        testRef = test
        Thread {
            val out = mutableListOf<SpeedCompare.PointResult>()
            val total = SpeedTest.ENDPOINTS.size
            for ((idx, ep) in SpeedTest.ENDPOINTS.withIndex()) {
                if (test.cancelled) break
                post {
                    comparePhase = "第 ${idx + 1}/$total 个测速点：${ep.label} · 单线程…"
                    liveBps = 0
                }
                val s = test.runPhase(ep.url, 1, SpeedCompare.PHASE_MS) { b ->
                    post { liveBps = b }
                }
                if (test.cancelled) break
                post {
                    comparePhase =
                        "第 ${idx + 1}/$total 个测速点：${ep.label} · 多线程（4 条连接）…"
                    liveBps = 0
                }
                val m = test.runPhase(
                    ep.url, SpeedCompare.MULTI_CONNECTIONS, SpeedCompare.PHASE_MS,
                ) { b -> post { liveBps = b } }
                if (test.cancelled) break
                out.add(SpeedCompare.PointResult(ep.label, s.bps, m.bps))
                val snapshot = out.toList()
                post { compareResults = snapshot }
            }
            post {
                compareRunning = false
                comparePhase = if (test.cancelled) "已停止" else "多点对比完成"
                liveBps = 0
            }
        }.start()
    }
    // v3.2: the comparison burns real data on three networks —
    // the totals are stated and consented BEFORE anything runs.
    if (showCompareConfirm) {
        val band = SpeedCompare.dataBandMB(SpeedTest.ENDPOINTS.size)
        AlertDialog(
            onDismissRequest = { showCompareConfirm = false },
            title = { Text("多点对比测速") },
            text = {
                Text(
                    "会依次测 ${SpeedTest.ENDPOINTS.size} 个测速点，每个点测单线程和多线程，" +
                        "全程约 ${SpeedCompare.totalSeconds(SpeedTest.ENDPOINTS.size)} 秒，" +
                        "大约消耗 ${band.first}–${band.last} MB 流量（网速越快用得越多）。" +
                        "建议在 WiFi 下测；用流量测请先想好这个量。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCompareConfirm = false
                    startCompare()
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showCompareConfirm = false }) { Text("取消") }
            },
        )
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
                // Audit round 44: the start-moment contention
                // snapshot, shown while a run is going and with
                // its results (the wording lives in ConnCompare
                // so it is tested — the same sentence the
                // connection comparison uses).
                val stNote = ConnCompare.runningTasksNote(stRunningAtStart)
                if (stNote.isNotBlank() &&
                    (running || compareRunning || singleBps >= 0 || compareResults.isNotEmpty())
                ) {
                    Text(
                        stNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
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
                    InfoRow(
                        "单线程：",
                        "${Format.speed(singleBps)}（稳态实测约 " +
                            "${"%.1f".format(singleWindowMs / 1000.0)} 秒）",
                    )
                }
                if (multiBps >= 0) {
                    InfoRow(
                        "多线程（4 条）：",
                        "${Format.speed(multiBps)}（稳态实测约 " +
                            "${"%.1f".format(multiWindowMs / 1000.0)} 秒）",
                    )
                }
                if (singleBps > 0 && multiBps > 0) {
                    val ratio = multiBps.toDouble() / singleBps.toDouble()
                    val verdictText = when (SpeedVerdict.verdict(singleBps, multiBps)) {
                        SpeedVerdict.Verdict.FASTER ->
                            "多线程约为单线程的 ${Format.ratio(ratio)} 倍——" +
                                "连接数加上去确实能提速，这正是本 App 多线程下载的原理"
                        SpeedVerdict.Verdict.SIMILAR ->
                            "这个测速点单条连接就把速度给够了，多开连接占不到便宜。" +
                                "这不代表多线程下载没用——有些站是按单条连接限速的，" +
                                "每条连接只给一点速度，在那种站上多线程能成倍提速。"
                        SpeedVerdict.Verdict.SLOWER ->
                            "多线程反而比单线程慢——这个测速点可能是按连接分速度的，" +
                                "或者连接建立的开销占了大头。实际下载遇到这类站时，" +
                                "把连接数调低反而更快。"
                        null -> ""
                    }
                    if (verdictText.isNotEmpty()) {
                        InfoRow("对比：", verdictText)
                    }
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
        // ---- v3.2: multi-point comparison ----
        item(key = "sp-compare") {
            SettingsGroup("多点对比") {
                Text(
                    "把三个测速点挨个测一遍，放在一起看。先说清：三个点在不同的网络上" +
                        "（全球测速服务、镜像站、CDN 测试文件），到你手机的线路各不相同，" +
                        "所以数字只能看个大概，不能直接比「哪个服务器快」——" +
                        "同一个点里单线程和多线程的对比，才是这里真正有意义的部分。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (comparePhase.isNotBlank()) {
                    Text(
                        comparePhase,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (compareRunning) {
                    Text(
                        Format.speed(liveBps),
                        color = WinPalette.ChartGreen,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (compareResults.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SpeedCompareChart(
                        results = compareResults,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                    Text(
                        "浅绿＝单线程，深绿＝多线程；柱子按最快的那个值统一比例。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    compareResults.forEachIndexed { i, r ->
                        if (r.reached) {
                            InfoRow("${i + 1}. ${r.label} · 单线程：", Format.speed(r.singleBps))
                            InfoRow("${i + 1}. ${r.label} · 多线程：", Format.speed(r.multiBps))
                        } else {
                            InfoRow("${i + 1}. ${r.label}：", "没测通（这个点连不上或没给数据）")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (compareRunning) {
                        OutlinedButton(onClick = { testRef?.cancel() }) { Text("停止") }
                    } else {
                        Button(
                            onClick = { showCompareConfirm = true },
                            enabled = !running,
                        ) {
                            Text(if (compareResults.isEmpty()) "多点对比测速" else "再对比一次")
                        }
                    }
                }
            }
        }
        // ---- v4.1: quick-test history (last 10, this device
        // only — never part of a backup; the multi-point
        // comparison above does not record here, different口径).
        // The whole section hides itself while there is nothing
        // to show.
        if (history.isNotEmpty()) {
            item(key = "sp-history") {
                SettingsGroup("测速历史（最近 ${history.size} 次）") {
                    Text(
                        "只记上面的快速测速，多点对比不记（口径不同，混在一起会误导）。" +
                            "记录只存这台手机，不进备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    history.forEach { e ->
                        Text(
                            "${Format.dateTime(e.atMs)} · ${e.pointLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "单线程 ${Format.speed(e.singleBps)} · " +
                                "多线程 ${Format.speed(e.multiBps)} · " +
                                SpeedHistory.verdictShort(e.verdict),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            DownloadCenter.clearSpeedHistory()
                            history = DownloadCenter.speedHistory()
                        }) { Text("清空历史") }
                    }
                }
            }
        }
    }
}

/**
 * v3.2: grouped bar chart for the multi-point comparison, in
 * the same hand-drawn style as the weekly chart (green bars,
 * grid, border, canvas labels). Two bars per point — pale for
 * single-thread, full green for multi — on ONE shared scale
 * (SpeedCompare.barFractions), point numbers under the columns;
 * the rows under the chart map numbers to point names.
 */
@androidx.compose.runtime.Composable
fun SpeedCompareChart(
    results: List<SpeedCompare.PointResult>,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val singleColor = Color(0xFF9CCB9C)
    val multiColor = WinPalette.ChartGreen
    val gridColor = if (dark) Color(0xFF3A3A3A) else Color(0xFFDDDDDD)
    val borderColor = if (dark) Color(0xFF4A4A4A) else Color(0xFFBDBDBD)
    val bg = if (dark) Color(0xFF202020) else Color(0xFFFCFEFC)
    val fractions = SpeedCompare.barFractions(results)
    val fastest = results.maxOfOrNull { maxOf(it.singleBps, it.multiBps) } ?: 0
    val topLabel = if (fastest > 0) "最快 ${Format.speed(fastest)}" else "还没有结果"
    val labelColor = if (dark) 0xFFAAAAAA.toInt() else 0xFF767676.toInt()
    Canvas(modifier.background(bg)) {
        val w = size.width
        val h = size.height
        val labelH = 14.dp.toPx()
        val bottomH = 12.dp.toPx()
        val plotTop = labelH
        val plotH = h - labelH - bottomH
        for (i in 0..4) {
            val y = plotTop + plotH * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        drawRect(
            borderColor,
            topLeft = Offset(0f, plotTop),
            size = Size(w, plotH),
            style = Stroke(width = 1f),
        )
        val paint = android.graphics.Paint().apply {
            color = labelColor
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(topLabel, 4.dp.toPx(), 11.dp.toPx(), paint)
        if (results.isEmpty()) return@Canvas
        val colW = w / results.size
        val barW = colW * 0.28f
        val gap = colW * 0.06f
        fractions.forEachIndexed { i, (sf, mf) ->
            val groupW = barW * 2 + gap
            val gx = i * colW + (colW - groupW) / 2f
            val sH = plotH * sf
            if (sH > 0f) {
                drawRect(
                    singleColor,
                    topLeft = Offset(gx, plotTop + plotH - sH),
                    size = Size(barW, sH),
                )
            }
            val mH = plotH * mf
            if (mH > 0f) {
                drawRect(
                    multiColor,
                    topLeft = Offset(gx + barW + gap, plotTop + plotH - mH),
                    size = Size(barW, mH),
                )
            }
            val label = "${i + 1}"
            val tw = paint.measureText(label)
            drawContext.canvas.nativeCanvas.drawText(
                label, i * colW + (colW - tw) / 2f, h - 2.dp.toPx(), paint,
            )
        }
    }
}

/**
 * Help center (v2.0): fully offline FAQ. Every answer is 3–5
 * plain-language sentences, no jargon without an explanation,
 * and the speed claims follow the project's honesty rule —
 * where a server or the user's own bandwidth is the ceiling,
 * the text says so instead of promising miracles.
 */
@androidx.compose.runtime.Composable
fun HelpScreen(onBack: () -> Unit) {
    val faqs = listOf(
        "为什么这里下载比浏览器快？" to
            "浏览器下载一个文件通常只开一条连接，服务器对每条连接单独限速时，它就只能跑那个速度。\n" +
            "TuxunDown 会把文件切成很多小块，开多条连接同时下，最后再拼回成一个完整文件。\n" +
            "每条连接都拿到一份限速的话，总速度就能叠上去，所以在很多服务器上会比浏览器快几倍甚至十几倍。\n" +
            "App 还会自动试探开多少条连接最合适：新任务先 16 条爆发开局，再按实际速度加减，速度不再涨就收下来，不瞎开；单个任务最多 64 条。",
        "为什么有时候就是快不了？" to
            "有些服务器是按总量限速的：不管你开几条连接，加起来就给这么多，这种情况谁来下都一样快不了。\n" +
            "还有你的宽带本身就是上限，比如家里宽带总共就这么快，浏览器已经跑满了，这里也变不出更多。\n" +
            "遇到这两种情况，任务卡片上会明说原因，不会假装还在提速。\n" +
            "主页「统计」里的测速页能测你现在的网络到底能跑多快，可以拿它当参考。",
        "「单线程」是什么意思？" to
            "分片多线程下载有个前提：服务器得支持「从中间开始下」（专业说法叫 Range 请求）。\n" +
            "有些老服务器不支持，文件只能从头一条道下到底，这就叫单线程，速度跟浏览器差不多。\n" +
            "这不是出错，任务卡片上会写明为什么退回单线程。\n" +
            "单线程也一样支持暂停和续传，只是不能靠加连接提速。",
        "双通道要什么条件？" to
            "双通道是让 WiFi 和手机流量两条网络一起下载同一个文件，两边速度叠加。\n" +
            "条件是：手机流量开着、有信号，而且系统愿意把流量网络分配给 App 用。\n" +
            "在设置里打开开关后，如果当前环境拿不到流量网络，任务卡片上会明说「双通道没开成」和原因，不会假装开着。\n" +
            "注意它会实实在在消耗流量；另外和「仅 WiFi 下载」同时开时，以「仅 WiFi」为准，双通道不启用。",
        "流量保护是什么？" to
            "这是防手滑烧流量的：在设置里选一个大小门槛（比如 200 MB），用流量下载超过这个大小的文件时，任务会先暂停并写明原因。\n" +
            "你确认就是这个文件、愿意花流量的话，在任务卡片上点一次「继续」就行，这一次确认只对这一个任务有效。\n" +
            "在 WiFi 下不受这个限制；门槛设为「关」就完全不拦。",
        "下好的文件在哪？" to
            "默认都在手机公共的「下载」目录里，就是文件管理器打开就能看到的那个下载文件夹的第一层，不藏在 App 自己的角落里。\n" +
            "没下完的文件也在那里，文件名后面带 .part，下完会自动改成正式名字。\n" +
            "如果你在设置里改过下载目录，那就是你选的那个文件夹。\n" +
            "App 里主页的「已下载文件」页也能看到全部下好的文件，可以直接打开、分享、删除。",
        "暂停、关机以后，续传靠什么接着下？" to
            "文件被切成很多小块，每下完一块都会记下来：哪块好了、哪块还没下，这个记录存在手机里。\n" +
            "所以暂停、退出 App、甚至关机重启，再点「继续」都是从没下完的地方接着下，不用从头来。\n" +
            "继续之前还会跟服务器对一下文件的「身份证」（大小和更新标记），确认服务器上的文件没换过才敢接着拼。\n" +
            "万一文件被服务器换掉了，会从头重下并在卡片上说明，不会把两个文件拼错。",
        "预约下载、每天定时，准时吗？" to
            "到点开始靠两层：下载服务在后台醒着等（通知栏会显示「预约下载待机」），到点就开下；另外还有一个系统闹钟在最早的预约时间上兜底，服务不在时负责把它叫起来。\n" +
            "实话实说：闹钟是不精确的那种（精确闹钟要多一个系统权限，这里没用它），省电模式下系统还可能把兜底往后推，所以偶尔晚几分钟是可能的。\n" +
            "如果 App 被从最近任务里划掉杀掉、或手机重启后一直没打开过 App，后台等的那层就没了，只能靠兜底闹钟，甚至要等下次打开 App——但任务不会丢：一打开就立刻补上，只是晚开始。\n" +
            "想尽量准时：到设置 → 后台运行，把电池设为「不限制」，再在最近任务里把 TuxunDown 的卡片锁定。每天定时的任务下完（或失败）后会自动排到第二天同一时间，不用每天重设。",
        "安卓 15 说后台下载时间用满了，是怎么回事？" to
            "安卓 15 给「后台下载」这类服务加了一个每天的时长上限：App 一直在后台时，这类服务累计跑满约 6 小时（按 24 小时算），系统就会把它停掉，防止任何 App 在后台无限跑。\n" +
            "轮到 TuxunDown 时，正在下的任务会被自动暂停，进度全都存好了，任务卡片上会写明是系统额度用满停的，不是出错、更不是下完了。你点「继续」就能接着下。\n" +
            "把 App 打开到前台，这个计时会重新算；平时正常下载很少会碰到这个上限，主要是整夜连续下很多大文件时才可能。\n" +
            "另外同样是安卓 15 的规矩：开机广播不能再直接启动下载服务，所以开了「开机后自动恢复」的手机，重启后要等你第一次打开 App 才会补上恢复，任务不会丢。",
        "下完能自动解压吗？" to
            "能，只解 zip。在设置 → 提示里打开「下完自动解压 zip 压缩包」（默认关），.zip 文件下完会自动解压到它所在的文件夹里、一个和压缩包同名的文件夹中，原 zip 不会删。\n" +
            "如果任务填了校验值，要校验对上才会解压——对不上的文件可能已经坏了，不会替你拆开。\n" +
            "安全上有两道防线：解压有文件数和解开后总大小的上限（防「压缩包炸弹」）；压缩包里要是藏了想跳出目标文件夹的路径，会整包停下并在卡片上说明，解出一半的文件会清掉。\n" +
            "rar、7z 等其他格式不支持，不会动它们；进度和结果都写在任务的完成说明里。",
        "限速计划是什么？" to
            "在设置 → 下载里，全局限速下面有「限速计划」：设定一个每天的时段（比如 23:00–07:00），时段内全局限速自动换成计划里那一档，时段外还是用原来的全局限速。\n" +
            "典型用法：白天把全局限速设小一点，不抢全家人的带宽；夜间计划设成「不限」，让它放开下。正在下的任务到了点会自动切换，不用重启任务。\n" +
            "它只管限速，和「通知静默时段」是两回事：那个只管完成通知在夜间不震动、不响铃，不管下载速度。两个时段各设各的，互不影响。",
        "任务备注是干什么的？" to
            "每个任务可以写一条你自己的备注（最多 200 字），比如「给 TuxunGo 换的模型，下完导入」。在任务卡片展开详情，点「加备注 / 改备注」就能写，所有状态的任务都能加。\n" +
            "备注和卡片上那行系统写的完成说明是两回事：系统说明是 App 写的（存在哪、校验结果等），备注只属于你，两边互不覆盖。\n" +
            "主页搜索能搜到备注内容；导出备份时备注也会带上，换手机恢复后还在。",
        "排队时卡片上的「预检提示」是什么意思？" to
            "排在队列前面的任务，App 会趁它们还没轮到，先轻轻问一下服务器：这个链接还在吗？\n" +
            "只有服务器明确回「文件不存在」（404 / 410），卡片上才会标红提示「可能已失效」。这只是提前预警，不是判死刑：真轮到它下载时还会正式再试一次，万一预检冤枉了它，照样能下。\n" +
            "你手动点「继续」也会把旧的预检提示清掉重新查。预检只对 http / https 链接做，FTP 没有这种问法，不参与。",
        "搜索和「按站点分组」怎么用？" to
            "主页最上面的搜索框是全局搜索：输入关键词，任务（文件名、链接、备注、站点都算，连已经归档的记录也能搜到）、下好的文件、嗅探过的网页会分成三组列出来。任务那一组直接就是任务卡片，所有按钮都能用；文件点一下跳到「已下载文件」页；嗅探历史点一下会把那个网页填进新建下载框，重新嗅探。清空搜索就回到原来的任务列表。\n" +
            "任务多了想按网站归类：搜索框下面的「视图」切到「按站点」，任务会按下载站点分组，每组有个组头，写着这个站有几个任务、总共下了百分之多少。点组头可以把这一组折叠起来，折叠状态会记住，下次打开还是折叠的。\n" +
            "分组和标签、排序的关系：先按上面的标签筛（全部 / 下载中 / 已完成 / 失败），再分组；排序只在每个分组内部生效。宽屏的双列排版只在平铺视图下有，分组视图是单列的，方便看组头。",
        "连接数开多少合适？「连接数对比实测」怎么用？" to
            "大多数时候不用你操心：新任务先 16 条爆发开局，下载中再按实际速度自动加减，单个任务最多 64 条；所有任务合计还有一个全局上限（设置 → 下载，8–128 条、默认 32），多任务同时下时按优先级分摊。\n" +
            "想知道某个网站到底开几条最快：到设置 → 引导与数据 → 诊断，里面有「连接数对比实测」，拿同一个链接轮流测 4 / 16 / 32 / 64 条，测完会给出最快的那一档。\n" +
            "实测结果可以一键应用到对应站点：以后从这个站下载就直接按那一档起步，不再从头慢慢试。想改回自动，在设置里清除按主机学习数据就行。\n" +
            "实测会真的下载一小段数据（有总流量上限），测的时候最好别同时开着大下载，结果会互相影响，页面上也会提醒。\n" +
            "诊断页上还能看到这台手机的内存档位和上次启动耗时：内存小的手机下载出问题、或者打开 App 觉得慢时，先看这两行再判断。",
        "息屏后下载会变慢吗？「息屏不降速」和省电怎么取舍？" to
            "会变慢的原因是：息屏后手机会让处理器睡过去，正在下载的线程会被拖慢、拖晚，大文件更容易不稳。\n" +
            "设置 → 后台运行里的「下载中保持运行（息屏不降速）」默认开着：只要有任务真正在传输，就保持处理器运行，息屏也能稳稳接着下；任务一结束就立刻放开。排队、等充电、等网络、等预约这些没在下载的状态不会占用。\n" +
            "代价是更费电：整夜挂着下大文件时更明显。在意电量、或者只是下小文件，可以把这个开关关掉，下载照样能下，只是息屏时可能慢一些。\n" +
            "它和电池白名单是两回事：白名单管的是 App 会不会被系统冻结，这个开关管的是处理器睡不睡，两个都在设置 → 后台运行那一栏。",
        "FTP 下载也能多连接吗？" to
            "能。ftp:// 链接和普通链接一样建任务；服务器支持断点续传（能报出文件大小、支持从中间开始下）时，会开多条连接分片同时下，最多 8 条——FTP 服务器普遍对同时连接数管得严，开太多容易被拒，所以上限比普通下载低。\n" +
            "服务器不支持断点续传时，自动照旧单连接下载，任务卡片上会写明原因，不是出错。\n" +
            "FTP 任务一样支持暂停续传：分片进度会记下来，下次接着下，不用重来。",
        "排队时，为什么先开始的是另一个网站的任务？" to
            "这是队列按站点轮转：同一优先级里，刚轮到过的网站先让一让，排在后面的另一个网站的任务先开始，免得一长串同一个网站的任务把别的网站一直饿着。\n" +
            "优先级仍然说了算：高优先级的任务永远排在低优先级前面，轮转只在同优先级里生效；你手动「插队」的任务仍然最优先，下一个就轮到它。\n" +
            "只有一个网站的任务在排队时，顺序和以前完全一样，就是列表顺序。",
        "连上要登录的公共 WiFi，为什么任务先停下了？" to
            "商场、机场这类公共 WiFi，刚连上时要先在浏览器里打开登录页点一下同意或输手机号，这一步没做完之前，真正的下载是通不过去的。\n" +
            "App 发现当前网络是这种「要先登录」的网络时，会把任务先停泊住等你，不会把登录页当成文件存下来，也不会直接判下载失败。\n" +
            "你打开浏览器完成登录后，任务会自动接着下，不用回来手动点。切网、开飞行模式这类中途断网也是同样的停泊处理：等网络恢复自动继续。",
        "任务失败了，卡片上的建议是怎么来的？" to
            "失败原因是在出错那一刻就按类型记下来的：空间不够、文件被服务器换过、被限流、链接已失效、需要登录等等，分门别类，不再靠事后猜错误文字。\n" +
            "卡片上的处理建议和「要不要自动重试、怎么重试」都按这个类型来，所以重启 App 之后建议照样准。\n" +
            "照着建议做就行：比如提示空间不够就先清空间再点继续，提示需要登录就用内置浏览器打开原网页登录后从页面里点下载。实在判断不出类型的失败，卡片就只说事实、不硬给建议。\n" +
            "想把出问题的任务集中看：标签页出现「异常」时点一下，失败的和被断网、等登录、等充电、等 WiFi 停住的任务都会一次筛出来。\n" +
            "失败的任务有两个以上时，「异常」页顶部还会出现批量处理入口，点一下就能给这些失败任务统一换个方式重试（换浏览器标识或改单线程）；停泊中的任务是在等条件恢复，不用重试，不在这个入口里。",
        "任务详情里的「服务器画像」是什么？" to
            "是这个任务实际下载过程中记下来的一组事实，展开任务详情就能看到：服务器支不支持断点续传、服务器报的文件大小、实际同时开过的峰值连接数、用了多个下载源时每个源各下了多少。\n" +
            "它只展示真的记到的数据：没探到的就不显示，不猜、不编。\n" +
            "用处是排查问题时心里有数：比如峰值连接数一直只有 1，多半是服务器只给一条；某个镜像源下的字节数是 0，说明它一直没帮上忙。",
        "任务详情里的「连接预算」是什么？" to
            "正在下载的任务，展开详情能看到这一行：这个任务当前分到了几条连接，以及这个数字是被什么压住的——所有任务合计的全局连接上限、这个站点以前限流过留下的上限、还是单个任务自己的上限，三种说法对应代码里真实的三种限制，不猜。\n" +
            "数字是实时刷新：别的任务结束腾出连接、这个任务分到的数量变了，这一行马上跟着变；分到的数量有了、实际连接还在往上爬暂时没跟上时，会如实多写一句实际在跑几条。\n" +
            "只有正在下载的任务才有这一行，排队、暂停和已完成的任务不显示，不拿假数字糊弄人。想调大，先看清是哪一项压住的，再去改对应的设置。",
        "下完怎么确认文件没下错？校验文件又是什么？" to
            "办法是算校验值：给任务填上发布方公布的 SHA-256 或 MD5（在任务详情里设置），文件下完后 App 会把整个文件算一遍和它比对，对不上就明说，可能是下坏了或者服务器上的文件被换过。填的时候不用自己修剪：从发布页把整行复制下来，带着文件名、带着「sha256:」这样的前缀都能认；粘贴里同时有两个不一样的值时会拒绝并说明，不瞎猜。\n" +
            "很多网站还会把一个 .sha256 或 .md5 小文件放在下载文件旁边，里面就写着官方的校验值。设置 → 提示里打开「自动读取校验文件」（默认关）后，新建下载会顺手去找这个小文件，有就记下来，下完按网站给的值核对，并在卡片上写明是按网站给的值核对的。\n" +
            "关着这个开关时不会发出任何额外请求；找不到校验文件、或者文件里写了好几个值又对不上文件名时，就当没有，不会随便挑一个用。",
        "同一个链接为什么建不了第二个任务？" to
            "这是建任务时先查重：同一链接只要还有一个没下完的任务在列表里——排队、下载中、暂停、失败都算，主地址对上、或者任一镜像地址对上，都算同一个——就不会再重复建一个，免得同一个文件下两遍、互相抢连接。\n" +
            "批量粘贴或导入链接时，重复的会被跳过，并明说跳过了几个，你心里有数。\n" +
            "已经下完的历史不拦：想再下一份同样的文件，照样可以建，不受这个查重影响。",
        "镜像显示「待确认」是什么意思？能手动放行吗？" to
            "多源下载前，每个镜像都会先被校验一遍（大小加内容抽样），镜像管理里逐个标明状态：已确认（就是同一个文件）、已拒绝（已经证明是别的文件）、还没校验，以及待确认——没能确认是不是同一个文件，常见原因是这个镜像需要登录，App 拿不到能对比的内容。\n" +
            "待确认不等于对不上：你可以点「仍然使用」手动放行，让它参与这个任务的多源下载；放行只对这个任务有效，随时可以取消。\n" +
            "已经证明是别的文件的镜像不能放行，这是为了防止把两个不同的文件拼在一起，没有例外。",
        "嗅探过的网页，想再抓一次怎么办？" to
            "不用重新粘贴地址：打开新建下载框，嗅探历史里每条记录旁边都有「重新嗅探」按钮，点一下就用原来的网页地址重新抓一遍，出来的结果和一次全新嗅探一样，也会照常自动测大小。\n" +
            "抓成功后，这条记录会顶到历史最前面，这次结果也会成为主页上「上次嗅探结果」的内容，接着勾选下载就行。\n" +
            "要是这个页面现在打不开了（网站下线、需要登录、断网），原来的历史记录和上次的结果都原样保留，不会因为一次失败就丢掉，等网络或登录恢复了再点一次即可。",
        "「一键体检」是什么？什么时候用？" to
            "在设置 → 引导与数据 → 诊断页的最上面：点「开始体检」，版本与签名、内存档位、设备温控、上次启动耗时、下载空间、当前网络、域名解析、学习数据和任务状态会汇成一页速览，每项写明正常、注意还是异常。配了代理时多一项代理连通，没配就不列这一项；设备温控读不到时也不列，不会编一个状态出来。\n" +
            "它点了才跑，进页面时什么都不查；只有代理连通和域名解析会发出很小的网络请求，开始前页面上会先写明，其余都在本机完成。\n" +
            "下载出问题、又说不清卡在哪一步时，先跑一次体检，结果可以一键复制（网址参数和敏感值会先去掉），把这段文字发给帮你看问题的人，对方一眼就能看出是哪一项不对。想更细地逐项查，用同一页下面原有的「开始自检」。",
    )
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "h-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Text(
                    "帮助中心",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item(key = "h-intro") {
            Text(
                "下面是大家最常问的几件事，全是离线内容，不用联网也能看。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(faqs, key = { it.first }) { (q, a) ->
            SettingsGroup(q) {
                Text(a, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * v2.4: 诊断 (self-check) page. One tap runs a LOCAL check-up —
 * storage writable + a rough write-speed benchmark, DNS and
 * network state, proxy reachability when one is configured, the
 * installed version + signing fingerprint, and the task counts /
 * on-disk footprint — and shows the results in plain words with a
 * one-tap copy of the whole report (for sending to the developer
 * when something is wrong). NOTHING is uploaded: the report is
 * built on the phone and only leaves it if the user pastes it
 * somewhere themselves.
 */
@androidx.compose.runtime.Composable
fun DiagnosticsScreen(version: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var report by remember { mutableStateOf("") }

    // ---- v5.5: 一键体检 (see HealthCheck for the verdicts) ----
    // Nothing runs on entry: the items show 未检查 until the
    // user taps 开始体检. Results land item by item (each step
    // posts its own row) so the page never freezes on the two
    // network steps.
    var healthRunning by remember { mutableStateOf(false) }
    var healthStarted by remember { mutableStateOf(false) }
    var healthCurrent by remember { mutableStateOf<HealthCheck.Item?>(null) }
    var healthResults by remember {
        mutableStateOf<List<HealthCheck.Result>>(emptyList())
    }
    var healthCopy by remember { mutableStateOf("") }

    fun healthPost(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    fun runHealthCheck() {
        if (healthRunning) return
        healthRunning = true
        healthStarted = true
        healthResults = emptyList()
        healthCopy = ""
        healthCurrent = null
        Thread {
            val results = mutableListOf<HealthCheck.Result>()
            fun publish(r: HealthCheck.Result) {
                results.add(r)
                val snapshot = results.toList()
                healthPost { healthResults = snapshot }
            }
            fun step(
                item: HealthCheck.Item,
                block: () -> HealthCheck.Result?,
            ) {
                healthPost { healthCurrent = item }
                runCatching { block() }.getOrNull()?.let { publish(it) }
            }
            // 1. version + signing fingerprint — the same
            // PackageManager read the 自检 below does, judged
            // by HealthCheck (fingerprint口径 = Diagnostics').
            step(HealthCheck.Item.VERSION) {
                val fingerprint = runCatching {
                    val pm = context.packageManager
                    @Suppress("DEPRECATION")
                    val pi = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pm.getPackageInfo(
                            context.packageName,
                            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
                        )
                    } else {
                        pm.getPackageInfo(
                            context.packageName,
                            android.content.pm.PackageManager.GET_SIGNATURES,
                        )
                    }
                    @Suppress("DEPRECATION")
                    val certBytes = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                    } else {
                        pi.signatures?.firstOrNull()?.toByteArray()
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(certBytes ?: byteArrayOf())
                    digest.joinToString("") { "%02x".format(it) }
                }.getOrDefault("")
                HealthCheck.versionVerdict(
                    version, Diagnostics.fingerprint8(fingerprint),
                )
            }
            // 2. memory tier (DownloadCenter's cached verdict).
            step(HealthCheck.Item.MEMORY) {
                HealthCheck.memoryVerdict(DownloadCenter.memoryTier())
            }
            // 2b. v6.1: device thermal state — a one-shot
            // snapshot read at tap time (this page's standing
            // caliber: nothing listens, nothing polls). A null
            // band (read failure / unknown status value) makes
            // step() publish nothing, so the item is simply
            // absent from the results — never a faked verdict.
            step(HealthCheck.Item.THERMAL) {
                ThermalStatus.currentBand(context)
                    ?.let { HealthCheck.thermalVerdict(it) }
            }
            // 3. last cold start (StartupTiming's stored value).
            step(HealthCheck.Item.STARTUP) {
                HealthCheck.startupVerdict(StartupTiming.lastColdStartMs(context))
            }
            // 4. download-dir free space (PublicStorage's read;
            // -1 with a custom SAF folder reads honestly as
            // 读不到 inside the verdict).
            step(HealthCheck.Item.SPACE) {
                HealthCheck.spaceVerdict(
                    PublicStorage.availableBytes(context),
                    DownloadCenter.downloadDirUri.isNotBlank(),
                )
            }
            // 5. network + gates — the summary card's own
            // snapshot, so the two surfaces cannot disagree.
            step(HealthCheck.Item.NETWORK) {
                HealthCheck.networkVerdict(
                    DownloadCenter.summaryNetSnapshot(DownloadCenter.store.tasks),
                )
            }
            // 6. proxy reachability — ONLY when a proxy is
            // configured and active; the same bare socket probe
            // the 自检 uses. Never probes an unconfigured proxy.
            val ps = DownloadCenter.proxySettings()
            if (ps.active) {
                step(HealthCheck.Item.PROXY) {
                    val reachable = runCatching {
                        java.net.Socket().use { s ->
                            s.connect(
                                java.net.InetSocketAddress(ps.host, ps.port), 5_000,
                            )
                            true
                        }
                    }.getOrDefault(false)
                    HealthCheck.proxyVerdict(true, reachable, ps.label())
                }
            }
            // 7. DNS — the 自检's own two hosts and 4 s timeout
            // shape, gathered per host so the verdict can count.
            step(HealthCheck.Item.DNS) {
                fun dnsProbe(host: String): HealthCheck.DnsProbe {
                    var result: Array<java.net.InetAddress>? = null
                    var failed = false
                    val t = Thread {
                        runCatching { java.net.InetAddress.getAllByName(host) }
                            .onSuccess { result = it }
                            .onFailure { failed = true }
                    }
                    t.isDaemon = true
                    t.start()
                    t.join(4_000)
                    return when {
                        failed -> HealthCheck.DnsProbe(host, false, "$host 解析失败")
                        result != null -> HealthCheck.DnsProbe(
                            host, true, "$host 能解析（${result!!.size} 个地址）",
                        )
                        else -> HealthCheck.DnsProbe(host, false, "$host 解析超时")
                    }
                }
                HealthCheck.dnsVerdict(
                    listOf(dnsProbe("www.baidu.com"), dnsProbe("github.com")),
                )
            }
            // 8. learning-data counts (existing parse results).
            step(HealthCheck.Item.LEARNING) {
                HealthCheck.learningVerdict(
                    DownloadCenter.hostMemoryCount(),
                    DownloadCenter.hostStatsCount(),
                )
            }
            // 9. task total + abnormal count — the GateContext
            // is built exactly like the home 异常 tab's (same
            // conservative WiFi read), so the counts agree.
            step(HealthCheck.Item.TASKS) {
                val tasks = DownloadCenter.store.tasks
                val gates = AbnormalFilter.GateContext(
                    wifiGateClosed = DownloadCenter.wifiOnly &&
                        !runCatching { DownloadCenter.isOnWifi() }
                            .getOrDefault(false),
                    chargeGateClosed = ChargeGuard.shouldPark(
                        DownloadCenter.chargeOnlyDownload,
                        DownloadCenter.isCharging(),
                    ),
                    nowMs = System.currentTimeMillis(),
                )
                HealthCheck.tasksVerdict(
                    tasks.size, AbnormalFilter.count(tasks, gates),
                )
            }
            val copyText = HealthCheck.buildCopyText(version, results.toList())
            healthPost {
                healthCopy = copyText
                healthCurrent = null
                healthRunning = false
            }
        }.start()
    }

    // ---- v4.2: connection-count comparison (knife 5) ----
    // Prefilled ONCE with the most recently created task's URL
    // (the link the user most likely wants to check); empty when
    // there are no tasks yet.
    var ccUrl by remember {
        mutableStateOf(
            DownloadCenter.store.tasks.maxByOrNull { it.createdAt }?.url ?: ""
        )
    }
    var ccUrlError by remember { mutableStateOf(false) }
    var ccRunning by remember { mutableStateOf(false) }
    var ccPhase by remember { mutableStateOf("") }
    var ccLiveBps by remember { mutableStateOf(0L) }
    var ccResults by remember {
        mutableStateOf<List<ConnCompare.TierResult>>(emptyList())
    }
    var ccShowConfirm by remember { mutableStateOf(false) }
    // v4.3: applying the winning tier as this host's remembered
    // starting count — the confirm dialog's visibility and the
    // applied count (0 = nothing applied yet this visit; a fresh
    // measurement clears it, see startConnCompare).
    var ccShowApplyConfirm by remember { mutableStateOf(false) }
    var ccAppliedThreads by remember { mutableStateOf(0) }
    // v4.2 audit round 27: RUNNING tasks at the moment the
    // comparison STARTED (read once in startConnCompare — a
    // snapshot the results below are judged against, not a
    // live counter).
    var ccRunningAtStart by remember { mutableStateOf(0) }
    var ccTestRef by remember { mutableStateOf<SpeedTest?>(null) }
    // Leaving the screen stops the comparison (same rule as the
    // speed-test page: it would keep burning data otherwise).
    DisposableEffect(Unit) {
        onDispose { ccTestRef?.cancel() }
    }

    fun ccPost(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    fun startConnCompare() {
        if (ccRunning) return
        val url = ccUrl.trim()
        // Audit round 27: snapshot the contention at the start
        // moment only (see ConnCompare.runningTasksNote).
        ccRunningAtStart = DownloadCenter.store.tasks.count {
            it.status == TaskStatus.RUNNING
        }
        ccRunning = true
        ccResults = emptyList()
        ccAppliedThreads = 0
        ccLiveBps = 0
        ccPhase = "准备实测…"
        val test = SpeedTest()
        ccTestRef = test
        Thread {
            val out = mutableListOf<ConnCompare.TierResult>()
            var spent = 0L
            for (tier in ConnCompare.TIERS) {
                if (test.cancelled) break
                // The four tiers share ONE traffic budget: each
                // tier only gets what the earlier tiers left.
                val budget = ConnCompare.tierBudget(spent)
                ccPost {
                    ccPhase = "正在测 $tier 条连接…"
                    ccLiveBps = 0
                }
                val r = test.runPhase(
                    url, tier, ConnCompare.PHASE_MS,
                    totalCapBytes = budget,
                ) { b -> ccPost { ccLiveBps = b } }
                spent += r.bytes
                out.add(
                    ConnCompare.TierResult(tier, r.bps, r.windowMs, r.bytes)
                )
                // Publish per tier (a FRESH list instance, the
                // v4.0 lesson) so finished tiers show while the
                // next one runs — and survive a cancel.
                val snapshot = out.toList()
                ccPost { ccResults = snapshot }
            }
            ccPost {
                ccRunning = false
                ccLiveBps = 0
                ccPhase = when {
                    test.cancelled -> "已取消（已测完的档位保留在下面）"
                    out.isEmpty() -> ""
                    else -> "实测完成"
                }
            }
        }.start()
    }

    fun runChecks() {
        if (running) return
        running = true
        lines = emptyList()
        report = ""
        Thread {
            val out = mutableListOf<Pair<String, String>>()
            // ---- app identity: version + signing fingerprint ----
            val fingerprint = runCatching {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val pi = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pm.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                } else {
                    pm.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.GET_SIGNATURES,
                    )
                }
                @Suppress("DEPRECATION")
                val certBytes = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                } else {
                    pi.signatures?.firstOrNull()?.toByteArray()
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certBytes ?: byteArrayOf())
                digest.joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
            val fp8 = Diagnostics.fingerprint8(fingerprint)
            out += "签名指纹" to if (fp8.isNotBlank()) {
                "$fp8…（正式版应为 B73A2080… 开头）"
            } else {
                "读不到"
            }
            // ---- storage: free space on the download volume ----
            val avail = PublicStorage.availableBytes(context)
            out += "存储空间" to if (avail >= 0) {
                "下载目录可用 ${Format.size(avail)}"
            } else if (DownloadCenter.downloadDirUri.isNotBlank()) {
                "用的是自定义文件夹，它的可用空间系统查不到；下载报空间不足时先看手机总空间"
            } else {
                "可用空间查不到"
            }
            // ---- storage: writable + rough write speed ----
            // 8 MiB scratch in the app cache dir, 128 KB writes —
            // the same write granularity the engine uses (one
            // pwrite per read buffer, fsync once at the end).
            val bench = runCatching {
                val f = java.io.File(context.cacheDir, "diag_bench.bin")
                val bytes = 8L * 1024 * 1024
                val buf = ByteArray(128 * 1024) { (it % 251).toByte() }
                val t0 = System.nanoTime()
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    raf.setLength(0)
                    var written = 0L
                    while (written < bytes) {
                        raf.write(buf)
                        written += buf.size
                    }
                    val t1 = System.nanoTime()
                    raf.fd.sync()
                    val t2 = System.nanoTime()
                    Triple(
                        bytes,
                        (t1 - t0) / 1_000_000,
                        (t2 - t1) / 1_000_000,
                    )
                }.also { runCatching { f.delete() } }
            }.getOrNull()
            out += "写盘" to if (bench != null) {
                Diagnostics.writeBenchLine(bench.first, bench.second, bench.third) +
                    "。日常下载远达不到这个速度时，瓶颈在网络或服务器，不在手机存储"
            } else {
                "写速粗测没跑成（临时目录写不进去）"
            }
            // ---- network state ----
            val netLine = runCatching {
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                when {
                    caps == null -> "当前没有网络连接"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                        "WiFi 已连接" +
                            if (cm.isActiveNetworkMetered) "（系统标记为计费网络）" else ""
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        "正在用流量（移动数据）"
                    else -> "已连接（其他网络类型）"
                }
            }.getOrDefault("网络状态读不到")
            out += "网络" to netLine
            // ---- DNS: resolve one domestic + one overseas host ----
            fun dns(host: String): String {
                var result: Array<java.net.InetAddress>? = null
                var failed = false
                val t = Thread {
                    runCatching { java.net.InetAddress.getAllByName(host) }
                        .onSuccess { result = it }
                        .onFailure { failed = true }
                }
                t.isDaemon = true
                t.start()
                t.join(4_000)
                return when {
                    failed -> "$host 解析失败"
                    result != null -> "$host 能解析（${result!!.size} 个地址）"
                    else -> "$host 解析超时"
                }
            }
            out += "域名解析" to (dns("www.baidu.com") + "；" + dns("github.com"))
            // ---- proxy reachability (only when configured) ----
            val ps = DownloadCenter.proxySettings()
            out += "代理" to if (!ps.active) {
                "未开启（直连；没配代理时上面的一键体检不列代理这一项，说的是同一件事）"
            } else {
                val reachable = runCatching {
                    java.net.Socket().use { s ->
                        s.connect(
                            java.net.InetSocketAddress(ps.host, ps.port), 5_000,
                        )
                        true
                    }
                }.getOrDefault(false)
                if (reachable) {
                    "已开启，代理服务器 ${ps.host}:${ps.port} 能连上"
                } else {
                    "已开启，但代理服务器 ${ps.host}:${ps.port} 连不上——" +
                        "下载会直接报这个错，不会偷偷直连"
                }
            }
            // ---- v5.1: memory tier (same line as the page row) ----
            val tierBudget = DownloadCenter.memoryBudget()
            out += "内存档位" to
                "${com.tuxundown.android.core.MemoryTiers.tierLabel(DownloadCenter.memoryTier())}；" +
                "每个下载任务的缓冲上限约 ${tierBudget.perTaskBufferBytes / (1024 * 1024)} MB"
            // ---- v5.1 (Step C): last cold start — carried in
            // the copied report exactly when the page shows the
            // row (no stored value = no line in either place) ----
            val startupText = StartupTiming.displayText(
                StartupTiming.lastColdStartMs(context)
            )
            if (startupText != null) {
                out += "上次启动耗时" to startupText
            }
            // ---- tasks: counts + on-disk footprint ----
            val tasks = DownloadCenter.store.tasks
            out += "任务数" to Diagnostics.taskCountsLine(tasks)
            val unfinishedBytes = tasks
                .filter { it.status != TaskStatus.DONE }
                .sumOf { it.downloaded.coerceAtLeast(0) }
            val metaBytes = runCatching {
                val f = java.io.File(context.filesDir, "tasks.json")
                if (f.exists()) f.length() else 0L
            }.getOrDefault(0L)
            out += "占用" to "未完成任务已写入约 ${Format.size(unfinishedBytes)}" +
                "（按任务记录口径）；任务记录文件 ${Format.size(metaBytes)}"
            val text = Diagnostics.buildReport("v$version", out)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                lines = out
                report = text
                running = false
            }
        }.start()
    }

    // v4.2: the comparison burns real data — the shared traffic
    // cap is stated and consented BEFORE anything runs (same
    // pattern as the speed-test page's multi-point confirm).
    if (ccShowConfirm) {
        AlertDialog(
            onDismissRequest = { ccShowConfirm = false },
            title = { Text("连接数对比实测") },
            text = { Text(ConnCompare.consentText()) },
            confirmButton = {
                TextButton(onClick = {
                    ccShowConfirm = false
                    startConnCompare()
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { ccShowConfirm = false }) { Text("取消") }
            },
        )
    }

    // v4.3: applying the winning tier writes a per-host memory —
    // say exactly what that does and does not change BEFORE it
    // happens (the settings page can undo it, see clearHostMemory).
    if (ccShowApplyConfirm) {
        val bestTier = ConnCompare.bestTier(ccResults)
        val host = com.tuxundown.android.core.HostMemory.hostOf(ccUrl.trim())
        AlertDialog(
            onDismissRequest = { ccShowApplyConfirm = false },
            title = { Text("应用最快档") },
            text = {
                Text(
                    "以后从这个站点（$host）新下载时，起步就用 " +
                        "${bestTier?.connections ?: 0} 条连接，不再从头慢慢试。\n\n" +
                        "只是起步值：真实下载还会受全局连接上限限制；" +
                        "如果这个站点以前学到过限流上限，也仍然不会超过它。\n\n" +
                        "想反悔随时可以：在「下载统计」页点「清除按主机学习数据」就能全部清掉、回到自动试探。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ccShowApplyConfirm = false
                    if (bestTier != null) {
                        DownloadCenter.applyHostThreads(ccUrl.trim(), bestTier.connections)
                        ccAppliedThreads = bestTier.connections
                    }
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { ccShowApplyConfirm = false }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "d-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Text(
                    "诊断",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item(key = "d-intro") {
            Text(
                "这一页有两套检查：上面的「一键体检」是汇总速览，把各项状态一页看全；" +
                    "下面「开始自检」是更细的逐项检查，会实测写盘速度、逐项列出结果。" +
                    "两边结果都只显示在这台手机上，不上传；下载出了问题要找人看时，" +
                    "把结果复制全文发过去就行。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ---- v5.5: 一键体检 — the aggregated health check at
        // the top of the page. Nothing runs until 开始体检 is
        // tapped; before that every row honestly says 未检查.
        // Each row carries BOTH a coloured dot and the level
        // word （正常 / 注意 / 异常 / 未检查 / 检查中）, so the
        // verdict never rides on colour alone. ----
        item(key = "d-health") {
            val proxyActive = DownloadCenter.proxySettings().active
            // v6.1: the thermal row is listed only when the
            // status is readable on this device (one probe,
            // remembered for this page visit); an unreadable
            // status omits the row entirely — never faked.
            val thermalReadable = remember {
                ThermalStatus.currentBand(context) != null
            }
            val healthItems = HealthCheck.itemsFor(proxyActive, thermalReadable)
            val byItem = healthResults.associateBy { it.item }
            SettingsGroup("一键体检") {
                Text(
                    "把版本、内存、温控、空间、网络、解析和任务状态汇成一页，一项一项看。" +
                        "点下面的按钮才会开始检查，进页面不会自动跑。提醒一句：" +
                        "代理连通和 DNS 两项会发出很小的网络请求，其余都在本机完成。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { runHealthCheck() },
                    enabled = !healthRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (healthRunning) "正在体检…" else "开始体检") }
                Spacer(Modifier.height(10.dp))
                for (item in healthItems) {
                    val r = byItem[item]
                    val isCurrent = healthRunning && healthCurrent == item
                    val dotColor: Color
                    val stateWord: String
                    val valueText: String
                    when {
                        r != null -> {
                            dotColor = when (r.level) {
                                HealthCheck.Level.OK -> Color(0xFF2E7D32)
                                HealthCheck.Level.WARN -> Color(0xFFB26A00)
                                HealthCheck.Level.BAD -> Color(0xFFB3261E)
                            }
                            stateWord = r.level.label
                            valueText = r.value
                        }
                        isCurrent -> {
                            dotColor = MaterialTheme.colorScheme.primary
                            stateWord = "检查中"
                            valueText = "正在检查…"
                        }
                        healthStarted && healthRunning -> {
                            dotColor = MaterialTheme.colorScheme.outline
                            stateWord = "未检查"
                            valueText = "还没轮到"
                        }
                        healthStarted -> {
                            dotColor = MaterialTheme.colorScheme.outline
                            stateWord = "未检查"
                            valueText = "这一项这次没查成，再点一次开始体检"
                        }
                        else -> {
                            dotColor = MaterialTheme.colorScheme.outline
                            stateWord = "未检查"
                            valueText = "点「开始体检」后检查"
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier.padding(top = 5.dp).size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                stateWord,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                valueText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (!healthRunning && healthCopy.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val cm = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE
                                ) as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "体检结果", healthCopy,
                                    )
                                )
                            }
                            Toast.makeText(
                                context, "体检结果已复制，去粘贴发送就行",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("复制体检结果") }
                }
            }
        }
        // ---- v5.1: current memory tier (display only — the
        // tier is automatic, there is deliberately no switch) ----
        item(key = "d-tier") {
            val tier = DownloadCenter.memoryTier()
            val budget = DownloadCenter.memoryBudget()
            SettingsGroup("内存档位") {
                InfoRow(
                    "内存档位",
                    "${com.tuxundown.android.core.MemoryTiers.tierLabel(tier)}；" +
                        "每个下载任务的缓冲上限约 ${budget.perTaskBufferBytes / (1024 * 1024)} MB",
                )
                // v5.1 (Step C): last cold-start time, same
                // group. No stored value (first install / never
                // measured) = the row is simply absent — never
                // a 0 or a made-up number (see StartupTiming).
                val startupText = StartupTiming.displayText(
                    StartupTiming.lastColdStartMs(context)
                )
                if (startupText != null) {
                    InfoRow("上次启动耗时", startupText)
                }
            }
        }
        item(key = "d-run") {
            Button(
                onClick = { runChecks() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "正在自检…" else "开始自检") }
        }
        if (lines.isNotEmpty()) {
            item(key = "d-results") {
                SettingsGroup("自检结果") {
                    for ((label, value) in lines) {
                        InfoRow(label, value)
                    }
                }
            }
            item(key = "d-copy") {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val cm = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE
                            ) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("诊断报告", report)
                            )
                        }
                        Toast.makeText(
                            context, "诊断结果已复制，去粘贴发送就行",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("复制诊断结果全文") }
            }
        }
        // ---- v4.2: connection-count comparison (knife 5) ----
        item(key = "d-conncompare") {
            SettingsGroup("连接数对比实测") {
                Text(
                    "这测的是这个链接在你当前网络下吃多少连接——越多越快，" +
                        "说明服务器是按连接限速的；和测速页测到测速服务器的速度不是一回事。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = ccUrl,
                        onValueChange = {
                            ccUrl = it
                            ccUrlError = false
                        },
                        label = { Text("文件直链") },
                        singleLine = true,
                        enabled = !ccRunning,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val cm = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE
                                ) as android.content.ClipboardManager
                                val clip = cm.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0)
                                        .coerceToText(context)?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        ccUrl = text.trim()
                                        ccUrlError = false
                                    }
                                }
                            }
                        },
                        enabled = !ccRunning,
                    ) { Text("粘贴") }
                }
                if (ccUrlError) {
                    Text(
                        "这个链接不对：要以 http:// 或 https:// 开头的文件直链。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (ccPhase.isNotBlank()) {
                    Text(
                        ccPhase,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                // Audit round 27: the start-moment contention
                // snapshot, shown with this run's results (the
                // wording lives in ConnCompare so it is tested).
                val ccNote = ConnCompare.runningTasksNote(ccRunningAtStart)
                if (ccNote.isNotBlank() && (ccRunning || ccResults.isNotEmpty())) {
                    Text(
                        ccNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (ccRunning) {
                    Text(
                        Format.speed(ccLiveBps),
                        color = WinPalette.ChartGreen,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (ccResults.isNotEmpty()) {
                    val best = ConnCompare.bestTier(ccResults)
                    Spacer(Modifier.height(10.dp))
                    ccResults.forEach { r ->
                        if (r.bps > 0) {
                            InfoRow(
                                "${r.connections} 条连接：",
                                "${Format.speed(r.bps)}（稳态约 " +
                                    "${"%.1f".format(r.windowMs / 1000.0)} 秒）" +
                                    (if (best != null && r.connections == best.connections) {
                                        " ← 最快"
                                    } else {
                                        ""
                                    }),
                            )
                        } else {
                            InfoRow(
                                "${r.connections} 条连接：",
                                "没测出速度（链接可能不支持或已失效）",
                            )
                        }
                    }
                    if (best == null) {
                        Text(
                            "四档都没测出速度：这个链接多半已经失效，或者服务器不给数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // v4.3: close the loop — let the measured
                    // winner become this host's remembered
                    // starting count instead of dying on screen.
                    if (best != null && !ccRunning) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { ccShowApplyConfirm = true }) {
                            Text("把最快档应用到这个站点（${best.connections} 条）")
                        }
                        if (ccAppliedThreads > 0) {
                            Text(
                                "已应用：这个站点下次下载起步 $ccAppliedThreads 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ccRunning) {
                        OutlinedButton(onClick = { ccTestRef?.cancel() }) {
                            Text("取消")
                        }
                    } else {
                        Button(onClick = {
                            val u = ccUrl.trim().lowercase()
                            if (u.startsWith("http://") || u.startsWith("https://")) {
                                ccShowConfirm = true
                            } else {
                                ccUrlError = true
                            }
                        }) {
                            Text(if (ccResults.isEmpty()) "开始实测" else "再测一次")
                        }
                    }
                }
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
    chargeOnly: Boolean,
    onChargeOnly: (Boolean) -> Unit,
    keepCpuAwake: Boolean,
    onKeepCpuAwake: (Boolean) -> Unit,
    dirName: String,
    onPickDir: () -> Unit,
    onResetDir: () -> Unit,
    limitKBps: Int,
    onLimit: (Int) -> Unit,
    schedOn: Boolean,
    onSchedOn: (Boolean) -> Unit,
    schedStartText: String,
    onSchedStartText: (String) -> Unit,
    schedEndText: String,
    onSchedEndText: (String) -> Unit,
    schedLimitKBps: Int,
    onSchedLimit: (Int) -> Unit,
    maxConc: Int,
    onMaxConc: (Int) -> Unit,
    connCap: Int,
    onConnCap: (Int) -> Unit,
    autoRetry: Int,
    onAutoRetry: (Int) -> Unit,
    trafficMB: Int,
    onTraffic: (Int) -> Unit,
    dualChan: Boolean,
    onDualChan: (Boolean) -> Unit,
    bootResume: Boolean,
    onBootResume: (Boolean) -> Unit,
    sortFolders: Boolean,
    onSortFolders: (Boolean) -> Unit,
    archiveDays: Int,
    onArchiveDays: (Int) -> Unit,
    userAgent: String,
    onUserAgent: (String) -> Unit,
    amoled: Boolean,
    onAmoled: (Boolean) -> Unit,
    cardFields: Map<String, Boolean>,
    onCardField: (String, Boolean) -> Unit,
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
    quietOn: Boolean,
    onQuiet: (Boolean) -> Unit,
    quietStartText: String,
    onQuietStartText: (String) -> Unit,
    quietEndText: String,
    onQuietEndText: (String) -> Unit,
    autoInstall: Boolean,
    onAutoInstall: (Boolean) -> Unit,
    autoOpen: Boolean,
    onAutoOpen: (Boolean) -> Unit,
    autoVerify: Boolean,
    onAutoVerify: (Boolean) -> Unit,
    autoSidecar: Boolean,
    onAutoSidecar: (Boolean) -> Unit,
    autoExtract: Boolean,
    onAutoExtract: (Boolean) -> Unit,
    batteryOk: Boolean,
    onOpenBattery: () -> Unit,
    onShowGuide: () -> Unit,
    onShowHelp: () -> Unit,
    onShowDiag: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onMigrationExport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // v2.3: settings search — the settings page has grown to seven
    // groups; a search box at the top filters GROUPS by keyword.
    // Every item's common names are indexed in its group's keyword
    // string, so searching 限速/代理/备份 lands on the right group.
    var settingsQuery by remember { mutableStateOf("") }
    val settingsKeywords = mapOf(
        "s-download" to "下载 仅wifi wifi 充电 电源 同时下载 任务数 并发 连接上限 总连接 连接数 上限 全局限速 限速 速度 " +
            "限速计划 计划 时段 夜间 分时 " +
            "自动重试 重试 流量保护 流量 阈值 双通道 user-agent ua 浏览器标识 " +
            "下载目录 文件夹 保存位置 开机 自启 恢复 重启 续传 分类 分文件夹 类型",
        "s-proxy" to "代理 proxy http socks 端口 地址 服务器 用户名 密码 认证",
        "s-look" to "外观 amoled 纯黑 深色 暗色 主题 黑色",
        "s-card" to "任务卡片 卡片 字段 平均速度 重试次数 创建时间 来源站点 分片 显示 详情",
        "s-notify" to "提示 震动 声音 响铃 完成 自动安装 安装 自动打开 打开 静默 夜间 时段 勿扰 " +
            "校验 哈希 完整性 sha md5 校验文件 边车 自动读取 解压 zip 压缩包 自动解压",
        "s-power" to "后台运行 后台 保活 电池 白名单 省电 冻结",
        "s-data" to "引导与数据 引导 新手引导 帮助 常见问题 备份 导出 导入 " +
            "恢复 孤儿 清理 part 数据 诊断 自检 体检 归档 旧记录 删除记录 " +
            "迁移 迁移包 换手机 学习数据 统计",
        "s-about" to "关于 版本 检查更新 更新 github 开源 gpl 许可 更新日志 历史",
    )
    fun groupVisible(key: String): Boolean {
        val q = settingsQuery.trim().lowercase()
        if (q.isEmpty()) return true
        val kw = settingsKeywords[key] ?: return true
        return q.split(Regex("\\s+")).all { it in kw }
    }
    val anyGroupVisible = settingsKeywords.keys.any { groupVisible(it) }
    // v2.0: in-app update check state (About section). The check
    // runs ONLY from the button below — UpdateCheck is never
    // called on launch or in the background.
    var updateBusy by remember { mutableStateOf(false) }
    var updateText by remember { mutableStateOf("") }
    var updateLatest by remember { mutableStateOf<UpdateCheck.Latest?>(null) }
    fun startUpdateCheck() {
        if (updateBusy) return
        updateBusy = true
        updateText = "正在检查…"
        updateLatest = null
        Thread {
            val result = UpdateCheck.fetchLatest()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updateBusy = false
                when (result) {
                    is UpdateCheck.Result.Found -> {
                        val l = result.latest
                        if (UpdateCheck.isNewer(l.version, version)) {
                            updateLatest = l
                            updateText = "发现新版本 v${l.version}（你现在是 v$version）"
                        } else if (l.version == version) {
                            updateText = "已经是最新版本（v$version），不用更新。"
                        } else {
                            updateText = "已经是最新：发布页最新正式版是 v${l.version}，" +
                                "你现在用的 v$version 比它还新。"
                        }
                    }
                    is UpdateCheck.Result.Failed -> updateText = result.reason
                }
            }
        }.start()
    }
    fun downloadUpdate() {
        val l = updateLatest ?: return
        // v5.4: the in-app update download is a creation entry
        // point too — it goes through the same CreateDedup
        // verdict as every other funnel (was: its own inline
        // copy of the old rule; before that, none).
        val dup = CreateDedup.isDuplicate(DownloadCenter.store.tasks, l.apkUrl)
        if (dup) {
            Toast.makeText(context, "这个更新已经在任务列表里了", Toast.LENGTH_SHORT).show()
            return
        }
        val t = DownloadCenter.newTask(l.apkUrl)
        t.fileName = l.apkName
        DownloadCenter.store.add(t)
        DownloadCenter.enqueue(t)
        updateLatest = null
        updateText = "更新已开始下载：回到主页看进度，下完点任务卡上的「安装」就能升级。"
        Toast.makeText(
            context, "更新已开始下载，下完在任务卡上点「安装」", Toast.LENGTH_LONG
        ).show()
    }
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
    // v3.4: old-record cleanup state — the day window, the
    // confirm dialog's frozen (days, victims) pair, and a tick
    // that makes the "currently eligible" count re-read after
    // a cleanup finishes.
    var cleanupDays by remember { mutableStateOf(90) }
    var cleanupBusy by remember { mutableStateOf(false) }
    var cleanupConfirm by remember {
        mutableStateOf<Pair<Int, List<com.tuxundown.android.core.TaskRecord>>?>(null)
    }
    var cleanupTick by remember { mutableStateOf(0) }
    // v3.6: the About page's in-app changelog dialog.
    var showChangelog by remember { mutableStateOf(false) }
    fun startCleanup(days: Int) {
        cleanupBusy = true
        Thread {
            val result = runCatching { DownloadCenter.cleanupOldRecords(days) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                cleanupBusy = false
                cleanupTick++
                result.onSuccess { (n, backup) ->
                    Toast.makeText(
                        context,
                        if (n > 0) {
                            "已清理 $n 条旧记录；清理前备份已存到下载目录：$backup"
                        } else {
                            "没有符合条件的旧记录，一条都没删"
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        "清理已取消：清理前备份没存成功，记录一条都没删",
                        Toast.LENGTH_LONG,
                    ).show()
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
        item(key = "s-search") {
            OutlinedTextField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it },
                label = { Text("搜索设置") },
                placeholder = { Text("输入关键词，如：代理 / 限速 / 备份") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (settingsQuery.isNotBlank() && !anyGroupVisible) {
            item(key = "s-nomatch") {
                Text(
                    "没有找到和「${settingsQuery.trim()}」有关的设置，" +
                        "换个词试试，比如：代理、限速、备份、深色。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        if (groupVisible("s-download")) item(key = "s-download") {
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("充电时才下载")
                        Text(
                            "开启后没插电源时任务只排队不下载，插上电源自动开始",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = chargeOnly, onCheckedChange = onChargeOnly)
                }
                Text(
                    "只管还没开始的任务：已经在下的不会因为拔掉电源被打断。" +
                        "和「仅 WiFi 下载」一起开时，两个条件都要满足才开始；" +
                        "都在等的时候，卡片上先写等 WiFi、WiFi 有了再写等充电。" +
                        "等充电时下载服务会留在后台，插上电源几秒内就会开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("同时下载任务数")
                ChipRow(
                    options = (1..5).map { it to "${it} 个" },
                    selected = maxConc,
                    onPick = onMaxConc,
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("全局连接上限（所有任务合计）")
                ChipRow(
                    options = listOf(
                        8 to "8 条", 16 to "16 条", 32 to "32 条",
                        48 to "48 条", 64 to "64 条", 96 to "96 条",
                        128 to "128 条",
                    ),
                    selected = connCap,
                    onPick = onConnCap,
                )
                Text(
                    "所有同时下载的任务加起来，最多开多少条连接。默认 32 条，" +
                        "单个任务自己最多能开 64 条；" +
                        "多个任务一起下时，总数封顶，任务之间按优先级分摊（高优先分得多），" +
                        "被压住的任务卡片上会写明。调低更省电、也更不容易被服务器限流；" +
                        "调高只在服务器不限速时才可能有区别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
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
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("限速计划")
                        Text(
                            "开启后，每天这个时段内改用下面那一档限速，时段外仍用上面的全局限速。" +
                                "典型用法：白天限速不抢全家带宽，夜间（比如 23:00–07:00）设「不限」放开下。" +
                                "改了立即生效，正在下的任务过点自动切换。和「通知静默时段」是两回事，互不影响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = schedOn, onCheckedChange = onSchedOn)
                }
                if (schedOn) {
                    Text(
                        "计划时段内的全局限速",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    ChipRow(
                        options = listOf(
                            0 to "不限", 512 to "512KB", 1024 to "1MB",
                            3072 to "3MB", 5120 to "5MB",
                        ),
                        selected = schedLimitKBps,
                        onPick = onSchedLimit,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = schedStartText,
                            onValueChange = onSchedStartText,
                            label = { Text("开始（如 23:00）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = schedEndText,
                            onValueChange = onSchedEndText,
                            label = { Text("结束（如 07:00）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val sMin = ScheduleTime.parseHhMm(schedStartText)
                        ?.let { it.first * 60 + it.second }
                    val eMin = ScheduleTime.parseHhMm(schedEndText)
                        ?.let { it.first * 60 + it.second }
                    if (sMin != null && eMin != null) {
                        val inWin = SpeedSchedule.inWindow(
                            sMin, eMin,
                            QuietHours.minuteOfDay(System.currentTimeMillis()),
                        )
                        Text(
                            if (inWin) {
                                "现在正处在计划时段内：全局限速按计划档生效中。"
                            } else {
                                "现在不在计划时段内：全局限速用的是上面那一档。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
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
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("开机后自动恢复未完成任务")
                        Text(
                            "手机重启后，没下完的任务按队列自动接着下；" +
                                "仍受「仅 WiFi 下载」和流量保护限制，你手动暂停的任务不会被自动恢复。默认关。" +
                                "安卓 15 不让开机广播直接启动下载服务，那些设备上改为你重启后第一次打开 App 时立刻补上恢复，任务不会丢",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = bootResume, onCheckedChange = onBootResume)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("按类型分文件夹")
                        Text(
                            "新下完的文件按类型放进公共「下载」目录的" +
                                " 视频 / 音频 / 图片 / 文档 / 压缩包 / APK 子文件夹；" +
                                "已下好的文件不搬家。用自定义下载文件夹时不生效。默认关",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sortFolders, onCheckedChange = onSortFolders)
                }
            }
        }
        if (groupVisible("s-proxy")) item(key = "s-proxy") {
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
        if (groupVisible("s-look")) item(key = "s-look") {
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
        if (groupVisible("s-card")) item(key = "s-card") {
            SettingsGroup("任务卡片显示") {
                Text(
                    "任务卡片展开「详细信息」时显示哪些可选行。关掉的行只是不显示，" +
                        "数据都还在；平均速度、重试次数、分片进度是以前就有的行（默认开），" +
                        "创建时间、来源站点是新增的可选行（默认关）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (f in CardFields.FIELDS) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label)
                            Text(
                                f.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cardFields[f.key] ?: f.default,
                            onCheckedChange = { onCardField(f.key, it) },
                        )
                    }
                }
            }
        }
        if (groupVisible("s-notify")) item(key = "s-notify") {
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("通知静默时段")
                        Text(
                            "开启后，这个时段里下载完成不震动、不响铃；" +
                                "完成通知会照常出现在通知栏，上面有「打开 / 分享」按钮。" +
                                "几个任务同时下完会合并成一条通知，不刷屏。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = quietOn, onCheckedChange = onQuiet)
                }
                if (quietOn) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = quietStartText,
                            onValueChange = onQuietStartText,
                            label = { Text("开始（如 23:00）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = quietEndText,
                            onValueChange = onQuietEndText,
                            label = { Text("结束（如 07:00）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下载完成后自动安装（仅安装包）")
                        Text(
                            "下完 APK 自动调起系统安装界面；最后的「安装」按钮仍要你亲手点，" +
                                "App 不会也不能替你确认。要是系统拦住了后台弹出，点完成通知或卡片上的「安装」也一样。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoInstall, onCheckedChange = onAutoInstall)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下载完成后自动打开")
                        Text(
                            "下完自动用能打开它的应用打开这个文件；安装包优先走上面的自动安装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoOpen, onCheckedChange = onAutoOpen)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下完自动校验文件完整性")
                        Text(
                            "任务里填了校验值（SHA-256 / MD5）时，下完立刻把整个文件读一遍比对，" +
                                "结果写在卡片和完成说明里。关掉后不自动读，需要时在任务详情里点「立即校验」。" +
                                "大文件校验要花一点时间，校验中可以取消。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoVerify, onCheckedChange = onAutoVerify)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动读取校验文件")
                        Text(
                            "开启后，新建任务时会顺手看一眼下载地址旁边有没有网站放的校验文件" +
                                "（地址后面加 .sha256 或 .md5 的那个小文件），有就把里面的校验值自动填进任务，" +
                                "下完照常校验，卡片上会写明校验值是网站给的。你手填过校验值的任务不会被它改动。" +
                                "找不到或读不懂就当没有，不影响下载。默认关，关着的时候不会多发任何请求。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoSidecar, onCheckedChange = onAutoSidecar)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下完自动解压 zip 压缩包")
                        Text(
                            "开启后，.zip 文件下完会自动解压到它所在文件夹里一个同名文件夹中，" +
                                "原 zip 保留。任务填了校验值时，校验对上才解压，没对上不解并在卡片上说明。" +
                                "只支持 zip，rar、7z 等其他格式不会动。解压有文件数和总大小上限，" +
                                "压缩包里有不安全路径会整包停下并说明。默认关",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoExtract, onCheckedChange = onAutoExtract)
                }
            }
        }
        if (groupVisible("s-power")) item(key = "s-power") {
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
                Text(
                    "预约下载和每天定时也靠这套保活：到点开始靠的是 App 在后台等着，" +
                        "另外还有一个系统闹钟兜底（不需要新权限，但不精确，省电模式下可能晚几分钟）。" +
                        "把上面两步做完，准时率最高；万一 App 被划掉杀掉、或手机重启后还没打开过 App，" +
                        "预约不会丢——下次 App 打开或服务起来时会立刻补上，只是可能晚开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "安卓 15 的两个新规矩，也说清楚：① 后台下载这类服务每天有约 6 小时的累计时长上限" +
                        "（所有这类 App 都一样），整夜连续下很多大文件时有可能用满——用满后系统会把服务停掉，" +
                        "正在下的任务会自动暂停、进度保留，点「继续」就接着下；把 App 打开到前台，这个计时会重新算。" +
                        "② 安卓 15 不让开机广播直接启动下载服务，所以开了「开机后自动恢复」的，重启后要等你下次打开 App 才会补上恢复——任务不会丢，只是晚一点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("下载中保持运行（息屏不降速）")
                        Text(
                            "开着时只有真正在下载的那段时间 CPU 不睡，息屏下大文件速度更稳；" +
                                "代价是更费电。排队、等 WiFi、等充电、定时等待时都不耗这个电。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = keepCpuAwake, onCheckedChange = onKeepCpuAwake)
                }
            }
        }
        if (groupVisible("s-data")) item(key = "s-data") {
            SettingsGroup("引导与数据") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("诊断（本地自检）")
                        Text(
                            "存储、网络、代理、版本一次查清；结果只在本机，可复制全文发给开发者报障",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onShowDiag) { Text("打开") }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("帮助中心")
                        Text(
                            "常见问题：为什么快、文件在哪、续传怎么回事，都在这儿（离线可看）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onShowHelp) { Text("常见问题") }
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
                        "敏感信息不进备份：每个任务的 Cookie、授权头和代理密码都会被剔除，" +
                        "导入后需要登录态的任务要重新从内置浏览器抓一次链接、代理密码要重填一次。" +
                        "下面这个完整迁移包也用「导入备份」导入；旧版备份照常能导，只是没有统计和学习数据部分。",
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
                        Text("导出完整迁移包")
                        Text(
                            "换手机专用：一个 JSON 文件装下设置＋任务＋嗅探历史＋下载统计＋按主机学习数据" +
                                "（每个站的最佳线程数、限流上限），到新手机用上面的「导入备份」选它，一次全恢复。" +
                                "敏感信息同样剔除（Cookie、授权头、代理密码不进迁移包）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onMigrationExport) { Text("导出") }
                }
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
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("已完成任务自动归档")
                Text(
                    "超过这个天数的已完成记录会自动收进主页的「已归档」分类，列表更清爽。" +
                        "只收记录：下载好的文件本体不动、不删，归档的记录随时能在「已归档」里恢复显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(
                        0 to "关", 7 to "7 天", 30 to "30 天", 90 to "90 天",
                    ),
                    selected = archiveDays,
                    onPick = onArchiveDays,
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("清理旧任务记录")
                Text(
                    "把超过所选天数的已完成 / 已失败记录从列表里删掉，列表更清爽。" +
                        "只删记录：已经下好的文件本体不动，没下完的 .part 也不动" +
                        "（.part 用上面的「清理孤儿 .part 文件」清）。" +
                        "清理前会先自动把全部设置＋任务记录备份成一个文件存到下载目录，" +
                        "删错了用「导入备份」就能恢复；备份存失败就一条都不删。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(
                        30 to "30 天", 90 to "90 天", 180 to "180 天",
                    ),
                    selected = cleanupDays,
                    onPick = { cleanupDays = it },
                )
                val cleanupEligible = remember(cleanupDays, cleanupTick) {
                    com.tuxundown.android.core.RecordCleanup.select(
                        DownloadCenter.store.tasks,
                        System.currentTimeMillis(),
                        cleanupDays,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (cleanupEligible.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    "没有符合条件的旧记录",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                cleanupConfirm = cleanupDays to cleanupEligible
                            }
                        },
                        enabled = !cleanupBusy,
                    ) { Text(if (cleanupBusy) "清理中…" else "清理") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "当前符合条件：${cleanupEligible.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (groupVisible("s-about")) item(key = "s-about") {
            SettingsGroup("关于") {
                Text("TuxunDown v$version")
                Text(
                    "极速多线程下载器 · 免费开源（GPLv3）· 无广告 · 不收集、不上传任何数据，" +
                        "所有任务记录只存在你的手机里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // v2.0: the update check is WIRED now — it asks
                // GitHub for the latest formal release, compares
                // versions in-app, and can download the new APK
                // with the app's own engine. It runs only when
                // tapped (see UpdateCheck's doc).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startUpdateCheck() },
                        enabled = !updateBusy,
                    ) { Text(if (updateBusy) "检查中…" else "检查更新") }
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(UpdateCheck.RELEASES_PAGE_URL),
                                )
                            )
                        }
                    }) { Text("打开 GitHub 发布页") }
                }
                if (updateText.isNotBlank()) {
                    Text(
                        updateText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val l = updateLatest
                if (l != null) {
                    if (l.notes.isNotBlank()) {
                        Text(
                            "更新说明：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            l.notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Button(
                        onClick = { downloadUpdate() },
                        modifier = Modifier.padding(top = 10.dp),
                    ) { Text("下载更新（${l.apkName}）") }
                    Text(
                        "用本 App 自己的引擎下载，存到公共「下载」目录；" +
                            "下完回到主页，点任务卡上的「安装」完成升级，任务记录都保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "检查更新只在你点按钮时联网查一次，平时不联网、不自动下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("更新日志")
                        Text(
                            "从 1.2 到现在，每个版本变了什么，一页看完（离线，不用联网）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { showChangelog = true }) { Text("查看") }
                }
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
    // v3.4: old-record cleanup confirmation — exact counts,
    // what is NOT deleted, and the backup-first rule, all
    // stated before anything happens.
    // v3.6: in-app changelog (About -> 更新日志) — bundled
    // offline text from core/Changelog, one line per version.
    if (showChangelog) {
        AlertDialog(
            onDismissRequest = { showChangelog = false },
            title = { Text("更新日志") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (e in Changelog.entries) {
                        Text(
                            "v${e.version}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            e.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChangelog = false }) { Text("关闭") }
            },
        )
    }
    if (cleanupConfirm != null) {
        val (cDays, victims) = cleanupConfirm!!
        val doneN = victims.count {
            it.status == com.tuxundown.android.core.TaskStatus.DONE
        }
        val failN = victims.size - doneN
        AlertDialog(
            onDismissRequest = { cleanupConfirm = null },
            title = { Text("清理旧任务记录") },
            text = {
                Text(
                    "将删除 $cDays 天前的 ${victims.size} 条记录" +
                        "（已完成 $doneN 条、失败 $failN 条）。\n" +
                        "只删记录：已下载的文件本体不动，没下完的 .part 也不动。\n" +
                        "开始前会先把全部设置＋任务记录备份成文件存到下载目录" +
                        "（文件名带 before-cleanup），删错了用上面的「导入备份」选它就能恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cleanupConfirm = null
                    startCleanup(cDays)
                }) { Text("开始清理") }
            },
            dismissButton = {
                TextButton(onClick = { cleanupConfirm = null }) { Text("取消") }
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
    // v2.0 font-scale fix: at the system maximum font scale the
    // chips outgrow a narrow screen; the row scrolls sideways
    // instead of clipping the last options off-screen.
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .horizontalScroll(rememberScrollState()),
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
 * v2.2 crash report page: shown on the launch AFTER a crash,
 * before the normal UI. Deliberately built from the plainest
 * widgets and nothing that can fail — this page is the rescue
 * path, it must always render. The log stays on the phone;
 * 复制日志 only puts it on the clipboard so the USER can choose
 * to send it to the developer.
 */
@androidx.compose.runtime.Composable
fun CrashReportScreen(info: CrashGuard.CrashInfo, onContinue: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
            )
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        androidx.compose.foundation.Image(
            // Raster copy, NOT R.mipmap.ic_launcher (adaptive-icon
            // XML crashes painterResource on API 26+ — the v1.3.0
            // lesson, and this page above all must not crash).
            painter = androidx.compose.ui.res.painterResource(R.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "上次打开时，应用闪退了",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "大概原因：${info.summary}。\n" +
                "你的下载任务和文件都没有丢，点「继续打开」就能接着用。\n\n" +
                "如果这个毛病反复出现，把崩溃日志发给开发者能帮我们尽快修好。" +
                "日志只存在你的手机里，应用不会自动上传任何东西——发不发、发给谁，都由你决定。",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (info.safeMode) {
            Spacer(Modifier.height(12.dp))
            Text(
                "已经连续闪退 ${info.streak} 次，这次打开进入了安全模式：" +
                    "新手引导和剪贴板提示先跳过，避免同一个地方反复出问题。",
                style = MaterialTheme.typography.bodyLarge,
                color = WinPalette.Red,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        val cm = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE
                        ) as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("TuxunDown 崩溃日志", info.detail)
                        )
                        Toast.makeText(context, "崩溃日志已复制，去粘贴发送就行", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
            ) { Text("复制日志") }
            Button(onClick = onContinue) { Text("继续打开") }
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
            "· 手机温控看得见了：诊断页的一键体检新增「设备温控」一项，把手机当前的发热状态分成正常、偏热、发热降速中三档显示；要是这台手机读不到温控状态，这一项就不显示，不会拿假数据糊弄\n" +
            "· 下载中发热会提醒你：正在下载时如果手机已经发热降速，通知里会多写一句，提醒这时速度变慢可能不是网络的问题；只是提醒，不会自动暂停、也不会自动降速，下载照常进行\n" +
            "· 「下载中保持运行」开关现在即时生效：下载途中改这个开关，约一秒内就按新设置走，不用等当前任务下完；另外桌面启动器图标的字形略微缩小了一点，看着更协调",
        "核心用法" to
            "点「新建下载」，把链接粘进来就行：\n" +
            "· 文件直链 → 直接开始多线程下载，还能预先改好保存文件名\n" +
            "· 网页链接 → 自动嗅探出页面里的资源，勾选后批量下载\n" +
            "· 一行一个链接、粘多行 → 批量新建\n" +
            "· 别人发来的链接清单 → 整段粘进来，整队任务一次建回来（清单在多选任务或主页就能分享出去）\n" +
            "下好的文件都在手机公共「下载」目录里，文件管理器第一层就能找到。",
        "提速小贴士" to
            "· 优先用 WiFi，并尽量靠近路由器\n" +
            "· 同一个文件浏览器下很慢、这里快很多，是正常现象：浏览器只有一条连接\n" +
            "· 如果加连接后速度不再涨，卡片上会明说——那是服务器按总量限速，谁都绕不过去\n" +
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
                // v2.1 safe-area: this dialog is its own window and
                // fills it — pad for the system bars so the title
                // and the navigation buttons stay reachable.
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                )
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
            // v2.0 font-scale fix: the title + body live in a
            // SCROLLABLE area now. At the system maximum font
            // scale the long pages (especially 本版更新） used to
            // push the dots and the navigation buttons off the
            // bottom of small screens.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
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
            }
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
 *  Windows multi-file transfer summary.
 *  v2.6 audit round 12: the numbers come from WidgetState — the
 *  same aggregation the notification and the widget use. This
 *  card used to redo the math in Float, whose precision on
 *  large files could put its percent one point off the other
 *  two surfaces; now all three print the identical number. */
@androidx.compose.runtime.Composable
fun SummaryCard(active: List<TaskRecord>, all: List<TaskRecord>, liveTick: Int) {
    // v4.0: liveTick exists so the caller's item scope subscribes
    // to DownApp's 1 s tick and this function's parameters change
    // every second, so Compose cannot skip it. The body reads the
    // tasks' plain fields, which the engine sampler mutates in
    // place; this (otherwise no-op) read is what lets each
    // recomposition pick up the fresh aggregate values.
    @Suppress("UNUSED_EXPRESSION") liveTick
    val agg = WidgetState.summary(active)
    val totalSize = agg.totalBytes
    val totalDone = agg.doneBytes
    val totalProgress = agg.totalPct / 100f
    val totalSpeed = active.filter { it.status == TaskStatus.RUNNING }.sumOf { it.speedBps }
    // v2.7: 全部暂停 / 全部继续 — the same pure BulkControl
    // decision (and the same DownloadCenter calls) as the
    // quick-settings tile, so the two surfaces never disagree.
    val bulk = BulkControl.action(all)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (active.isNotEmpty()) {
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
                        // The integer percent itself — never a Float
                        // round-trip of it (0.29f × 100 truncates to 28).
                        "已完成 ${agg.totalPct}%",
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
                    // v4.4: append the all-done ETA only when
                    // WidgetState can prove one (eta > 0); an
                    // unknown-size active task or a stalled
                    // speed hides it instead of guessing.
                    // Format.eta carries its own 「大约」 prefix,
                    // so this reads 「… · 全部下完大约 3 分钟」.
                    // liveTick above re-runs this every second,
                    // so the ETA stays as fresh as the speed.
                    buildString {
                        append("总速度：${Format.speed(totalSpeed)}")
                        append(" · ${Format.size(totalDone)} / ${Format.size(totalSize)}")
                        val eta = WidgetState.etaSeconds(active)
                        if (eta > 0) append(" · 全部下完${Format.eta(eta)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // v5.2: the network status line (see
                    // SummaryNetStatus) — current network, dual
                    // channel ONLY when really active, and the
                    // first gate really holding a queued task.
                    // Collected fresh here; liveTick re-runs this
                    // card every second, so it stays as fresh as
                    // the speed line above with no new polling.
                    // Active form ONLY: in the resume form below
                    // every task is user-paused, no gate is
                    // holding anything, and a network line would
                    // just be noise next to 全部继续.
                    DownloadCenter.summaryNetLine(all),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Shown only for the resume-all case (see the call
                // site): an aggregate over zero active tasks would
                // print a meaningless 0% — say the true thing.
                Text(
                    // v5.3 (audit round 38): BulkControl's
                    // verdict — 「任务都暂停了」 only when the
                    // resumable set is really all PAUSED; a
                    // FAILED task is named as failed, not
                    // relabelled paused (see BulkControl
                    // .stoppedTitle).
                    BulkControl.stoppedTitle(all),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (bulk == BulkControl.Action.PAUSE_ALL) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { DownloadCenter.pauseAll() }) {
                    Text("全部暂停")
                }
            } else if (bulk == BulkControl.Action.RESUME_ALL) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { DownloadCenter.resumeAll() }) {
                    Text("全部继续")
                }
            }
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
        // v2.0 font-scale fix: the label used to have a FIXED
        // 20.dp height — at the system maximum font scale
        // bodyMedium needs more than that and the text clipped.
        // Width-only now (a touch wider, since scaled glyphs are
        // wider too) and the height wraps with the text.
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
fun TaskCard(
    task: TaskRecord,
    liveTick: Int,
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
    onVerifyHash: () -> Unit,
    onCancelVerify: () -> Unit,
    onHeaders: () -> Unit,
    onUserNote: () -> Unit,
    onTaskLimit: (Int) -> Unit,
    onPriority: (Int) -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    // v4.0: liveTick exists so the caller's item scope subscribes
    // to DownApp's 1 s tick and this function's parameters change
    // every second, so Compose cannot skip it. The card body reads
    // task's plain fields, which the engine sampler mutates in
    // place; this (otherwise no-op) read is what lets each
    // recomposition pick up the fresh speed/downloaded values.
    @Suppress("UNUSED_EXPRESSION") liveTick
    var expanded by remember(task.id) {
        mutableStateOf(DownloadCenter.detailsExpanded)
    }
    // v3.8: captured in composition so click handlers (which
    // are NOT composable context) can use it — the diagnostics
    // copy button below needs it.
    val context = LocalContext.current
    val pct = WidgetState.taskPercent(task)
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
                    // v2.9 (audit 15): both backgrounds here are
                    // light (Yellow / Green #06B025 ≈ 2.9:1 with
                    // white text) — black text clears 4.5:1 on
                    // both. White stays only on the Red side.
                    color = Color.Black,
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
                            TaskStatus.DONE -> statusTextGreen()
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                when (task.status) {
                    TaskStatus.RUNNING ->
                        IconButton(onClick = onPause) {
                            // v2.0 a11y: the glyphs are bare Canvas
                            // draws — without a content description
                            // a screen reader finds an unnamed
                            // button here.
                            PauseGlyph(
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.semantics { contentDescription = "暂停" },
                            )
                        }
                    TaskStatus.PAUSED, TaskStatus.FAILED ->
                        IconButton(onClick = onResume) {
                            PlayGlyph(
                                MaterialTheme.colorScheme.onSurface,
                                Modifier.semantics { contentDescription = "继续" },
                            )
                        }
                    TaskStatus.DONE ->
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CheckGlyph(WinPalette.ChartGreen)
                        }
                    TaskStatus.QUEUED -> Spacer(Modifier.size(48.dp))
                }
                IconButton(onClick = onDelete) {
                    CloseGlyph(
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.semantics { contentDescription = "删除任务" },
                    )
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
                        if (task.avgBps > 0 && DownloadCenter.cardFieldShown(CardFields.AVG_SPEED)) InfoRow("平均速度：", Format.speed(task.avgBps))
                        if (task.peakBps > 0) InfoRow("峰值速度：", Format.speed(task.peakBps))
                        val eta = Format.eta(task.etaSeconds)
                        InfoRow(
                            "剩余时间：",
                            // v5.3 (audit round 38): etaSeconds is
                            // -1 both for "speed not sampled yet"
                            // and for "size unknown" (every HLS
                            // task, every chunked response) — the
                            // old blanket 计算中… promised a number
                            // that can never come for the second
                            // kind, while every other surface just
                            // hides its ETA there. Say which it is.
                            eta.ifBlank {
                                if (task.totalSize <= 0) "还不知道大小，没法估算" else "计算中…"
                            },
                        )
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
                        if (task.avgBps > 0 && DownloadCenter.cardFieldShown(CardFields.AVG_SPEED)) InfoRow("平均速度：", Format.speed(task.avgBps))
                        if (task.peakBps > 0) InfoRow("峰值速度：", Format.speed(task.peakBps))
                    }
                    TaskStatus.DONE -> {
                        InfoRow("大小：", Format.size(task.totalSize))
                        if (task.avgBps > 0 && DownloadCenter.cardFieldShown(CardFields.AVG_SPEED)) InfoRow("平均速度：", Format.speed(task.avgBps))
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
                        // v2.7: diagnosis advice for this failure
                        // category (pure FailureAdvice) — one
                        // concrete next step, and a button only
                        // when the app can take the user straight
                        // to the operation it names.
                        val advice = FailureAdvice.forTask(task)
                        if (advice != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "建议：${advice.text}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (advice.action != FailureAdvice.Action.NONE) {
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton(onClick = {
                                    when (advice.action) {
                                        FailureAdvice.Action.RETRY -> onResume()
                                        FailureAdvice.Action.OPEN_BROWSER ->
                                            onOpenInBrowser()
                                        FailureAdvice.Action.MANAGE_SOURCES ->
                                            onSources()
                                        FailureAdvice.Action.OPEN_FILES ->
                                            onOpenFiles()
                                        FailureAdvice.Action.NONE -> Unit
                                    }
                                }) { Text(advice.actionLabel) }
                            }
                        }
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
                // v3.7: optional card rows （创建时间 / 来源站点，
                // both default OFF — see CardFields).
                if (DownloadCenter.cardFieldShown(CardFields.CREATED_AT)) {
                    InfoRow("创建时间：", Format.dateTime(task.createdAt))
                }
                if (DownloadCenter.cardFieldShown(CardFields.HOST)) {
                    val cardHost = HostMemory.hostOf(task.url)
                    if (cardHost.isNotBlank()) InfoRow("来源站点：", cardHost)
                }
                // v2.8: queue pre-check warning (see
                // LinkPrecheck). Wording matters: this is a
                // heads-up, NOT a failure — the task will still
                // start on its turn and re-probe for real.
                if (task.status == TaskStatus.QUEUED && task.precheckDead) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "预检提示：这个链接可能已失效（服务器说文件不存在）。" +
                            "轮到它下载时还会再试一次，确认下不了才会判失败。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WinPalette.Red,
                    )
                }
                if (DownloadCenter.cardFieldShown(CardFields.CHUNKS) &&
                    task.chunkSize > 0 && task.totalSize > 0 && task.status != TaskStatus.DONE
                ) {
                    // v4.2: the count comes from the task's own
                    // grid — uniform math undercounts a two-zone
                    // grid's fine head.
                    val total = ChunkPlanner.chunkCount(
                        ChunkPlanner.gridOf(
                            task.totalSize, task.chunkSize,
                            task.fineChunkSize, task.fineChunkCount,
                        )
                    )
                    InfoRow(
                        "分片：",
                        // v5.3 (audit round 38): the count is
                        // TaskRecord.displayConnections — while
                        // RUNNING it is the sampler's live count,
                        // not the scaler target threads holds
                        // (the connCapHeld line below calls
                        // threads 想要 N 条； printing it here as
                        // the live count contradicted that line
                        // on the same card).
                        "已完成 ${task.chunkDone.count { it }}/$total 块 · ${task.displayConnections} 条连接",
                    )
                    // v3.5: the chunk progress map. ACTIVE
                    // cells come from the engine's per-second
                    // connection snapshot (empty while paused —
                    // then nothing is bright, honestly).
                    Spacer(Modifier.height(6.dp))
                    ChunkMapView(
                        cells = ChunkMap.cells(
                            task.chunkDone,
                            task.connInfos.map { it.chunkIndex }.toSet(),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "分片图：绿 = 已下完 · 亮绿 = 正在下 · 半绿 = 这一格下了一部分 · 灰 = 还没下" +
                            if (task.chunkDone.size > ChunkMap.MAX_CELLS) {
                                "（分片较多，每格代表连续的 " +
                                    "${(task.chunkDone.size + ChunkMap.MAX_CELLS - 1) / ChunkMap.MAX_CELLS} 块）"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v1.6: HLS merge progress + multi-source state.
                if (DownloadCenter.cardFieldShown(CardFields.CHUNKS) &&
                    task.isHls && task.hlsTotalSegments > 0
                ) {
                    InfoRow(
                        "流媒体分片：",
                        "已合并 ${task.hlsDone.count { it }}/${task.hlsTotalSegments} 段",
                    )
                    // v3.5: the HLS map is drawn by MERGED
                    // prefix (see ChunkMap.hlsCells) — that is
                    // the only progress the record vouches for.
                    if (task.status != TaskStatus.DONE) {
                        Spacer(Modifier.height(6.dp))
                        ChunkMapView(
                            cells = ChunkMap.hlsCells(
                                task.hlsDone,
                                running = task.status == TaskStatus.RUNNING,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "流媒体分片图按已合并的进度画：绿 = 已合并 · 亮绿 = 正在处理的一段 · " +
                                "灰 = 还没合并（后面的段可能已在同时下载，合并进度以绿色为准）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // v3.5: a single-stream task has no chunks to
                // draw — say so where the map would have been
                // instead of leaving a silent gap.
                if (DownloadCenter.cardFieldShown(CardFields.CHUNKS) &&
                    !task.isHls && task.chunkSize <= 0 && task.singleThreadOnly &&
                    task.status != TaskStatus.DONE
                ) {
                    Text(
                        if (FtpClient.isFtpUrl(task.url)) {
                            "FTP 是单条数据流下载，没有分片图。"
                        } else {
                            "这个任务是单线程下载（服务器不支持分片），整个文件一条连接从头下到尾，没有分片图。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v3.5: per-connection detail — the engine
                // sampler's snapshot, republished every second
                // on the sampler's existing tick (no new
                // polling; the home list's 1 s refresh while
                // running is what repaints these rows). Only
                // while RUNNING: the snapshot is cleared when
                // the run ends, so a paused or finished task
                // shows nothing here instead of stale rows.
                // HLS downloads segments on parallel
                // connections the record does not track
                // individually, so HLS tasks honestly show no
                // rows (their map above is the progress view).
                if (task.status == TaskStatus.RUNNING && task.connInfos.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "连接明细（每秒刷新）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    task.connInfos.forEachIndexed { i, ci ->
                        InfoRow(
                            if (ci.chunkIndex >= 0) {
                                "连接 ${i + 1}（分片 ${ci.chunkIndex + 1}）："
                            } else {
                                "连接 ${i + 1}（整条流）："
                            },
                            "${Format.speed(ci.speedBps)} · 已下 " +
                                "${Format.size(ci.downloaded)} / ${Format.size(ci.total)}",
                        )
                    }
                }
                // v3.9: the global connection cap is holding
                // this task below the count it would open on
                // its own (see ConnectionBudget) — say so here,
                // with the live numbers, where the effect is
                // visible. connCapHeld is only ever set by the
                // segmented engine; task.threads is the count it
                // is aiming for.
                if (task.status == TaskStatus.RUNNING && task.connCapHeld) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "全局连接上限在分摊：所有任务合计最多 " +
                            "${DownloadCenter.globalConnectionCap} 条连接，这个任务想要 " +
                            "${task.threads} 条、现在 ${task.connInfos.size} 条在跑。" +
                            "其他任务下完会自动补上；也可以到设置 → 下载把上限调高。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.sources.isNotEmpty()) {
                    InfoRow("下载源：", "${1 + task.sources.size} 个地址（含镜像）")
                }
                // v4.6: server profile (see ServerProfile) — a
                // pure aggregation over records the app already
                // keeps; rows with no data are absent by
                // construction, and the whole section is one
                // CardFields switch. The cap is looked up for
                // the host of task.url only, and contribution
                // rows name hosts, never full mirror URLs (the
                // TaskDiagnostics redaction discipline).
                if (DownloadCenter.cardFieldShown(CardFields.SERVER_PROFILE)) {
                    // v5.1 audit round 36: the profile is an
                    // aggregation (per-source grouping + sort, a
                    // host-table lookup) and this card recomposes
                    // every second while visible — memoize it on
                    // exactly the record fields it reads, plus
                    // DownloadCenter.hostMemoryVersion for the
                    // host cap. The sourceBytes fingerprint is
                    // cheap arithmetic over <= MAX_SOURCES
                    // entries (no URL parsing, no grouping), so
                    // the expensive work runs only when an input
                    // actually changed.
                    val sourceBytesFingerprint = synchronized(task.sourceBytes) {
                        task.sourceBytes.entries.fold(0L) { acc, (k, v) ->
                            acc + k.hashCode() + v
                        }
                    }
                    val profileRows = remember(
                        task.id, task.peakBps, task.peakConns,
                        task.totalSize, task.etag, task.lastModified,
                        task.singleThreadOnly, task.startedAt, task.isHls,
                        task.sources.size, sourceBytesFingerprint,
                        DownloadCenter.hostMemoryVersion,
                    ) {
                        com.tuxundown.android.core.ServerProfile.rows(
                            task, DownloadCenter.hostThreadsCap(task.url),
                        )
                    }
                    if (profileRows.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "服务器画像（都是实际下载里记下来的，没数据的就不显示）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (row in profileRows) {
                            InfoRow(row.label, row.value)
                        }
                    }
                }
                // v5.9: connection budget row — right under the
                // server profile, this task's CURRENT granted
                // connection count and the one clamp the code
                // can prove is binding (see ConnectionBudgetLine).
                // RUNNING only: connectionBudgetLineFor returns
                // null in every other state, so a queued / paused
                // / finished task shows no fake figure. The card
                // already recomposes on the 1 s liveTick, and the
                // allowance is recomputed live inside — no new
                // polling here.
                DownloadCenter.connectionBudgetLineFor(task)?.let { line ->
                    InfoRow("连接预算：", line)
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
                // v2.1: per-task speed cap. It composes with the
                // global budget — the smaller limit wins (RateCap)
                // — and the engine's limiter reads the record live,
                // so changing it mid-download applies at once.
                if (task.status != TaskStatus.DONE) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "单任务限速（和全局限速同时设了时，取更小的那个）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow(
                        options = listOf(
                            0 to "不限", 512 to "512KB", 1024 to "1MB",
                            3072 to "3MB", 5120 to "5MB",
                        ),
                        selected = task.taskLimitKBps,
                        onPick = onTaskLimit,
                    )
                }
                // v3.1: three-tier priority. One switch, two
                // effects, both stated on the label: the queue
                // starts higher tiers first, and a shared speed
                // limit splits 高：中：低 = 2 : 1 : 0.5.
                if (task.status != TaskStatus.DONE) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "优先级（排队时先开始高的；多任务一起下又设了限速时，份额按 高：中：低 = 2：1：0.5 分）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow(
                        options = listOf(
                            TaskPriority.HIGH to "高",
                            TaskPriority.NORMAL to "中",
                            TaskPriority.LOW to "低",
                        ),
                        selected = TaskPriority.clamp(task.priority),
                        onPick = onPriority,
                    )
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
                    // v4.5: while VERIFYING, show the live
                    // percent the verifier publishes on the
                    // task （正在校验…N%); when the file size
                    // is unknown the label stays a bare 校验中….
                    val csText =
                        if (task.checksumState == Checksum.STATE_VERIFYING) {
                            Checksum.verifyingLabel(task.checksumProgressPct)
                        } else {
                            // v5.8: stateLabelFor adds the
                            // website-supplied source sentence
                            // for sidecar hashes; the verdict
                            // words stay stateLabel's.
                            Checksum.stateLabelFor(task)
                        }
                    InfoRow(
                        "完整性校验：",
                        "${expectedHash.first} · $csText",
                    )
                }
                if (task.status != TaskStatus.RUNNING) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onChecksum) {
                        Text(if (expectedHash == null) "设置校验哈希" else "改校验哈希")
                    }
                }
                // v2.9: manual verify + cancel. VERIFYING can be
                // entered by the service (auto-verify at finalize)
                // or by this button (hash set late, or the auto
                // switch off) — the cancel flag is process-wide,
                // so 取消校验 stops either one.
                if (task.status == TaskStatus.DONE && expectedHash != null) {
                    Spacer(Modifier.height(6.dp))
                    if (task.checksumState == Checksum.STATE_VERIFYING) {
                        OutlinedButton(onClick = onCancelVerify) {
                            Text("取消校验")
                        }
                    } else {
                        OutlinedButton(onClick = onVerifyHash) {
                            Text("立即校验")
                        }
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
                if (task.retryCount > 0 && DownloadCenter.cardFieldShown(CardFields.RETRY_COUNT)) {
                    InfoRow("重试次数：", "${task.retryCount} 次（分片级）")
                }
                // v2.8: the user's own note — displayed above
                // the system's note line and editable whatever
                // the status (a note on a DONE task is how you
                // remember what the file was FOR). The editor is
                // a dialog, like the headers editor.
                if (task.userNote.isNotBlank()) {
                    InfoRow("备注：", task.userNote)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onUserNote) {
                    Text(if (task.userNote.isBlank()) "加备注" else "改备注")
                }
                // v3.8: copy a redacted per-task diagnostic for
                // bug reports (see TaskDiagnostics — link query
                // and header VALUES never leave the phone).
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    val ctx = context
                    val version = runCatching {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                            .versionName
                    }.getOrNull() ?: "6.1.0"
                    val cm = ctx.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE,
                    ) as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "TuxunDown 任务诊断",
                            com.tuxundown.android.core.TaskDiagnostics.build(
                                task, version, DownloadCenter.userAgent,
                            ),
                        ),
                    )
                    Toast.makeText(
                        ctx,
                        "诊断信息已复制（链接已去参数、请求头只有名字没有值）",
                        Toast.LENGTH_LONG,
                    ).show()
                }) {
                    Text("复制诊断信息")
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
                    // v4.5: export the retained speed curve as
                    // CSV into the download folder (same door as
                    // the sniff-list export). A task that kept no
                    // samples gets a plain explanation, never an
                    // empty file; a write failure is shown, never
                    // swallowed.
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        val ctx = context
                        val csv = SpeedCurveExport.buildCsv(task)
                        if (csv == null) {
                            Toast.makeText(
                                ctx,
                                "这个任务没有留下速度曲线，没法导出",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val name = SpeedCurveExport.exportFileName(
                                task.savedName.ifBlank { task.fileName },
                            )
                            Thread {
                                val saved = runCatching {
                                    PublicStorage.saveTextFile(ctx, name, csv)
                                }
                                android.os.Handler(
                                    android.os.Looper.getMainLooper(),
                                ).post {
                                    saved.onSuccess { usedName ->
                                        Toast.makeText(
                                            ctx,
                                            "已导出 $usedName 到下载目录",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }.onFailure {
                                        Toast.makeText(
                                            ctx,
                                            "导出失败：${Errors.human(it)}",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }.start()
                        }
                    }) {
                        Text("导出速度曲线（CSV）")
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
/**
 * v3.5: chunk progress map — a grid of cells, one per chunk
 * (or per aggregated bucket past [ChunkMap.MAX_CELLS]
 * chunks; the aggregation rule lives in ChunkMap). Hand-
 * drawn like the other charts: solid green = done, bright
 * green = downloading right now, translucent green = this
 * bucket is partly done, grey = not started yet.
 */
@androidx.compose.runtime.Composable
fun ChunkMapView(cells: List<ChunkMap.Cell>, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) return
    val dark = isSystemInDarkTheme()
    val pendingColor = if (dark) WinPalette.TrackDark else WinPalette.TrackLight
    val cols = 12
    val rows = (cells.size + cols - 1) / cols
    Canvas(modifier.height((rows * 14 + (rows - 1) * 3).dp)) {
        val gap = 3.dp.toPx()
        val cellW = (size.width - gap * (cols - 1)) / cols
        val cellH = 14.dp.toPx()
        cells.forEachIndexed { i, cell ->
            val x = (i % cols) * (cellW + gap)
            val y = (i / cols) * (cellH + gap)
            val color = when (cell.state) {
                ChunkMap.State.DONE -> WinPalette.Green
                ChunkMap.State.ACTIVE -> WinPalette.GreenLight
                ChunkMap.State.PARTIAL -> WinPalette.ChartGreen.copy(
                    alpha = 0.35f + 0.65f *
                        (cell.done.toFloat() / cell.total.coerceAtLeast(1)),
                )
                ChunkMap.State.PENDING -> pendingColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(cellW, cellH),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

/**
 * v2.7: last-7-days download volume bars, hand-drawn in the
 * same visual language as [SpeedChart] (same background, grid,
 * border and green). The last column is today.
 */
@androidx.compose.runtime.Composable
fun WeekChart(days: List<DaySeries.Day>, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val barColor = WinPalette.ChartGreen
    val gridColor = if (dark) Color(0xFF3A3A3A) else Color(0xFFDDDDDD)
    val borderColor = if (dark) Color(0xFF4A4A4A) else Color(0xFFBDBDBD)
    val bg = if (dark) Color(0xFF202020) else Color(0xFFFCFEFC)
    val maxBytes = (days.maxOfOrNull { it.bytes } ?: 0).coerceAtLeast(1)
    val topLabel = Format.size(maxBytes) + " 最高一天"
    val labelColor = if (dark) 0xFFAAAAAA.toInt() else 0xFF767676.toInt()
    Canvas(modifier.background(bg)) {
        val w = size.width
        val h = size.height
        val labelH = 14.dp.toPx()
        val bottomH = 12.dp.toPx()
        val plotTop = labelH
        val plotH = h - labelH - bottomH
        for (i in 0..4) {
            val y = plotTop + plotH * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        drawRect(
            borderColor,
            topLeft = Offset(0f, plotTop),
            size = Size(w, plotH),
            style = Stroke(width = 1f),
        )
        val paint = android.graphics.Paint().apply {
            color = labelColor
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(topLabel, 4.dp.toPx(), 11.dp.toPx(), paint)
        if (days.isEmpty()) return@Canvas
        val colW = w / days.size
        val barW = colW * 0.55f
        days.forEachIndexed { i, day ->
            val frac = day.bytes.toFloat() / maxBytes.toFloat()
            val barH = plotH * frac
            val x = i * colW + (colW - barW) / 2f
            if (barH > 0f) {
                drawRect(
                    barColor,
                    topLeft = Offset(x, plotTop + plotH - barH),
                    size = Size(barW, barH),
                )
            }
            // "11-22" style label centred under the column;
            // today gets 今天 instead of its date.
            val label = if (i == days.lastIndex) "今天" else day.key.drop(5)
            val tw = paint.measureText(label)
            drawContext.canvas.nativeCanvas.drawText(
                label, i * colW + (colW - tw) / 2f, h - 2.dp.toPx(), paint,
            )
        }
    }
}

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

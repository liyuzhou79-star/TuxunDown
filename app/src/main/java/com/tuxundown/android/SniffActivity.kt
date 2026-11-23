package com.tuxundown.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.tuxundown.android.core.CreateDedup
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.Errors
import com.tuxundown.android.core.LongPressLink
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.sniff.SniffResult
import com.tuxundown.android.sniff.Sniffer

/**
 * Sniffer layer 3: a minimal built-in browser. The user taps through the
 * page by hand; when a real download starts, the WebView download listener
 * captures the true URL together with the session Cookie, the page
 * Referer and the browser User-Agent, and hands it to the download engine.
 *
 * v1.4 (item 17): a floating "用 TuxunDown 下载" button hovers over the
 * page — tapping it sniffs the CURRENT page on the spot: a direct file
 * (or a page that resolves to one) becomes a task immediately, and a
 * resource list is offered as a picker.
 */
class SniffActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        val startUrl = intent.getStringExtra(EXTRA_URL) ?: ""

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(this).apply {
            text = "嗅探浏览器：在页面里点到下载那一步，抓到真实地址后会自动新建下载任务；" +
                "也可以随时点右下角的绿色按钮，直接嗅探当前页。"
            setPadding(24, 16, 24, 16)
        }
        root.addView(hint)

        // v7.2 (audit round 57): a way OUT of a sniff-browser
        // login. CookieManager persists the cookies of every
        // site the user signed into here, and until now there
        // was no clear path anywhere — a login lived forever.
        // This button wipes the browser's cookies and web
        // storage; the feedback toast says plainly that sites
        // will ask for a fresh login afterwards.
        val clearLogin = Button(this).apply {
            text = "清除浏览器登录状态"
            setOnClickListener {
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies {
                        runCatching { cm.flush() }
                    }
                    android.webkit.WebStorage.getInstance().deleteAllData()
                }
                Toast.makeText(
                    this@SniffActivity,
                    "已清除浏览器登录状态（Cookie 和网页存储），网站会要求重新登录",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        root.addView(clearLogin)

        val frame = FrameLayout(this)
        frame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        // v1.5 startup audit: WebView construction throws on devices
        // whose WebView provider is missing/disabled mid-update. This
        // is a first-frame path — degrade to a plain explanation and
        // leave, never crash the launch.
        web = runCatching { WebView(this) }.getOrElse {
            Toast.makeText(
                this,
                "内置浏览器不可用（系统 WebView 异常），请改用粘贴链接或嗅探网页的方式下载",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        // v2.3 audit round 9: file/content access default to TRUE
        // and this browser never needs local files — turn both off
        // explicitly, so a hostile page cannot even attempt a
        // file:// or content:// read through some path the
        // navigation gate below might miss.
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        // v7.2 (audit round 57): mixed content is refused
        // EXPLICITLY. The platform default at targetSdk 35 is
        // already NEVER_ALLOW, so behaviour does not change —
        // the setting is written out so the rule is visible to
        // audit instead of living in a platform default a future
        // targetSdk bump could quietly move.
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // v1.9 audit: navigation is gated to web schemes. A page
        // (or an ad inside it) trying to steer the WebView at
        // intent://, file://, tel: and friends used to just fail
        // weirdly or leave the browser; now it is stopped with a
        // plain explanation. There is deliberately NO Javascript
        // bridge (addJavascriptInterface) — page JS can never call
        // into the app.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean {
                val u = request.url?.toString() ?: return true
                val scheme = u.substringBefore(':').lowercase()
                return if (scheme == "http" || scheme == "https" || scheme == "about") {
                    false
                } else {
                    Toast.makeText(
                        this@SniffActivity,
                        "页面想打开非网页链接（$scheme），已拦下",
                        Toast.LENGTH_SHORT,
                    ).show()
                    true
                }
            }
        }
        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            // v1.9 audit: the WebView also reports blob:/data:
            // "downloads" — the engine cannot fetch those at all,
            // so don't create a task that can only fail confusingly.
            if (!isDownloadableUrl(url)) {
                Toast.makeText(
                    this,
                    LongPressLink.notDownloadableMessage(url),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                val headers = mutableMapOf<String, String>()
                headers["User-Agent"] = userAgent ?: ""
                runCatching { CookieManager.getInstance().getCookie(url) }
                    .getOrNull()?.let { headers["Cookie"] = it }
                web.url?.let { headers["Referer"] = it }
                createTaskSmart(url, headers) { outcome ->
                    when (outcome) {
                        true -> {
                            Toast.makeText(this, "已抓到真实地址，开始下载", Toast.LENGTH_LONG).show()
                            finish()
                        }
                        false -> {
                            Toast.makeText(this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG).show()
                            finish()
                        }
                        // Cancelled the quality pick: stay in
                        // the browser, say nothing.
                        null -> Unit
                    }
                }
            }
        }
        frame.addView(web)

        // ---- long-press a link: download THAT link (v1.9) ----
        // The hit test tells us the URL under the finger; the task
        // carries the same session headers a real download tap
        // would have captured (Cookie / Referer / UA). The browser
        // stays open afterwards — the user may want more links.
        web.setOnLongClickListener {
            val hit = web.hitTestResult
            val link = hit.extra
            val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            // v6.5 audit round 50 (A-F1): the verdict lives in
            // LongPressLink. A hit on a link the engine cannot
            // fetch used to fall through silently — the press
            // looked dead. It now gets the SAME explanation the
            // download listener above shows for the same link.
            when (LongPressLink.actionFor(isLink, link)) {
                LongPressLink.Action.CONFIRM_DOWNLOAD -> {
                    val target = link!!
                    AlertDialog.Builder(this)
                        .setTitle("下载这个链接？")
                        .setMessage(target)
                        .setPositiveButton("用 TuxunDown 下载") { _, _ ->
                            val headers = mutableMapOf<String, String>()
                            headers["User-Agent"] = web.settings.userAgentString ?: ""
                            runCatching { CookieManager.getInstance().getCookie(target) }
                                .getOrNull()?.let { headers["Cookie"] = it }
                            web.url?.let { headers["Referer"] = it }
                            createTaskSmart(target, headers) { outcome ->
                                when (outcome) {
                                    true -> Toast.makeText(
                                        this, "已新建下载任务", Toast.LENGTH_LONG,
                                    ).show()
                                    false -> Toast.makeText(
                                        this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG,
                                    ).show()
                                    null -> Unit
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
                LongPressLink.Action.EXPLAIN_NOT_DOWNLOADABLE -> {
                    Toast.makeText(
                        this,
                        LongPressLink.notDownloadableMessage(link ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
                LongPressLink.Action.IGNORE -> false
            }
        }

        // ---- floating take-over button (v1.4 item 17) ----
        val fab = Button(this).apply {
            text = "⬇ 用 TuxunDown 下载"
            // v6.2 audit round 47: white on this green is
            // 2.90:1 (fails even the 3:1 graphics bar); black
            // is 7.24:1 — the same fix the v2.9 swipe labels
            // got, pinned in A11yContrastTest.
            setTextColor(Color.BLACK)
            textSize = 15f
            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(Color.rgb(6, 176, 37)) // Windows transfer green
            }
            setPadding(36, 20, 36, 20)
            setOnClickListener { sniffCurrentPage() }
        }
        val fabParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 36, 48)
        }
        frame.addView(fab, fabParams)

        root.addView(frame)
        // v2.1 safe-area pass: on Android 15 (targetSdk 35) this
        // window is drawn edge-to-edge — without insets the hint
        // text slides under the status bar and the floating
        // download button under the gesture bar. Pad the root by
        // the system bars; the WebView area shrinks accordingly,
        // which is exactly the pre-15 layout.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        // v1.9 audit: after a rotation the WebView's back stack
        // and page are restored instead of throwing away the page
        // the user had tapped their way through.
        val restored = savedInstanceState?.let {
            runCatching { web.restoreState(it) }.getOrNull()
        }
        if (restored == null && startUrl.isNotBlank()) {
            // v2.8 audit round 14: loadUrl() does NOT pass
            // through shouldOverrideUrlLoading — the navigation
            // gate below never sees the FIRST load. Gate it
            // here: only real web pages may open (an ftp://
            // task URL or a javascript:/file: string typed into
            // the add dialog gets a plain explanation instead
            // of a WebView error page or worse).
            val scheme = startUrl.substringBefore(':').lowercase()
            if (scheme == "http" || scheme == "https") {
                runCatching { web.loadUrl(startUrl) }
            } else {
                Toast.makeText(
                    this,
                    "内置浏览器只能打开 http / https 网页，这个链接（$scheme）打不开",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized) runCatching { web.saveState(outState) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (::web.isInitialized) runCatching { web.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) runCatching { web.onResume() }
    }

    override fun onDestroy() {
        // v1.9 audit: the WebView was NEVER destroyed before —
        // its renderer (and any page JS) outlived the screen.
        if (::web.isInitialized) runCatching { web.destroy() }
        super.onDestroy()
    }

    /**
     * v5.7 audit round 42 (item 5): the session headers a task
     * created from inside this browser carries — the same set
     * the download listener and the long-press path capture:
     * the browser UA, the Cookie CookieManager holds FOR THE
     * TARGET URL (per-domain by construction), and the page the
     * user is standing on as Referer. The engine still scopes
     * the Cookie per hop via SessionScope; capturing it here is
     * what gives that scoping something to send on same-host
     * hops.
     */
    private fun sessionHeaders(targetUrl: String, referer: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = web.settings.userAgentString ?: ""
        runCatching { CookieManager.getInstance().getCookie(targetUrl) }
            .getOrNull()?.let { headers["Cookie"] = it }
        if (referer.isNotBlank()) headers["Referer"] = referer
        return headers
    }

    /** Only schemes the download engine can actually fetch. */
    private fun isDownloadableUrl(url: String): Boolean =
        LongPressLink.isDownloadable(url)

    /**
     * v3.8: single-create with the HLS 清晰度 choice, for every
     * create path in this browser. Non-HLS links (and HLS links
     * whose probe finds no real choice — media playlist, one
     * variant, probe failed) report synchronously. A master
     * playlist with 2+ variants raises the picker first;
     * [report] then receives true (created), false (already in
     * the list) or null (user cancelled the pick — callers
     * stay silent and stay on the page). The probe carries the
     * same session headers the task itself would, so a
     * login-walled playlist resolves the same way.
     */
    private fun createTaskSmart(
        url: String,
        headers: Map<String, String> = emptyMap(),
        report: (Boolean?) -> Unit,
    ) {
        if (!com.tuxundown.android.core.DownloadEngine.isHlsUrl(url)) {
            report(createTask(url, headers))
            return
        }
        Thread {
            val variants = com.tuxundown.android.core.HlsProbe.fetchVariants(
                url, headers, DownloadCenter.userAgent,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (variants == null || variants.size <= 1) {
                    report(createTask(url, headers))
                    return@runOnUiThread
                }
                val sorted = com.tuxundown.android.core.Hls.sortedForChoice(variants)
                var reported = false
                fun done(outcome: Boolean?) {
                    if (!reported) {
                        reported = true
                        report(outcome)
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("选择清晰度")
                    .setItems(
                        sorted.map {
                            com.tuxundown.android.core.Hls.variantLabel(it)
                        }.toTypedArray()
                    ) { _, which ->
                        val v = sorted[which]
                        // v8.1 audit round 66: the session
                        // headers were captured for the MASTER
                        // url, but the task's primary becomes
                        // the VARIANT url — and the engine
                        // scopes credentials by the task's
                        // primary, so an unscoped copy would
                        // re-own the master host's Cookie to a
                        // variant host (often a CDN) that has
                        // no claim on it. Scope the set to the
                        // variant first (SessionScope.scopedFor);
                        // a same-host variant keeps everything.
                        val ok = createTask(
                            v.url,
                            com.tuxundown.android.core.SessionScope.scopedFor(
                                headers, url, v.url,
                            ),
                        )
                        if (ok) {
                            // Force the HLS flag (a variant URL
                            // need not end in .m3u8) and record
                            // the chosen quality on the card.
                            DownloadCenter.store.tasks
                                .firstOrNull { it.url == v.url }?.let { t ->
                                    t.isHls = true
                                    t.note = "已选清晰度：" +
                                        com.tuxundown.android.core.Hls.variantLabel(v)
                                    DownloadCenter.store.save()
                                }
                        }
                        done(ok)
                    }
                    .setNegativeButton("取消") { _, _ -> done(null) }
                    .setOnCancelListener { done(null) }
                    .show()
            }
        }.start()
    }

    /** Create a task unless the same link is already active. */
    private fun createTask(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        // v2.8 audit round 14: same intake gate as the home
        // screen (see LinkSchemes) — a page can hand the
        // download listener or the long-press handler an
        // absolute file:// / content:// URL, and isDownloadableUrl
        // above already filters those two call sites; this is
        // the funnel every path ends in, so the rule sits here
        // too, in depth.
        if (!com.tuxundown.android.core.LinkSchemes.isSupported(url)) return false
        // v5.4: the ONE dedup verdict (see CreateDedup), same as
        // every other creation funnel — no record, no enqueue
        // on a block; the callers show the "already in the
        // list" toast for a false return.
        if (CreateDedup.isDuplicate(DownloadCenter.store.tasks, url)) return false
        // v1.6: newTask also flags .m3u8 links as HLS tasks.
        val task = DownloadCenter.newTask(url, headers)
        DownloadCenter.store.add(task)
        DownloadCenter.enqueue(task)
        return true
    }

    private fun sniffCurrentPage() {
        val pageUrl = web.url ?: ""
        if (pageUrl.isBlank()) {
            Toast.makeText(this, "页面还没打开", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在嗅探当前页…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { Sniffer().sniff(pageUrl) }
                .getOrElse { SniffResult.Failure(Errors.human(it)) }
            runOnUiThread {
                // v1.9 audit: the user may have backed out while
                // the sniff was running — popping a dialog on a
                // dead activity throws BadTokenException, so a
                // late result is dropped quietly.
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is SniffResult.DirectFile -> {
                        // v5.7 audit round 42 (item 5): the FAB
                        // path used to create the task with NO
                        // headers at all, while the download
                        // listener and the long-press path both
                        // capture the session (Cookie / Referer /
                        // UA) — a login-walled direct file found
                        // from inside the logged-in browser then
                        // failed as if the user had never logged
                        // in. Same capture here: the Cookie is
                        // read for the TARGET url (CookieManager
                        // scopes it per domain), Referer is the
                        // page the user is standing on.
                        createTaskSmart(result.url, sessionHeaders(result.url, pageUrl)) { outcome ->
                            when (outcome) {
                                true -> Toast.makeText(
                                    this, "已新建下载任务", Toast.LENGTH_LONG,
                                ).show()
                                false -> Toast.makeText(
                                    this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG,
                                ).show()
                                null -> Unit
                            }
                        }
                    }
                    is SniffResult.ResourceList -> {
                        DownloadCenter.rememberSniffedPage(result.pageUrl)
                        val names = result.resources.map {
                            it.name + if (it.looksLikeDownloadEntry) "（疑似下载入口）" else ""
                        }.toTypedArray()
                        // v4.3: say it when the list is only part
                        // of the page (parse budget stopped early,
                        // or more resources than the shown cap).
                        val partial = result.truncated ||
                            result.totalCount > result.resources.size
                        val dialog = AlertDialog.Builder(this)
                            .setTitle("这个页面里的资源（点一个下载）")
                        if (partial) {
                            dialog.setMessage(
                                "这个页面资源太多，只列出了一部分（先找到的排在前面）"
                            )
                        }
                        dialog
                            .setItems(names) { _, which ->
                                val res = result.resources[which]
                                // v5.7 (item 5): same session
                                // capture as the DirectFile arm.
                                createTaskSmart(
                                    res.url, sessionHeaders(res.url, pageUrl),
                                ) { outcome ->
                                    when (outcome) {
                                        true -> Toast.makeText(
                                            this, "已新建下载任务", Toast.LENGTH_LONG,
                                        ).show()
                                        false -> Toast.makeText(
                                            this, "这个链接已经在任务列表里了",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        null -> Unit
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    is SniffResult.Failure ->
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}

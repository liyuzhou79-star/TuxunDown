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
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
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
                    "抓到的不是能下载的链接（${url.substringBefore(':').lowercase()}），已忽略",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                val headers = mutableMapOf<String, String>()
                headers["User-Agent"] = userAgent ?: ""
                runCatching { CookieManager.getInstance().getCookie(url) }
                    .getOrNull()?.let { headers["Cookie"] = it }
                web.url?.let { headers["Referer"] = it }
                if (createTask(url, headers)) {
                    Toast.makeText(this, "已抓到真实地址，开始下载", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG).show()
                    finish()
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
            if (isLink && !link.isNullOrBlank() && isDownloadableUrl(link)) {
                AlertDialog.Builder(this)
                    .setTitle("下载这个链接？")
                    .setMessage(link)
                    .setPositiveButton("用 TuxunDown 下载") { _, _ ->
                        val headers = mutableMapOf<String, String>()
                        headers["User-Agent"] = web.settings.userAgentString ?: ""
                        runCatching { CookieManager.getInstance().getCookie(link) }
                            .getOrNull()?.let { headers["Cookie"] = it }
                        web.url?.let { headers["Referer"] = it }
                        if (createTask(link, headers)) {
                            Toast.makeText(this, "已新建下载任务", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            } else {
                false
            }
        }

        // ---- floating take-over button (v1.4 item 17) ----
        val fab = Button(this).apply {
            text = "⬇ 用 TuxunDown 下载"
            setTextColor(Color.WHITE)
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
        setContentView(root)
        // v1.9 audit: after a rotation the WebView's back stack
        // and page are restored instead of throwing away the page
        // the user had tapped their way through.
        val restored = savedInstanceState?.let {
            runCatching { web.restoreState(it) }.getOrNull()
        }
        if (restored == null && startUrl.isNotBlank()) {
            runCatching { web.loadUrl(startUrl) }
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

    /** Only schemes the download engine can actually fetch. */
    private fun isDownloadableUrl(url: String): Boolean {
        val scheme = url.substringBefore(':').lowercase()
        return scheme == "http" || scheme == "https" || scheme == "ftp"
    }

    /** Create a task unless the same link is already active. */
    private fun createTask(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        val dup = DownloadCenter.store.tasks.any {
            it.url == url && it.status != TaskStatus.DONE && it.status != TaskStatus.FAILED
        }
        if (dup) return false
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
                .getOrElse { SniffResult.Failure(it.message ?: "嗅探失败") }
            runOnUiThread {
                // v1.9 audit: the user may have backed out while
                // the sniff was running — popping a dialog on a
                // dead activity throws BadTokenException, so a
                // late result is dropped quietly.
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is SniffResult.DirectFile -> {
                        if (createTask(result.url)) {
                            Toast.makeText(this, "已新建下载任务", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG).show()
                        }
                    }
                    is SniffResult.ResourceList -> {
                        DownloadCenter.rememberSniffedPage(result.pageUrl)
                        val names = result.resources.map {
                            it.name + if (it.looksLikeDownloadEntry) "（疑似下载入口）" else ""
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("这个页面里的资源（点一个下载）")
                            .setItems(names) { _, which ->
                                val res = result.resources[which]
                                if (createTask(res.url)) {
                                    Toast.makeText(this, "已新建下载任务", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG).show()
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

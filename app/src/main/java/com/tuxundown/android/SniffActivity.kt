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
        web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.webViewClient = WebViewClient()
        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val headers = mutableMapOf<String, String>()
            headers["User-Agent"] = userAgent ?: ""
            CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
            web.url?.let { headers["Referer"] = it }
            if (createTask(url, headers)) {
                Toast.makeText(this, "已抓到真实地址，开始下载", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "这个链接已经在任务列表里了", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        frame.addView(web)

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
        if (startUrl.isNotBlank()) web.loadUrl(startUrl)
    }

    /** Create a task unless the same link is already active. */
    private fun createTask(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        val dup = DownloadCenter.store.tasks.any {
            it.url == url && it.status != TaskStatus.DONE && it.status != TaskStatus.FAILED
        }
        if (dup) return false
        val task = TaskRecord(url = url, headers = headers.toMutableMap())
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

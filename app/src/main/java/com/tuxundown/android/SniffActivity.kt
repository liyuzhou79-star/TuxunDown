package com.tuxundown.android

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.tuxundown.android.core.DownloadCenter
import com.tuxundown.android.core.TaskRecord

/**
 * Sniffer layer 3: a minimal built-in browser. The user taps through the
 * page by hand; when a real download starts, the WebView download listener
 * captures the true URL together with the session Cookie, the page
 * Referer and the browser User-Agent, and hands it to the download engine.
 */
class SniffActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadCenter.init(this)
        val startUrl = intent.getStringExtra(EXTRA_URL) ?: ""

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(this).apply {
            text = "嗅探浏览器：在页面里点到下载那一步，抓到真实地址后会自动新建下载任务。"
            setPadding(24, 16, 24, 16)
        }
        root.addView(hint)
        val web = WebView(this)
        web.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
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
            val task = TaskRecord(url = url, headers = headers)
            DownloadCenter.store.add(task)
            DownloadCenter.enqueue(task)
            Toast.makeText(this, "已抓到真实地址，开始下载", Toast.LENGTH_LONG).show()
            finish()
        }
        root.addView(web)
        setContentView(root)
        if (startUrl.isNotBlank()) web.loadUrl(startUrl)
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}

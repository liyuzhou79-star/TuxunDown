package com.tuxundown.android.core

/**
 * User-visible error wording (v2.2, audit round 8) — pure,
 * unit-tested.
 *
 * The rule for anything a user can read on a card, a toast or a
 * dialog: no raw exception class names, no English library
 * messages, no bare HTTP numbers. Every failure becomes one
 * plain sentence that says what happened AND what to try next.
 *
 * Two entry points:
 * - [httpMessage] for "the server answered with code N" — the
 *   number stays (it helps support), but it is never alone:
 *   each common code carries its plain meaning and a next step.
 * - [human] for a caught exception: an exception whose message
 *   is already plain Chinese (most of this app's own
 *   IllegalStateExceptions are written that way) passes through
 *   untouched; anything else is mapped by TYPE to a plain
 *   sentence, and the original English/class text is dropped.
 */
object Errors {

    fun httpMessage(code: Int): String = when (code) {
        400 -> "服务器没听懂这个请求（HTTP 400），链接可能不完整或已过期，换个链接再试"
        401, 403 -> "服务器拒绝访问（HTTP $code）：可能需要登录，或者链接已过期。" +
            "可以试试用内置浏览器打开页面，登录后手动点下载"
        404, 410 -> "文件已经不存在了（HTTP $code）：链接可能失效或被删除，换个链接再试"
        408 -> "服务器响应超时（HTTP 408）：对方太忙或网络太慢，过一会儿再试"
        416 -> "服务器说这个位置没有数据（HTTP 416）：点继续会重新整理进度再接着下"
        429 -> "服务器限流了（HTTP 429）：请求太频繁被暂时拒绝，过一会儿再试"
        in 500..599 -> "服务器那边出错了（HTTP $code）：不是你的问题，过一会儿再试"
        in 300..399 -> "服务器跳转出了问题（HTTP $code）：链接可能已失效，换个链接再试"
        else -> "服务器返回了 HTTP $code，暂时下不了，过一会儿再试"
    }

    fun human(e: Throwable): String {
        val msg = e.message?.trim().orEmpty()
        // Already plain language (our own messages are written in
        // Chinese): keep them exactly as written.
        if (msg.any { it in '一'..'鿿' }) return msg
        return when (e) {
            // Order matters: the specific IOException subtypes
            // must be matched before IOException itself.
            is java.net.UnknownHostException ->
                "找不到这个服务器：检查一下链接对不对，或者换个网络再试"
            is java.net.ConnectException ->
                "连不上服务器：对方可能暂时关机或拒绝连接，过一会儿再试"
            is java.net.SocketTimeoutException ->
                "服务器半天没回应（超时）：网络慢或对方太忙，过一会儿再试"
            is javax.net.ssl.SSLException ->
                "安全连接（HTTPS）建立失败：可能是网络被拦截，或者对方的证书有问题，换个网络再试"
            is java.io.FileNotFoundException ->
                "文件不存在或已被删除，换个链接再试"
            is InterruptedException ->
                "操作被打断了，再试一次就好"
            is java.io.IOException ->
                "网络或读写出错了：过一会儿再试；反复出现可以换个网络"
            else ->
                "遇到一个没预料到的问题：过一会儿再试；反复出现请把情况反馈给开发者"
        }
    }
}

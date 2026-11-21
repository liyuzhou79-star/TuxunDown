package com.tuxundown.android.core

/**
 * Failure diagnosis advice (v2.7) — pure, unit-tested.
 *
 * A failed card already says WHAT happened (the plain error
 * text, see Errors). This adds the second half: one concrete,
 * doable next step, matched to the failure category, with a
 * button that goes straight to the operation it names. A
 * category only earns a button when the app can actually take
 * the user there — no decorative advice.
 *
 * Matching runs over the app's OWN plain error strings (the
 * Errors.httpMessage sentences and the engine's Chinese
 * messages), most specific category first. An unrecognised
 * error gets NO advice rather than a generic guess.
 */
object FailureAdvice {

    enum class Action { RETRY, OPEN_BROWSER, MANAGE_SOURCES, OPEN_FILES, NONE }

    data class Advice(val text: String, val action: Action, val actionLabel: String)

    fun forTask(task: TaskRecord): Advice? =
        forError(task.error, hasSources = task.sources.isNotEmpty())

    fun forError(error: String, hasSources: Boolean): Advice? {
        val e = error.trim()
        if (e.isEmpty()) return null
        return when {
            // Storage full: the bytes are all still there; free
            // space and 继续 resumes — the one fix that is
            // entirely in the user's hands, in this app.
            e.contains("空间不足") || e.contains("空间不够") -> Advice(
                "手机存储空间不够了。清出空间后点继续就能接着下，不用重来；" +
                    "可以先去「已下载文件」里删掉不要的大文件。",
                Action.OPEN_FILES, "去已下载文件清空间",
            )
            // The file changed under us (size / ETag / mirror
            // mismatch, or a checksum that does not match): the
            // old progress cannot be trusted.
            e.contains("对不上") -> if (hasSources) {
                Advice(
                    "服务器上的文件在下载途中变了，或者某个镜像给的不是同一个文件。" +
                        "建议删掉可疑的镜像源、只留主地址，再重新开始。",
                    Action.MANAGE_SOURCES, "管理镜像源",
                )
            } else {
                Advice(
                    "服务器上的文件在下载途中被换过了，旧进度不能再用。" +
                        "点继续会重新验证、从头开始下。",
                    Action.RETRY, "重新开始",
                )
            }
            // Refused: usually a login wall or a referer check.
            // The sniff browser carries the session Cookie.
            e.contains("HTTP 403") || e.contains("HTTP 401") ||
                e.contains("拒绝访问") -> Advice(
                    "这个链接多半要登录，或者只认从原网页点进来的下载。" +
                        "用内置浏览器打开原网页、登录后在页面里点下载，" +
                        "登录状态（Cookie）会自动带上。",
                    Action.OPEN_BROWSER, "用内置浏览器打开原网页",
                )
            e.contains("HTTP 429") || e.contains("限流") -> Advice(
                "服务器嫌请求太频繁，暂时不给下。等几分钟再点继续就好；" +
                    "不用改线程——线程数是自动调的，限流过去自然恢复。",
                Action.RETRY, "等会儿再试",
            )
            e.contains("HTTP 404") || e.contains("HTTP 410") ||
                e.contains("文件已经不存在") -> Advice(
                    "文件已经被删掉或链接过期了，再重试也是这个结果。" +
                        "换个新链接重新建任务吧。",
                    Action.NONE, "",
                )
            e.contains("HTTP 416") -> Advice(
                "服务器说这个位置没有数据，通常是旧进度和现在的文件对不上了。" +
                    "点继续会重新整理进度再接着下。",
                Action.RETRY, "继续",
            )
            e.contains("服务器那边出错") -> Advice(
                "是服务器自己出的问题，不是你的网络。等一会儿再点继续就好。",
                Action.RETRY, "等会儿再试",
            )
            e.contains("不支持 Range") ||
                (e.contains("不支持") &&
                    (e.contains("分片") || e.contains("续传"))) -> Advice(
                    "这个服务器不支持分片和断点续传。点继续会用单线程从头下；" +
                        "下完之前尽量别暂停——暂停了也只能从头来。",
                    Action.RETRY, "单线程重新下",
                )
            e.contains("找不到这个服务器") || e.contains("连不上服务器") ||
                e.contains("超时") || e.contains("安全连接") ||
                e.contains("网络") -> Advice(
                    "先确认手机网络是通的、这个链接在浏览器里能打开，再点继续；" +
                        "也可以换个网络（WiFi / 流量）试试，排除是当前网络的问题。",
                    Action.RETRY, "再试一次",
                )
            else -> null
        }
    }
}

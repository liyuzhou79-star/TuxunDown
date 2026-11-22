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
 * v4.5 (audit round 30): matching no longer runs over the
 * error TEXT. The category is the structured verdict the
 * engine recorded at the failure site (see FailureKind /
 * FailureClassifier), persisted on the record so it
 * survives the process restart between the failure and
 * the user reading the card. The old needle matching
 * survives only inside FailureClassifier.fromErrorText,
 * as the fallback for verdict-less records written
 * before v4.5. An unrecognised failure gets NO advice
 * rather than a generic guess.
 */
object FailureAdvice {

    enum class Action { RETRY, OPEN_BROWSER, MANAGE_SOURCES, OPEN_FILES, NONE }

    data class Advice(val text: String, val action: Action, val actionLabel: String)

    fun forTask(task: TaskRecord): Advice? =
        forKind(FailureClassifier.verdictFor(task), hasSources = task.sources.isNotEmpty())

    /** Legacy text entry point: judges via the fallback
     *  classifier, for verdict-less records and old tests. */
    fun forError(error: String, hasSources: Boolean): Advice? =
        forKind(FailureClassifier.fromErrorText(error), hasSources)

    fun forKind(kind: FailureKind, hasSources: Boolean): Advice? {
        return when (kind) {
            // Storage full: the bytes are all still there; free
            // space and 继续 resumes — the one fix that is
            // entirely in the user's hands, in this app.
            FailureKind.STORAGE_FULL -> Advice(
                "手机存储空间不够了。清出空间后点继续就能接着下，不用重来；" +
                    "可以先去「已下载文件」里删掉不要的大文件。",
                Action.OPEN_FILES, "去已下载文件清空间",
            )
            // The custom folder's SAF grant expired or turned
            // read-only (audit round 28): retrying cannot fix
            // it — the only way out is re-picking the download
            // folder in settings. No button: the failed card has
            // no route that opens settings, and a button that
            // goes nowhere is worse than honest text.
            FailureKind.FOLDER_GRANT -> Advice(
                    "下载文件夹的授权失效了，光重试没用：去设置里重新选一次" +
                        "下载目录，选好后回来点继续就能接着下，已下载的进度还在。",
                    Action.NONE, "",
                )
            // The storage device itself went away or turned
            // read-only mid-download (audit round 28): an SD
            // card pulled out, a volume unmounted. The progress
            // survives — fix the device, then resume.
            FailureKind.STORAGE_GONE -> Advice(
                "像是存储设备出问题了：SD 卡可能被拔出，或者存储变成了只读。" +
                    "先确认卡还在、能正常写入，再点继续接着下。",
                Action.RETRY, "再试一次",
            )
            // The file changed under us (size / ETag / mirror
            // mismatch, or a checksum that does not match): the
            // old progress cannot be trusted.
            FailureKind.FILE_CHANGED -> if (hasSources) {
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
            // The .part entry itself was deleted mid-download
            // (audit round 28's TargetGone, classified in
            // round 30): the bytes are gone, a resume
            // re-checks the target and starts over.
            FailureKind.TARGET_GONE -> Advice(
                "下载中的文件被删掉了，已经下好的部分也没了。点继续会重新开始下载。",
                Action.RETRY, "重新开始",
            )
            // Refused: usually a login wall or a referer check.
            // The sniff browser carries the session Cookie.
            FailureKind.HTTP_REFUSED -> Advice(
                    "这个链接多半要登录，或者只认从原网页点进来的下载。" +
                        "用内置浏览器打开原网页、登录后在页面里点下载，" +
                        "登录状态（Cookie）会自动带上。",
                    Action.OPEN_BROWSER, "用内置浏览器打开原网页",
                )
            FailureKind.HTTP_RATE_LIMITED -> Advice(
                "服务器嫌请求太频繁，暂时不给下。等几分钟再点继续就好；" +
                    "不用改连接数——连接数是自动调的，限流过去了自然恢复。",
                Action.RETRY, "等会儿再试",
            )
            FailureKind.HTTP_GONE -> Advice(
                    "文件已经被删掉或链接过期了，再重试也是这个结果。" +
                        "换个新链接重新建任务吧。",
                    Action.NONE, "",
                )
            FailureKind.HTTP_RANGE_INVALID -> Advice(
                "服务器说这个位置没有数据，通常是旧进度和现在的文件对不上了。" +
                    "点继续会重新整理进度再接着下。",
                Action.RETRY, "继续",
            )
            FailureKind.HTTP_SERVER_ERROR -> Advice(
                "是服务器自己出的问题，不是你的网络。等一会儿再点继续就好。",
                Action.RETRY, "等会儿再试",
            )
            FailureKind.NO_RANGE -> Advice(
                    "这个服务器不支持分片和断点续传。点继续会用单线程从头下；" +
                        "下完之前尽量别暂停——暂停了也只能从头来。",
                    Action.RETRY, "单线程重新下",
                )
            FailureKind.NETWORK -> Advice(
                    "先确认手机网络是通的、这个链接在浏览器里能打开，再点继续；" +
                        "也可以换个网络（WiFi / 流量）试试，排除是当前网络的问题。",
                    Action.RETRY, "再试一次",
                )
            FailureKind.UNKNOWN -> null
        }
    }
}


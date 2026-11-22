package com.tuxundown.android.core

/**
 * Structured failure verdict (v4.5, audit round 30) — pure,
 * unit-tested.
 *
 * Round 29 proved the pattern "classify by text that a
 * translator already rewrote" silently kills features:
 * NetRetry's string classifier judged the HUMANIZED
 * task.error and matched nothing for two years. The same
 * pattern was still standing in FailureAdvice and in
 * RetryPolicy.needsUserAction — both matched contains()
 * needles over Errors.human's plain sentences, so any
 * wording edit there could silently kill or mis-fire a
 * branch, and a needle with no living producer was a dead
 * branch nobody could see.
 *
 * The fix is the round-29 shape, one level up: classify
 * WHERE THE RAW INFO EXISTS. The engine knows the HTTP
 * status code at its throw sites and knows storage vs
 * network vs mismatch by code path, so it records a
 * [FailureKind] (plus the raw http code) on the task at
 * the failure site, BEFORE Errors.human rewrites the
 * text. The verdict is PERSISTED on the record (see
 * TaskStore): FailureAdvice renders for FAILED records
 * after a process restart, when only serialized fields
 * survive — the transient netOutage/portalHit flags
 * could not carry this. Records written before v4.5
 * have no verdict (UNKNOWN); for those, and only those,
 * [fromErrorText] keeps the old needle matching as a
 * fallback, so old FAILED cards keep their advice.
 * Once a verdict exists, Errors.human's wording can
 * change freely — no classification may move.
 */
enum class FailureKind {
    UNKNOWN,
    STORAGE_FULL,
    STORAGE_GONE,
    FOLDER_GRANT,
    FILE_CHANGED,
    TARGET_GONE,
    HTTP_REFUSED,
    HTTP_RATE_LIMITED,
    HTTP_GONE,
    HTTP_RANGE_INVALID,
    HTTP_SERVER_ERROR,
    NO_RANGE,
    NETWORK,
}

/**
 * An HTTP status failure that CARRIES its code, thrown at
 * the engine sites that read responseCode. The message is
 * exactly Errors.httpMessage(code) — the card text is
 * unchanged — but classifiers downstream judge [code],
 * never the sentence.
 */
class HttpFailureException(val code: Int) :
    IllegalStateException(Errors.httpMessage(code))

object FailureClassifier {

    fun ofHttp(code: Int): FailureKind = when (code) {
        401, 403 -> FailureKind.HTTP_REFUSED
        429 -> FailureKind.HTTP_RATE_LIMITED
        404, 410 -> FailureKind.HTTP_GONE
        416 -> FailureKind.HTTP_RANGE_INVALID
        // A 408 is the server timing out — the retry advice
        // the text fallback has always given it (via the
        // 超时 needle) is the network one.
        408 -> FailureKind.NETWORK
        in 500..599 -> FailureKind.HTTP_SERVER_ERROR
        else -> FailureKind.UNKNOWN
    }

    /** The HTTP code carried anywhere in the cause chain. */
    fun httpCodeOf(e: Throwable?): Int {
        var cur = e
        while (cur != null) {
            if (cur is HttpFailureException) return cur.code
            cur = cur.cause
        }
        return 0
    }

    /**
     * Judge the RAW failure. Walks the cause chain; the
     * verdicts come from exception TYPES, marker exception
     * classes and OS errno strings (stable system text) —
     * and, for this app's own plain-Chinese
     * IllegalStateExceptions, from [fromErrorText] applied
     * to the ORIGINAL message at the throw site (that is
     * the producer text itself, not a translation of it).
     */
    fun ofThrowable(e: Throwable?): FailureKind {
        var cur = e
        while (cur != null) {
            when (cur) {
                is HttpFailureException -> {
                    val k = ofHttp(cur.code)
                    if (k != FailureKind.UNKNOWN) return k
                }
                is PublicStorage.FolderGrantException ->
                    return FailureKind.FOLDER_GRANT
                is PublicStorage.TargetGoneException ->
                    return FailureKind.TARGET_GONE
                // A portal hit has its own verdict channel
                // (task.portalHit / portalDeferred) — never
                // re-judge it here from its message text.
                is PortalBlockedException -> return FailureKind.UNKNOWN
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketTimeoutException,
                is java.net.SocketException,
                is javax.net.ssl.SSLException,
                -> return FailureKind.NETWORK
            }
            val msg = cur.message ?: ""
            // OS errno text — the same needles the engine's
            // isStorageFull / isStorageGone have always used;
            // they describe the DEVICE, not our wording.
            if (msg.contains("No space left", true) || msg.contains("ENOSPC") ||
                msg.contains("存储空间不足")
            ) return FailureKind.STORAGE_FULL
            if (msg.contains("Read-only file system", true) || msg.contains("EROFS") ||
                msg.contains("No such device", true) || msg.contains("ENODEV") ||
                msg.contains("Input/output error", true) || msg.contains("EIO")
            ) return FailureKind.STORAGE_GONE
            // Our own plain sentences (mismatch, no-Range,
            // folder, redirect…): judge the producer text.
            if (msg.any { it in '一'..'鿿' }) {
                val k = fromErrorText(msg)
                if (k != FailureKind.UNKNOWN) return k
            }
            // A generic IOException that survived the errno
            // checks is what Errors.human calls 网络或读写
            // 出错了 — a network/read-write failure.
            if (cur is java.io.IOException &&
                cur !is java.io.FileNotFoundException
            ) return FailureKind.NETWORK
            cur = cur.cause
        }
        return FailureKind.UNKNOWN
    }

    /**
     * The LEGACY text fallback: the pre-v4.5 needle matching
     * (same needles, same precedence as the old
     * FailureAdvice branches), kept ONLY for records that
     * carry no recorded verdict — files written before
     * v4.5, and the blank-fill failure sentences. A record
     * WITH a verdict never reaches this function (see
     * [verdictFor]), so rewording Errors.human / Notes
     * cannot change any live classification; it can only
     * change what an ancient verdict-less record shows.
     */
    fun fromErrorText(error: String): FailureKind {
        val e = error.trim()
        if (e.isEmpty()) return FailureKind.UNKNOWN
        return when {
            e.contains("空间不足") || e.contains("空间不够") ->
                FailureKind.STORAGE_FULL
            e.contains("重选") || e.contains("授权可能已失效") ||
                e.contains("自定义文件夹") -> FailureKind.FOLDER_GRANT
            e.contains("存储设备") -> FailureKind.STORAGE_GONE
            e.contains("对不上") -> FailureKind.FILE_CHANGED
            e.contains("被删掉") -> FailureKind.TARGET_GONE
            else -> {
                val code = HTTP_CODE.find(e)?.groupValues?.get(1)?.toIntOrNull()
                val byCode = if (code != null) ofHttp(code) else FailureKind.UNKNOWN
                when {
                    byCode != FailureKind.UNKNOWN -> byCode
                    e.contains("拒绝访问") -> FailureKind.HTTP_REFUSED
                    e.contains("限流") -> FailureKind.HTTP_RATE_LIMITED
                    e.contains("文件已经不存在") -> FailureKind.HTTP_GONE
                    e.contains("服务器那边出错") -> FailureKind.HTTP_SERVER_ERROR
                    e.contains("不支持 Range") ||
                        (e.contains("不支持") &&
                            (e.contains("分片") || e.contains("续传"))) ->
                        FailureKind.NO_RANGE
                    e.contains("找不到这个服务器") || e.contains("连不上服务器") ||
                        e.contains("超时") || e.contains("安全连接") ||
                        e.contains("网络") -> FailureKind.NETWORK
                    else -> FailureKind.UNKNOWN
                }
            }
        }
    }

    /**
     * The verdict a consumer must judge by: the recorded
     * structured verdict when one exists, the legacy text
     * fallback only for verdict-less (pre-v4.5) records.
     */
    fun verdictFor(task: TaskRecord): FailureKind =
        if (task.failureKind != FailureKind.UNKNOWN) task.failureKind
        else fromErrorText(task.error)

    private val HTTP_CODE = Regex("HTTP (\\d{3})")
}

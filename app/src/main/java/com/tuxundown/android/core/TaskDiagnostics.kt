package com.tuxundown.android.core

/**
 * v3.8: per-task diagnostic text behind the card's
 * 复制诊断信息 button — the single-download companion to the
 * settings page's whole-app 诊断 (v2.4). It is built to be
 * pasted into a bug report, so it is deliberately stingy:
 * - the link is reduced to scheme + host + path: any
 *   userinfo (http://user:pass@…) is dropped and the query
 *   string is replaced by a marker (signed URLs carry their
 *   tokens in the query);
 * - request headers are NAMED, never valued — Cookie and
 *   Authorization values stay on the phone;
 * - the UA is reported by preset name (or 自定义), never as
 *   the raw string — a custom UA can carry identifying text.
 * Assembly is pure so the redaction rules are unit-tested
 * against secret-shaped values.
 */
object TaskDiagnostics {

    fun statusLabel(s: TaskStatus): String = when (s) {
        TaskStatus.QUEUED -> "排队中"
        TaskStatus.RUNNING -> "下载中"
        TaskStatus.PAUSED -> "已暂停"
        TaskStatus.DONE -> "已完成"
        TaskStatus.FAILED -> "失败"
    }

    /**
     * scheme://host[:port]/path — userinfo dropped; a present
     * query or fragment collapses to one marker (their content
     * is exactly what must not travel).
     */
    fun redactedUrl(raw: String): String {
        val hadSuffix = '?' in raw || '#' in raw
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
        if (uri != null && uri.scheme != null && uri.host != null) {
            val sb = StringBuilder()
            sb.append(uri.scheme).append("://").append(uri.host)
            if (uri.port > 0) sb.append(':').append(uri.port)
            sb.append(uri.path ?: "")
            if (hadSuffix) sb.append("?（参数已隐藏）")
            return sb.toString()
        }
        // Unparseable: strip by hand — everything from the
        // first ? or # goes, and an @ in the authority means
        // userinfo goes too.
        var s = raw.substringBefore('?').substringBefore('#')
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) {
            val rest = s.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            val authority = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else ""
            s = s.substring(0, schemeEnd + 3) +
                authority.substringAfterLast('@') + path
        }
        return if (hadSuffix) "$s?（参数已隐藏）" else s
    }

    /** The effective UA described by NAME only. */
    fun uaLabel(taskHeader: String?, userSetting: String?): String {
        val effective = UserAgents.resolve(taskHeader, userSetting)
        if (effective == DownloadEngine.DEFAULT_UA) return "默认（安卓 Chrome）"
        val idx = UserAgents.PRESETS.indexOfFirst {
            it.second.isNotEmpty() && it.second == effective
        }
        return if (idx >= 0) UserAgents.PRESETS[idx].first else "自定义"
    }

    fun build(task: TaskRecord, appVersion: String, userUaSetting: String?): String {
        val sb = StringBuilder()
        sb.append("TuxunDown 任务诊断\n")
        sb.append("版本：v").append(appVersion).append('\n')
        sb.append("状态：").append(statusLabel(task.status)).append('\n')
        sb.append("链接：").append(redactedUrl(task.url)).append('\n')
        sb.append("大小：").append(
            if (task.totalSize > 0) Format.size(task.totalSize) else "未知"
        ).append("，已下载：").append(Format.size(task.downloaded)).append('\n')
        sb.append("连接：").append(
            when {
                task.isHls -> "流媒体分片并行下载（HLS）"
                task.singleThreadOnly -> "单线程（单条连接）"
                // v5.3 (audit round 38): same single source as
                // the card's 分片 row — the live count while
                // RUNNING, the target otherwise (see
                // TaskRecord.displayConnections).
                else -> "${task.displayConnections} 条连接"
            }
        ).append('\n')
        if (task.error.isNotBlank()) {
            sb.append("错误：").append(task.error).append('\n')
        }
        val expected = Checksum.expectedOf(task)
        sb.append("完整性校验：").append(
            if (expected != null) {
                "${expected.first} · ${Checksum.stateLabel(task.checksumState)}"
            } else {
                "未设置期望哈希"
            }
        ).append('\n')
        sb.append("浏览器标识（UA）：").append(
            uaLabel(task.headers["User-Agent"], userUaSetting)
        ).append('\n')
        val names = task.headers.keys.sorted()
        sb.append("请求头：").append(
            if (names.isEmpty()) {
                "无"
            } else {
                names.joinToString("、") + "（只列名字，值不外带）"
            }
        ).append('\n')
        sb.append("（链接已去掉参数，请求头只列名字、没有值；本诊断只在本机生成，没有上传。）")
        return sb.toString()
    }
}

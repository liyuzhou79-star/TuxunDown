package com.tuxundown.android.core

/**
 * v3.7 (audit round 23): the single source for the task-note /
 * task-error sentences the download paths write over and over.
 *
 * Before this round the same sentence lived as a literal in up
 * to four places in DownloadEngine (segmented / single-stream /
 * HLS / FTP each grew its own copy — and the copies had already
 * drifted: the at-start traffic note said 当前是流量网络 in two
 * paths while the mid-download one said 网络已切到流量， which is
 * a real distinction worth keeping, so there are two functions,
 * not one). Wording changes now happen HERE and every path
 * follows. The texts below are byte-for-byte the sentences the
 * paths used to write inline — this round converges the source,
 * it does not reword anything.
 *
 * What deliberately does NOT live here: Compose UI copy in
 * MainActivity (labels and rows are written where they render,
 * the codebase's standing structure), notification titles in
 * DownloadService (each bound to one Android notification slot),
 * the changelog (historical text, one entry per version), and
 * FailureAdvice (already a single source of its own).
 */
object Notes {

    /** Traffic guard tripped while the task was STARTING on a
     *  mobile network (the file's size is known by then). */
    fun trafficGuardAtStart(totalSize: Long): String =
        "流量保护：当前是流量网络，这个文件有 ${Format.size(totalSize)}，" +
            "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"

    /** Traffic guard tripped because the network SWITCHED to
     *  mobile mid-download (segmented / single-stream paths —
     *  the size clause was always present in their inline copy,
     *  whatever the size). */
    fun trafficGuardSwitched(totalSize: Long): String =
        "流量保护：网络已切到流量，这个文件有 ${Format.size(totalSize)}，" +
            "已自动暂停省流量。回到 WiFi 点继续，或在流量下再点一次继续也可下载。"

    /** The HLS variant of the switched note: an HLS task's total
     *  size is unknown while it runs, and its inline copy never
     *  had a size clause (nor the on-mobile-continue offer). Kept
     *  as its own function so the wording stays byte-identical
     *  instead of depending on what totalSize happens to hold. */
    fun trafficGuardSwitchedHls(): String =
        "流量保护：网络已切到流量，已自动暂停省流量。回到 WiFi 点继续即可续传。"

    /** 仅 WiFi is on and the network is (now) mobile. */
    fun wifiOnlyPaused(): String =
        "网络已切到流量（已开启「仅 WiFi 下载」），已暂停；回到 WiFi 后点继续即可续传。"

    /** The disk filled up mid-download (note AND error copies). */
    fun storageFull(): String =
        "手机存储空间不足，下载已停下。清出空间后点继续即可续传。"

    /** The server swapped the file mid-download: total size no
     *  longer matches what the probe saw. */
    fun fileChangedSize(): String =
        "服务器在下载途中换了文件（大小和刚才探到的对不上），已停下；点继续会重新开始"

    /** The server swapped the file mid-download: the strong
     *  validator (ETag) changed. */
    fun fileChangedValidator(): String =
        "服务器在下载途中换了文件（校验标记对不上），已停下；点继续会重新开始"
}

package com.tuxundown.android.core

/**
 * v8.5: 「再下一次」 — the pure field-copy plan behind
 * DownloadCenter.redownload. A finished task the user wants
 * again becomes a BRAND-NEW record; this object decides
 * exactly what the new record inherits, so the copy table is
 * unit-pinned instead of eyeballed at the call site.
 *
 * COPIED from the source record:
 *  - url — the address to download again (the visibility
 *    rule, CardQuickMenu.canRedownload, already guarantees
 *    DONE + non-blank; anything else gets null here).
 *  - sources (mirrors) — copied into a NEW list; the two
 *    records never share one.
 *  - headers — copied into a NEW map, the same caliber as
 *    the FAILED-retry reuse (retryWithStrategy keeps the
 *    record's headers): a link that needed a Cookie or a
 *    Referer downloads again with the same headers. The
 *    values stay inside the task store exactly like any
 *    other task's headers — nothing is written anywhere
 *    new, and at send time SessionScope still decides per
 *    hop what may leave for which host, unchanged.
 *  - fileName — the name the user/task settled on. A clash
 *    with the already-downloaded file is resolved by the
 *    EXISTING finalize naming (PublicStorage / FreeName):
 *    the new file lands as 「名字 (1)」, the original file is
 *    never overwritten.
 *  - isHls — re-derived from the url exactly the way
 *    DownloadCenter.newTask derives it (a .m3u8 link is an
 *    HLS task), never copied as a stale flag.
 *
 * NOT copied — the new record starts from TaskRecord
 * defaults for everything else: a fresh id, status QUEUED,
 * downloaded = 0, totalSize unknown, no chunk bitmap, no
 * savedUri / targetUri, no etag / lastModified, no checksum
 * expectation or verdict, no speed history or peaks, no
 * note / error / userNote, default priority and thread
 * settings, no schedule, not archived. The source record
 * itself is never mutated.
 *
 * Pure logic: no Android API, no I/O (the fresh id and the
 * createdAt stamp come from TaskRecord's own defaults).
 */
object RedownloadPlan {

    /** The fresh record for [source], or null when the
     *  source may not re-download (see
     *  CardQuickMenu.canRedownload). */
    fun newRecord(source: TaskRecord): TaskRecord? {
        if (!CardQuickMenu.canRedownload(source)) return null
        val fresh = TaskRecord(
            url = source.url,
            fileName = source.fileName,
            headers = LinkedHashMap(source.headers),
            sources = source.sources.toMutableList(),
        )
        if (DownloadEngine.isHlsUrl(source.url)) fresh.isHls = true
        return fresh
    }
}

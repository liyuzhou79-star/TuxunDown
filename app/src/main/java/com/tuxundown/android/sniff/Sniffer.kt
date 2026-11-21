package com.tuxundown.android.sniff

import com.tuxundown.android.core.DownloadEngine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class SniffedResource(
    val url: String,
    val name: String,
    val kind: String, // apk / archive / video / audio / image / document / other
    /** Anchor text suggested this is THE download entry of the page. */
    val looksLikeDownloadEntry: Boolean = false,
    /**
     * File size in bytes when the v1.6 size probe managed to measure
     * it; -1 = unknown. The parser never knows sizes — only
     * [SizeProbe] fills this in, and only with a number the server
     * itself reported. Unknown is displayed as 大小未知， never guessed.
     */
    val size: Long = -1,
)

sealed class SniffResult {
    data class DirectFile(val url: String, val fileName: String) : SniffResult()
    data class ResourceList(
        val pageUrl: String,
        val resources: List<SniffedResource>,
        val totalCount: Int = resources.size,
    ) : SniffResult()
    data class Failure(val reason: String) : SniffResult()
}

/**
 * Input routing: decide whether a pasted link is a direct file or a web
 * page BEFORE doing anything expensive. A direct file link (like a
 * 271 MB .bin.gz model) must go straight to the download engine; only
 * real HTML pages go to the sniffer. (v1 bug: a pasted direct link could
 * be dragged through the web-page path and come back with "no resources".)
 */
object LinkRouter {
    enum class Route { DIRECT_FILE, WEB_PAGE }

    /** Cheap local check: the URL path ends in a known file extension. */
    fun looksLikeFileUrl(url: String): Boolean = SniffParser.kindOf(url) != null

    /** Pure header-based decision (unit-testable, no network). */
    fun decide(contentType: String?, contentDisposition: String?, url: String): Route {
        if (!contentDisposition.isNullOrBlank()) return Route.DIRECT_FILE
        val type = (contentType ?: "").lowercase()
        if (type.contains("html") || type.contains("xhtml")) return Route.WEB_PAGE
        if (type.isNotBlank() && !type.startsWith("text/")) return Route.DIRECT_FILE
        if (looksLikeFileUrl(url)) return Route.DIRECT_FILE
        if (type.startsWith("text/")) return Route.WEB_PAGE
        // No usable headers: a downloader should try downloading first.
        return Route.DIRECT_FILE
    }

    /**
     * Lightweight network probe: HEAD first; servers that refuse HEAD get
     * a 1-byte Range GET whose body is never read. Throws with a plain
     * reason on HTTP errors so the UI can say what happened.
     */
    fun probe(url: String, userAgent: String = DownloadEngine.DEFAULT_UA): Route {
        var method = "HEAD"
        repeat(2) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 12_000
            c.readTimeout = 12_000
            c.instanceFollowRedirects = true
            c.requestMethod = method
            c.setRequestProperty("User-Agent", userAgent)
            if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")
            val code = c.responseCode
            val type = c.getHeaderField("Content-Type")
            val disp = c.getHeaderField("Content-Disposition")
            val finalUrl = c.url.toString()
            c.disconnect()
            when {
                // Audit A14: plenty of servers refuse HEAD (405/501,
                // some even 401/403) but happily serve GET — fall back
                // to the 1-byte Range GET before giving up.
                code == 405 || code == 501 || code == 400 ||
                    ((code == 401 || code == 403) && method == "HEAD") -> method = "GET"
                code >= 400 -> throw IllegalStateException(httpReason(code))
                else -> return decide(type, disp, finalUrl)
            }
        }
        throw IllegalStateException("服务器没有回应链接类型，请改用内置浏览器打开")
    }

    fun httpReason(code: Int): String = when (code) {
        401, 403 -> "服务器拒绝访问（HTTP $code），可能需要登录或从浏览器里抓链接，可以试试内置浏览器打开后手动点下载"
        404 -> "链接不存在了（HTTP 404）"
        429 -> "服务器限流了（HTTP 429），过一会儿再试"
        in 500..599 -> "服务器出错（HTTP $code），过一会儿再试"
        else -> "服务器返回 HTTP $code"
    }
}

/** Layer 1: pure HTML parsing (unit-testable, no network). */
object SniffParser {
    val EXT_KIND = mapOf(
        "apk" to "apk", "xapk" to "apk", "apks" to "apk", "apkm" to "apk", "aab" to "apk",
        "zip" to "archive", "rar" to "archive", "7z" to "archive",
        "tar" to "archive", "gz" to "archive", "tgz" to "archive", "xz" to "archive",
        "bz2" to "archive", "zst" to "archive",
        "mp4" to "video", "mkv" to "video", "webm" to "video", "avi" to "video",
        "mov" to "video", "flv" to "video", "m3u8" to "video", "ts" to "video",
        "mp3" to "audio", "flac" to "audio", "wav" to "audio", "ogg" to "audio",
        "m4a" to "audio", "aac" to "audio",
        "jpg" to "image", "jpeg" to "image", "png" to "image", "gif" to "image",
        "webp" to "image", "svg" to "image", "bmp" to "image",
        "pdf" to "document", "doc" to "document", "docx" to "document",
        "xls" to "document", "xlsx" to "document", "ppt" to "document",
        "pptx" to "document", "txt" to "document", "epub" to "document",
        "iso" to "other", "img" to "other", "bin" to "other", "exe" to "other",
        "msi" to "other", "crx" to "other",
        "dmg" to "other", "deb" to "other", "json" to "other", "torrent" to "other",
    )

    private val KIND_ORDER = mapOf(
        "apk" to 0, "archive" to 1, "video" to 2, "audio" to 3,
        "document" to 4, "other" to 5, "image" to 6,
    )

    private val ATTR = Regex(
        """(?:href|src|poster|data-src|data-url|data-href|data-file)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val ANCHOR = Regex(
        """<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val META_REFRESH = Regex(
        """<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val REFRESH_URL = Regex("""url\s*=\s*([^"'>\s]+)""", RegexOption.IGNORE_CASE)
    private val PLAIN_URL = Regex("""https?://[^\s"'<>\\)\]}]+""")

    fun kindOf(url: String): String? {
        val clean = url.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', "").lowercase()
        return EXT_KIND[ext]
    }

    fun resolve(base: String, ref: String): String? {
        val r = ref.trim()
        if (r.isBlank() || r.startsWith("#") || r.startsWith("javascript:", true) ||
            r.startsWith("data:", true) || r.startsWith("mailto:", true)
        ) return null
        return runCatching { URL(URL(base), r).toString() }.getOrNull()
    }

    /** The target of a <meta http-equiv="refresh"> jump, if the page has one. */
    fun metaRefreshTarget(html: String, baseUrl: String): String? {
        val tag = META_REFRESH.find(html)?.value ?: return null
        val raw = REFRESH_URL.find(tag)?.groupValues?.get(1) ?: return null
        return resolve(baseUrl, raw.trim('"', '\''))
    }

    private fun isEntryLink(url: String, anchorText: String): Boolean =
        anchorText.contains("下载") || anchorText.contains("download", true) ||
            url.contains("download", true) || url.contains("/dl/", true) ||
            url.contains("down.php", true) || url.contains("downfile", true)

    /**
     * v1.6 site patterns: well-known public file hosts whose REAL
     * download links often carry no file extension (a SourceForge
     * "/download" hop, an extensionless GitHub release asset, an
     * archive.org file entry). A URL matching one of these is a
     * downloadable entry even when [kindOf] finds no extension.
     * Deliberately narrow — a generic page on the same host (docs,
     * about, the project home) must NOT match.
     */
    private val DIRECT_PATTERNS = listOf(
        // GitHub release asset links (extensionless binaries exist).
        Regex("""/releases/download/"""),
        // SourceForge: file links live under /projects/<p>/files/…
        // and the actual fetch hop ends in /download.
        Regex("""sourceforge\.net/projects/[\w.-]+/files/.+"""),
        Regex("""(downloads\.)?sourceforge\.net/.*/download([?#].*)?$"""),
        // archive.org: everything under /download/<id>/… is a file
        // of that item (the details page itself is /details/<id>).
        Regex("""archive\.org/download/"""),
    )

    /** Kind when [url] matches a known direct-file site pattern. */
    fun patternKind(url: String): String? {
        if (DIRECT_PATTERNS.none { it.containsMatchIn(url) }) return null
        // A SourceForge-style "…/file.zip/download" hop hides the
        // real extension in the segment before the trailing /download.
        val stripped = url.substringBefore('?').substringBefore('#')
            .removeSuffix("/download")
        return kindOf(stripped) ?: "other"
    }

    fun extract(html: String, baseUrl: String): List<SniffedResource> {
        val byUrl = linkedMapOf<String, SniffedResource>()
        fun add(url: String, kind: String?, entry: Boolean) {
            // v1.6: extensionless links on known file hosts (GitHub
            // release assets, SourceForge hops, archive.org files)
            // count as downloadable entries via the site patterns.
            val patterned = if (kind == null) patternKind(url) else null
            if (kind == null && patterned == null && !entry) return
            val prev = byUrl[url]
            byUrl[url] = SniffedResource(
                url = url,
                name = DownloadEngine.fileNameFrom(null, url),
                kind = kind ?: patterned ?: (prev?.kind ?: "other"),
                looksLikeDownloadEntry = entry || patterned != null ||
                    (prev?.looksLikeDownloadEntry == true),
            )
        }
        // Anchors first: their text tells us which link is the download entry.
        for (m in ANCHOR.findAll(html)) {
            val resolved = resolve(baseUrl, m.groupValues[1]) ?: continue
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            add(resolved, kindOf(resolved), isEntryLink(resolved, text))
        }
        // Then bare href/src/poster/data-* attributes (media tags, JS vars...).
        for (m in ATTR.findAll(html)) {
            val resolved = resolve(baseUrl, m.groupValues[1]) ?: continue
            add(resolved, kindOf(resolved), false)
        }
        // Finally raw URLs embedded in text or scripts (var downUrl = "http://...").
        for (m in PLAIN_URL.findAll(html)) {
            val raw = m.value.trimEnd('.', ',', ';')
            val resolved = resolve(baseUrl, raw) ?: continue
            // Audit A13: a bare script/text URL counts as a download
            // ENTRY only when it really points at a file. v1.2 marked
            // ANY raw URL containing "download" as an entry, so one
            // stray analytics/CDN URL could become the "single path"
            // that layer 2 auto-follows instead of listing choices.
            val kind = kindOf(resolved)
            add(resolved, kind, kind != null && resolved.contains("download", true))
        }
        // Download entries first, files before images, images last.
        return byUrl.values.sortedWith(
            compareByDescending<SniffedResource> { it.looksLikeDownloadEntry }
                .thenBy { KIND_ORDER[it.kind] ?: 5 }
                .thenBy { it.name }
        )
    }
}

/** Layers 1+2 with network: fetch pages, follow the single download path. */
class Sniffer(private val userAgent: String = DownloadEngine.DEFAULT_UA) {

    private data class Fetched(
        val url: String,
        val contentType: String,
        val body: String,
        val isFile: Boolean,
    )

    /**
     * Fetch one URL with guards that v1 lacked:
     * - manual redirect following (also across http <-> https),
     * - HTTP error codes become clear failure reasons,
     * - Content-Type is checked BEFORE the body is touched: a non-HTML
     *   response is a file, its body is never read as if it were a page
     *   (a 271 MB binary is not scanned as HTML),
     * - page bodies are capped, gzip is decoded explicitly, and the page
     *   charset (GBK on many Chinese sites) is honoured.
     */
    private fun fetch(startUrl: String): Fetched {
        var current = startUrl
        val visited = mutableSetOf<String>()
        repeat(6) {
            if (!visited.add(current)) {
                throw IllegalStateException("页面跳转成环，无法继续")
            }
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code in 301..308 && code != 304 && code != 306) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("服务器跳转但没给地址（HTTP $code）")
                current = SniffParser.resolve(current, loc)
                    ?: throw IllegalStateException("服务器给的跳转地址无效")
                return@repeat // next hop
            }
            if (code >= 400) {
                c.disconnect()
                throw IllegalStateException(LinkRouter.httpReason(code))
            }
            val type = c.getHeaderField("Content-Type") ?: ""
            val disp = c.getHeaderField("Content-Disposition")
            val finalUrl = c.url.toString()
            if (LinkRouter.decide(type, disp, finalUrl) == LinkRouter.Route.DIRECT_FILE) {
                // It is a file: never read the body as a page.
                c.disconnect()
                return Fetched(finalUrl, type, "", isFile = true)
            }
            val body = readBodyCapped(c)
            c.disconnect()
            return Fetched(finalUrl, type, body, isFile = false)
        }
        throw IllegalStateException("页面跳转次数太多，已停下")
    }

    private fun readBodyCapped(c: HttpURLConnection): String {
        var input: InputStream = c.inputStream
        if ((c.getHeaderField("Content-Encoding") ?: "").contains("gzip", true)) {
            input = GZIPInputStream(input)
        }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (total < MAX_PAGE_BYTES) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_PAGE_BYTES - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        input.close()
        val bytes = out.toByteArray()
        val charsetName = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(c.getHeaderField("Content-Type") ?: "")?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(String(bytes.copyOf(minOf(bytes.size, 4096)), Charsets.UTF_8))
                ?.groupValues?.get(1)
            ?: "UTF-8"
        val charset = runCatching { charset(charsetName) }.getOrDefault(Charsets.UTF_8)
        return String(bytes, charset)
    }

    /**
     * Sniff [startUrl]. Direct files are recognised by headers and handed
     * back immediately. Layer 2: while the page offers exactly ONE
     * plausible download entry, follow it (max 5 hops); several candidates
     * are handed back as a list for the user to pick. Every failure path
     * returns a plain-language reason — never a silent empty list.
     */
    fun sniff(startUrl: String): SniffResult {
        // Local fast path: a known file extension never touches the page logic.
        if (LinkRouter.looksLikeFileUrl(startUrl)) {
            return SniffResult.DirectFile(
                startUrl, DownloadEngine.fileNameFrom(null, startUrl)
            )
        }
        var current = startUrl
        val visited = mutableSetOf<String>()
        repeat(6) {
            if (!visited.add(current)) {
                return SniffResult.Failure("页面跳转成环，无法继续自动追踪")
            }
            val f = try {
                fetch(current)
            } catch (e: Exception) {
                return SniffResult.Failure("打开页面失败：${e.message ?: "网络错误"}")
            }
            if (f.isFile) {
                return SniffResult.DirectFile(
                    f.url, DownloadEngine.fileNameFrom(null, f.url)
                )
            }
            // A meta-refresh page is just a signpost: follow it.
            val refresh = SniffParser.metaRefreshTarget(f.body, f.url)
            if (refresh != null && refresh != f.url) {
                current = refresh
                return@repeat
            }
            val resources = SniffParser.extract(f.body, f.url)
            if (resources.isEmpty()) {
                return SniffResult.Failure(
                    "页面打开了，但里面没找到可下载的资源（可能是脚本动态加载的页面）。" +
                        "可以试试内置浏览器打开，手动点到下载那一步。"
                )
            }
            val entries = resources.filter { it.looksLikeDownloadEntry }
            val single = when {
                entries.size == 1 -> entries[0]
                entries.isEmpty() && resources.size == 1 -> resources[0]
                else -> null
            }
            if (single == null) {
                val shown = resources.take(MAX_LIST)
                return SniffResult.ResourceList(f.url, shown, resources.size)
            }
            // If the single path points straight at a file, we are done;
            // otherwise it is an intermediate page: follow it.
            if (SniffParser.kindOf(single.url) != null &&
                !single.url.contains(".php", true) && !single.url.contains("down", true)
            ) {
                return SniffResult.DirectFile(
                    single.url, DownloadEngine.fileNameFrom(null, single.url)
                )
            }
            current = single.url
        }
        return SniffResult.Failure("追了 5 层中间页还没到文件，请用内置浏览器手动打开")
    }

    /**
     * v1.6 (sniff pack item 1): measure the sizes of an already
     * sniffed resource list. Kept OUT of [sniff] so the result list
     * still appears instantly; the UI calls this as a second phase
     * and re-renders with sizes + size sorting once it lands.
     */
    fun probeSizes(resources: List<SniffedResource>): List<SniffedResource> =
        SizeProbe.probeAll(resources, userAgent)

    companion object {
        const val MAX_PAGE_BYTES = 4 * 1024 * 1024
        const val MAX_LIST = 600
    }
}

/** Sort orders for the sniff result list (pure, unit-tested). */
object SniffSort {
    /** Biggest first; unknown sizes (-1) always sink to the end. */
    fun bySizeDesc(list: List<SniffedResource>): List<SniffedResource> =
        list.sortedWith(
            compareByDescending<SniffedResource> { it.size >= 0 }
                .thenByDescending { it.size }
                .thenBy { it.name.lowercase() }
        )
}

/**
 * v1.6 size probe: ask each resource's server how big the file is,
 * WITHOUT downloading it — a HEAD request first, and for servers
 * that refuse HEAD a 1-byte Range GET whose Content-Range carries
 * the total. Hard limits so a 600-resource page cannot turn into a
 * traffic storm: at most [MAX_PROBED] resources, [THREADS] in
 * flight, ~4 s per request. A resource whose size cannot be
 * measured keeps size = -1 （未知） — never an estimate.
 */
object SizeProbe {
    const val MAX_PROBED = 50
    const val THREADS = 8
    const val TIMEOUT_MS = 4_000

    /** Total length out of a "bytes 0-0/12345" Content-Range, if any. */
    fun totalFromContentRange(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return -1
        val total = contentRange.substringAfter('/', "").trim()
        if (total == "*") return -1
        return total.toLongOrNull() ?: -1
    }

    /** Prefer the Range total (exact); fall back to a HEAD length. */
    fun pickSize(headLength: Long, rangeTotal: Long): Long = when {
        rangeTotal > 0 -> rangeTotal
        headLength > 0 -> headLength
        else -> -1
    }

    fun probeAll(
        resources: List<SniffedResource>,
        userAgent: String = DownloadEngine.DEFAULT_UA,
    ): List<SniffedResource> {
        if (resources.isEmpty()) return resources
        val targets = resources.take(MAX_PROBED)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS)
        try {
            val futures = targets.map { res ->
                pool.submit(java.util.concurrent.Callable {
                    res.copy(size = probeOne(res.url, userAgent))
                })
            }
            val probed = futures.map { f ->
                runCatching {
                    f.get(TIMEOUT_MS * 3L, java.util.concurrent.TimeUnit.MILLISECONDS)
                }.getOrNull()
            }
            val byUrl = probed.filterNotNull().associateBy { it.url }
            return resources.map { byUrl[it.url] ?: it }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probeOne(url: String, userAgent: String): Long {
        // HEAD first: cheapest, and most file servers answer it.
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.requestMethod = "HEAD"
            c.setRequestProperty("User-Agent", userAgent)
            val code = c.responseCode
            val len = c.contentLengthLong
            c.disconnect()
            if (code in 200..299 && len > 0) return len
        }
        // Range GET of a single byte: Content-Range reports the total
        // even on servers that refuse HEAD. The body is never read.
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Range", "bytes=0-0")
            val code = c.responseCode
            val rangeTotal = totalFromContentRange(c.getHeaderField("Content-Range"))
            val len = c.contentLengthLong
            c.disconnect()
            val picked = pickSize(if (code == 200) len else -1, rangeTotal)
            if (picked > 0) return picked
        }
        return -1
    }
}

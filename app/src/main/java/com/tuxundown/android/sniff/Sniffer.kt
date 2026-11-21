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
                code == 405 || code == 501 || code == 400 -> method = "GET"
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

    fun extract(html: String, baseUrl: String): List<SniffedResource> {
        val byUrl = linkedMapOf<String, SniffedResource>()
        fun add(url: String, kind: String?, entry: Boolean) {
            if (kind == null && !entry) return
            val prev = byUrl[url]
            byUrl[url] = SniffedResource(
                url = url,
                name = DownloadEngine.fileNameFrom(null, url),
                kind = kind ?: (prev?.kind ?: "other"),
                looksLikeDownloadEntry = entry || (prev?.looksLikeDownloadEntry == true),
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
            add(resolved, kindOf(resolved), resolved.contains("download", true))
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

    companion object {
        const val MAX_PAGE_BYTES = 4 * 1024 * 1024
        const val MAX_LIST = 600
    }
}

package com.tuxundown.android.sniff

import com.tuxundown.android.core.DownloadEngine
import java.net.HttpURLConnection
import java.net.URL

data class SniffedResource(
    val url: String,
    val name: String,
    val kind: String, // apk / archive / video / audio / image / document / other
    /** Anchor text suggested this is THE download entry of the page. */
    val looksLikeDownloadEntry: Boolean = false,
)

sealed class SniffResult {
    data class DirectFile(val url: String, val fileName: String) : SniffResult()
    data class ResourceList(val pageUrl: String, val resources: List<SniffedResource>) : SniffResult()
    data class Failure(val reason: String) : SniffResult()
}

/** Layer 1: pure HTML parsing (unit-testable, no network). */
object SniffParser {
    val EXT_KIND = mapOf(
        "apk" to "apk", "xapk" to "apk",
        "zip" to "archive", "rar" to "archive", "7z" to "archive",
        "tar" to "archive", "gz" to "archive", "xz" to "archive", "bz2" to "archive",
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
        "dmg" to "other", "deb" to "other", "json" to "other", "torrent" to "other",
    )

    private val ATTR = Regex("""(?:href|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ANCHOR = Regex(
        """<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

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

    fun extract(html: String, baseUrl: String): List<SniffedResource> {
        val byUrl = linkedMapOf<String, SniffedResource>()
        // Anchors first: their text tells us which link is the download entry.
        for (m in ANCHOR.findAll(html)) {
            val resolved = resolve(baseUrl, m.groupValues[1]) ?: continue
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val kind = kindOf(resolved)
            val isEntry = text.contains("下载") || text.contains("download", true) ||
                resolved.contains("download", true) || resolved.contains("/dl/", true) ||
                resolved.contains("down.php", true)
            if (kind == null && !isEntry) continue
            val prev = byUrl[resolved]
            byUrl[resolved] = SniffedResource(
                url = resolved,
                name = DownloadEngine.fileNameFrom(null, resolved),
                kind = kind ?: "other",
                looksLikeDownloadEntry = isEntry || (prev?.looksLikeDownloadEntry == true),
            )
        }
        // Then bare href/src attributes (media tags, link tags...).
        for (m in ATTR.findAll(html)) {
            val resolved = resolve(baseUrl, m.groupValues[1]) ?: continue
            val kind = kindOf(resolved) ?: continue
            if (resolved !in byUrl) {
                byUrl[resolved] = SniffedResource(
                    url = resolved,
                    name = DownloadEngine.fileNameFrom(null, resolved),
                    kind = kind,
                )
            }
        }
        return byUrl.values.toList()
    }
}

/** Layers 1+2 with network: fetch pages, follow the single download path. */
class Sniffer(private val userAgent: String = DownloadEngine.DEFAULT_UA) {

    private data class Fetched(val url: String, val contentType: String, val body: String)

    private fun fetch(url: String): Fetched {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        val type = c.contentType ?: ""
        val finalUrl = c.url.toString()
        if (!type.contains("html", true) && !type.contains("text/", true)) {
            c.disconnect()
            return Fetched(finalUrl, type, "")
        }
        val body = c.inputStream.bufferedReader(
            charset("UTF-8")
        ).use { it.readText() }
        c.disconnect()
        return Fetched(finalUrl, type, body)
    }

    private fun isFileResponse(url: String, contentType: String): Boolean {
        if (contentType.contains("html", true)) return false
        if (contentType.isNotBlank() && !contentType.contains("text/", true)) return true
        return SniffParser.kindOf(url) != null && !url.endsWith(".html", true)
    }

    /**
     * Sniff [startUrl]. If it already is a file → DirectFile.
     * Layer 2: while the page offers exactly ONE plausible download entry,
     * follow it (max 5 hops) until a real file shows up; if a page offers
     * several candidates, hand the list back for the user to pick.
     */
    fun sniff(startUrl: String): SniffResult {
        var current = startUrl
        val visited = mutableSetOf<String>()
        repeat(6) {
            if (!visited.add(current)) {
                return SniffResult.Failure("页面跳转成环，无法继续自动追踪")
            }
            val f = try {
                fetch(current)
            } catch (e: Exception) {
                return SniffResult.Failure("打开页面失败：${e.message}")
            }
            if (isFileResponse(f.url, f.contentType)) {
                return SniffResult.DirectFile(
                    f.url, DownloadEngine.fileNameFrom(null, f.url)
                )
            }
            val resources = SniffParser.extract(f.body, f.url)
            if (resources.isEmpty()) {
                return SniffResult.Failure(
                    "这个页面里没找到可下载的资源。可以试试内置浏览器打开，手动点到下载那一步。"
                )
            }
            val entries = resources.filter { it.looksLikeDownloadEntry }
            val single = when {
                entries.size == 1 -> entries[0]
                entries.isEmpty() && resources.size == 1 -> resources[0]
                else -> null
            }
            if (single == null) {
                return SniffResult.ResourceList(f.url, resources)
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
}

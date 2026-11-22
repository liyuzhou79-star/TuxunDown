package com.tuxundown.android.core

/**
 * Captive-portal detection (v4.4, audit round 29) — pure,
 * unit-tested.
 *
 * A hotel / campus / airport WiFi that wants a login first
 * answers EVERY http(s) request — including a file download —
 * with a redirect to its login page, which ends as a 200 HTML
 * page. Where the engine expected FILE bytes, an HTML answer
 * is therefore never the file: it is either a portal (the
 * network needs a login) or, on an already-working network,
 * a web page where a direct link was expected.
 *
 * Before this round the ONLY HTML check lived in probe()
 * (fresh starts). A portal appearing MID-download produced:
 * - segmented: the status / Content-Range gauntlet refused
 *   the bytes (integrity held) but the task FAILED with a
 *   misleading message and nothing parked it;
 * - single-stream with an UNKNOWN total: the login page's
 *   own length was adopted as the total and the page was
 *   saved as a DONE "file" — a clean integrity hole;
 * - HLS: the login page was parsed as a playlist and failed
 *   with "no segments", again with no park.
 *
 * This object is the ONE detector every path now shares.
 * The engine throws [PortalBlockedException]; the service
 * decides what it means (park while the network is not yet
 * validated, fail plainly when it is — see DownloadService).
 */
object PortalDetect {

    /** True when a Content-Type header says the body is a
     *  web page, not file bytes. */
    fun isHtmlContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val ct = contentType.lowercase()
        return ct.contains("text/html") || ct.contains("application/xhtml")
    }

    /**
     * True when the first bytes of a body are unmistakably an
     * HTML document. Deliberately narrow — only the document
     * prologues a portal / error page starts with. A playlist
     * starts with #EXTM3U, a .ts segment with 0x47, a key with
     * arbitrary binary: none of them can trip this.
     */
    fun looksLikeHtml(head: ByteArray, length: Int = head.size): Boolean {
        if (length <= 0) return false
        // Decode just the prologue; a portal page's first tag
        // always lands well inside 512 bytes.
        val n = minOf(length, head.size, 512)
        val text = String(head, 0, n, Charsets.UTF_8)
        return looksLikeHtml(text)
    }

    /** String twin of [looksLikeHtml] for text bodies. */
    fun looksLikeHtml(text: String): Boolean {
        var s = text.trimStart()
        // A UTF-8 BOM decoded as a char rides at the front.
        if (s.startsWith("\uFEFF")) s = s.drop(1).trimStart() // (BOM char)
        val lower = s.lowercase()
        return lower.startsWith("<!doctype") ||
            lower.startsWith("<html") ||
            lower.startsWith("<head")
    }

    /** The plain-language park note the service stamps on a
     *  portal-parked task's card. */
    const val PARK_NOTE_PREFIX: String =
        "这个 WiFi 要先登录：打开浏览器完成登录后会自动继续，不用管它"
}

/**
 * Thrown by the engine when a response that should have
 * carried file bytes is an HTML page instead (see
 * [PortalDetect]). The message is plain Chinese, so
 * Errors.human passes it through untouched if the task
 * ends FAILED rather than parked.
 */
class PortalBlockedException(
    message: String = "下载被 WiFi 的登录页拦住了：这个网络要先登录才能上网",
) : IllegalStateException(message)

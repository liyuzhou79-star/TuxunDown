package com.tuxundown.android.core

/**
 * v7.2: the ONE redactor for free text that is about to leave
 * the device through a copy / share出口 (diagnostics, the
 * one-tap health check, the crash page). Before this round each
 * surface kept its own rules — the health check's corpus had
 * grown shapes the others never learned (a standalone Bearer
 * token, a Cookie: line), and the crash page copied its log
 * with no pass at all. Now every copy出口 funnels through
 * [forCopy], and the corpus lives in exactly one place, pinned
 * by RedactTest against fake secret-shaped data.
 *
 * What [forCopy] removes, in order:
 * - a "v1:" Keystore ciphertext blob (the proxy password's
 *   stored form) — removed outright;
 * - a whole Cookie: header line's value (a cookie string runs
 *   to the end of its line, "; "-separated pairs included);
 * - Bearer / Basic credential blobs;
 * - key=value (or key: value) pairs whose NAME carries a
 *   credential feature — password / passwd / pwd / token /
 *   secret / authorization / cookie / api-key / apikey /
 *   auth-token / access-token and kin: the value is hidden,
 *   the name stays so the reader can still see WHAT was there;
 * - URLs collapse via [TaskDiagnostics.redactedUrl]: userinfo
 *   dropped, query and fragment folded into one marker.
 *
 * Plain text that carries none of these shapes passes through
 * untouched. Pure — unit-tested.
 */
object Redact {

    private val urlToken = Regex("""(?:https?|ftp)://\S+""")
    private val cookieLine = Regex("""(?im)^(.*cookie\s*:\s*).*$""")
    private val secretPair = Regex(
        """(?i)\b(access[-_]?token|refresh[-_]?token|auth[-_]?token|api[-_]?key|apikey|password|passwd|pwd|token|secret|authorization|cookie)\b\s*[:=]\s*\S+"""
    )
    private val bearerAuth = Regex("""(?i)bearer\s+\S+""")
    private val basicAuth = Regex("""(?i)basic\s+[A-Za-z0-9+/=]{8,}""")
    private val ciphertext = Regex("""v1:[A-Za-z0-9+/=]{8,}""")

    /** [value] made safe to copy out of the app. */
    fun forCopy(value: String): String {
        var s = value
        s = ciphertext.replace(s) { "（密文已隐藏）" }
        s = cookieLine.replace(s) { m -> m.groupValues[1] + "（已隐藏）" }
        s = bearerAuth.replace(s) { "Bearer （凭据已隐藏）" }
        s = basicAuth.replace(s) { "（凭据已隐藏）" }
        s = secretPair.replace(s) { m -> m.groupValues[1] + "：（已隐藏）" }
        s = urlToken.replace(s) { m -> TaskDiagnostics.redactedUrl(m.value) }
        return s
    }

    /**
     * True when [url] carries something a share出口 should warn
     * about before the list leaves the device: a query string
     * (signed links keep their token there) or userinfo
     * (http://user:pass@…). Used by the share lists (v7.2) to
     * add ONE plain reminder line — the links themselves stay
     * whole, because re-importing the list needs them.
     */
    fun urlCarriesCredentials(url: String): Boolean {
        if ('?' in url) return true
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        if (uri != null) return !uri.userInfo.isNullOrEmpty()
        // Unparseable: an @ in the authority segment is userinfo.
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return false
        return '@' in rest.substringBefore('/')
    }
}

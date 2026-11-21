package com.tuxundown.android.core

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Base64

/**
 * Download proxy (v1.9): one app-wide proxy for the download
 * engine and the sniffer (HTTP/HTTPS traffic). Deliberately NOT
 * applied to FTP downloads (the hand-written FTP client speaks
 * no proxy protocol) or to the built-in browser's page loads
 * (the system WebView offers no per-app proxy API) — the
 * settings copy says both plainly.
 *
 * Failure honesty: when a proxy is configured, connections are
 * opened ONLY through it. If the proxy cannot be reached, the
 * request fails with a plain "代理连不上" message — there is
 * never a silent fall-back to a direct connection.
 */
data class ProxySettings(
    val enabled: Boolean = false,
    /** [TYPE_HTTP] or [TYPE_SOCKS]. */
    val type: Int = TYPE_HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** Usable = switched on AND host/port filled in sanely. An
     *  enabled-but-incomplete proxy is NOT active — the engine
     *  then connects directly and the settings page shows the
     *  validation problem; it never pretends to use a proxy
     *  that was never fully specified. */
    val active: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    fun javaProxy(): Proxy? {
        if (!active) return null
        val kind = if (type == TYPE_SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(kind, InetSocketAddress.createUnresolved(host.trim(), port))
    }

    /** "127.0.0.1:7890"-style label for the UI and error text. */
    fun label(): String = "${host.trim()}:$port"

    fun typeLabel(): String = if (type == TYPE_SOCKS) "SOCKS5" else "HTTP"

    /**
     * Proxy-Authorization value for an HTTP proxy with
     * credentials; null in every other case. SOCKS5 credentials
     * cannot ride a header — the JDK asks an Authenticator,
     * which [ProxyState] installs.
     */
    fun proxyAuthHeader(): String? {
        if (!active || type != TYPE_HTTP || username.isEmpty()) return null
        val raw = "$username:$password"
        return "Basic " + Base64.getEncoder()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val TYPE_HTTP = 0
        const val TYPE_SOCKS = 1

        /** Plain-language validation problem, or null when fine. */
        fun validate(enabled: Boolean, host: String, portText: String): String? {
            if (!enabled) return null
            if (host.isBlank()) return "代理开着，但主机地址没填"
            if (host.any { it.isWhitespace() }) return "代理主机地址里不能有空格"
            val p = portText.trim().toIntOrNull()
                ?: return "代理端口要填数字（1–65535）"
            if (p !in 1..65535) return "代理端口要在 1–65535 之间"
            return null
        }

        /** The one and only wording for "the proxy is down". */
        fun unreachableMessage(s: ProxySettings, cause: Throwable): String =
            "代理服务器连不上（${s.typeLabel()} ${s.label()}），已停下，没有改走直连。" +
                "请检查代理地址和端口，或在设置里先关掉代理。" +
                (cause.message?.let { "（$it）" } ?: "")
    }
}

/**
 * Runtime mirror of the proxy settings (the AmoledState
 * pattern): DownloadCenter owns persistence and pushes the
 * current value here on every change; the sniff layer, whose
 * objects are constructed ad-hoc at call sites, reads it here.
 */
object ProxyState {
    @Volatile var settings: ProxySettings = ProxySettings()

    /** Open a connection honouring the current proxy (if any). */
    fun open(url: URL): HttpURLConnection {
        val p = settings.javaProxy()
        val c = if (p != null) {
            url.openConnection(p) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        settings.proxyAuthHeader()?.let {
            c.setRequestProperty("Proxy-Authorization", it)
        }
        return c
    }

    /**
     * SOCKS5 username/password has no per-connection API in
     * HttpURLConnection — the JDK asks the default Authenticator.
     * Installed from DownloadCenter whenever the settings change;
     * it answers ONLY proxy challenges and reads the CURRENT
     * settings on every call, so turning the proxy off (or
     * clearing the username) disarms it without uninstalling.
     */
    fun installAuthenticator() {
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                if (requestorType != RequestorType.PROXY) return null
                val cur = settings
                if (!cur.active || cur.type != ProxySettings.TYPE_SOCKS ||
                    cur.username.isEmpty()
                ) return null
                return java.net.PasswordAuthentication(
                    cur.username, cur.password.toCharArray()
                )
            }
        })
    }
}

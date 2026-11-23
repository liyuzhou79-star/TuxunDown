package com.tuxundown.android.core

import java.io.ByteArrayOutputStream
import java.net.URL

/**
 * v3.8: the creation-time master-playlist probe behind the
 * HLS 清晰度 picker. One light GET of the playlist (through
 * the configured proxy, with the caller's session headers —
 * the same Cookie/Referer/UA a task created from the sniffer
 * would carry), parsed by the pure [Hls].
 *
 * Returns the variant list ONLY when the URL really serves a
 * master playlist with at least one variant. Every other
 * outcome — media playlist, HTTP error, timeout, garbage —
 * returns null, and the caller falls back to creating the
 * task exactly as before (the engine then resolves the
 * master itself, highest bandwidth, as it always has). The
 * probe is an offer, never a gate: it must not be able to
 * make a previously-working creation fail.
 *
 * The variant URLs are resolved against the FINAL URL after
 * redirects (a redirecting master is common on CDNs, and its
 * relative variants belong to the target, not the shortlink).
 *
 * v8.1 audit round 66: redirects are followed MANUALLY, never
 * by the platform. This probe carries a real session Cookie,
 * and a platform-followed redirect re-sends the headers it was
 * given to whatever host the Location names — the v4.7 leak
 * shape (a credential riding to a destination picked by the
 * server, not by the user). Each hop here re-asks
 * [SessionScope]: the Cookie (and an Authorization, if one was
 * passed) goes out only while the hop stays on the ORIGINAL
 * url's host, exactly the engine's per-hop rule. Referer rides
 * every hop unchanged, as SessionScope's doc rules for it.
 */
object HlsProbe {

    private const val CAP_BYTES = 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun fetchVariants(
        url: String,
        headers: Map<String, String>,
        fallbackUa: String,
    ): List<Hls.Variant>? = runCatching {
        var current = url
        var redirects = 0
        var result: List<Hls.Variant>? = null
        var done = false
        while (!done) {
            val c = ProxyState.open(URL(current))
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = false
            c.setRequestProperty(
                "User-Agent",
                UserAgents.resolve(headers["User-Agent"], fallbackUa),
            )
            SessionScope.cookieFor(headers["Cookie"], url, current)
                ?.let { c.setRequestProperty("Cookie", it) }
            SessionScope.authorizationFor(headers["Authorization"], url, current)
                ?.let { c.setRequestProperty("Authorization", it) }
            headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
            val code = c.responseCode
            when {
                code in 301..308 && code != 304 && code != 306 -> {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                        done = true
                    } else {
                        current = URL(URL(current), loc).toString()
                        redirects++
                    }
                }
                code !in 200..299 -> {
                    c.disconnect()
                    done = true
                }
                else -> {
                    val text = c.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > CAP_BYTES) break
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    }
                    val parsed = Hls.parse(text, current)
                    if (parsed is Hls.Playlist.Master && parsed.variants.isNotEmpty()) {
                        result = parsed.variants
                    }
                    done = true
                }
            }
        }
        result
    }.getOrNull()
}

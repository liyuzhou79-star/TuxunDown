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
 */
object HlsProbe {

    private const val CAP_BYTES = 1024 * 1024

    fun fetchVariants(
        url: String,
        headers: Map<String, String>,
        fallbackUa: String,
    ): List<Hls.Variant>? = runCatching {
        val c = ProxyState.open(URL(url))
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.instanceFollowRedirects = true
        c.setRequestProperty(
            "User-Agent",
            UserAgents.resolve(headers["User-Agent"], fallbackUa),
        )
        headers["Cookie"]?.let { c.setRequestProperty("Cookie", it) }
        headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
        if (c.responseCode !in 200..299) {
            c.disconnect()
            return null
        }
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
        val parsed = Hls.parse(text, c.url.toString())
        if (parsed is Hls.Playlist.Master && parsed.variants.isNotEmpty()) {
            parsed.variants
        } else {
            null
        }
    }.getOrNull()
}

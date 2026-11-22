package com.tuxundown.android.core

/**
 * Connection warmup decision (v4.2, knife 4): WHICH sources get
 * a warm-up request when a task starts.
 *
 * The warmup itself lives in DownloadEngine: while the probe /
 * resume revalidation is still in flight, a side thread resolves
 * each warmed source's host and issues ONE Range bytes=0-0
 * request through the normal open path, draining the 1-byte 206
 * body so the socket lands in the platform keep-alive pool. The
 * probe and the first chunk requests then reuse a hot connection
 * instead of paying a fresh DNS + TCP + TLS round trip each.
 * This object is only the pure decision — which sources, in
 * which order, under which settings — so the whole rule table
 * is unit-testable without an engine or a network.
 *
 * Rules:
 * - Proxy active -> NOTHING is warmed. A warmup request through
 *   the proxy would just be the probe itself (no saving, and it
 *   could mislead), and a warmup that bypassed the proxy to open
 *   a DIRECT connection would break the v1.9 iron rule: when a
 *   proxy is configured, connections go through it or fail
 *   honestly — never a silent direct connection. Warmup, which
 *   is pure optimisation, must certainly never go direct.
 * - Only http / https sources qualify. FTP has no
 *   HttpURLConnection path at all (the hand-written FtpClient
 *   speaks its own protocol), and every other scheme has no
 *   keep-alive pool to warm, so they are filtered out.
 * - The result is the primary URL first (when it qualifies),
 *   then the qualifying mirrors in their stored order,
 *   de-duplicated, capped at 1 + [TaskStore.MAX_SOURCES] total
 *   (the same ceiling TaskStore enforces on a record's mirror
 *   list, plus the primary itself).
 *
 * Pure — unit-tested (WarmupTest).
 */
object Warmup {

    /**
     * The sources to warm for a task starting now: [url] is the
     * primary, [sources] its mirrors, [proxyActive] the current
     * proxy state (see the class doc for why an active proxy
     * disables warmup entirely).
     */
    fun sourcesToWarm(
        url: String,
        sources: List<String>,
        proxyActive: Boolean,
    ): List<String> {
        if (proxyActive) return emptyList()
        val limit = 1 + TaskStore.MAX_SOURCES
        val out = ArrayList<String>(minOf(limit, sources.size + 1))
        val seen = HashSet<String>()
        fun consider(candidate: String) {
            if (out.size >= limit) return
            val trimmed = candidate.trim()
            if (!isHttpOrHttps(trimmed)) return
            if (!seen.add(trimmed)) return
            out.add(trimmed)
        }
        consider(url)
        for (source in sources) {
            if (out.size >= limit) break
            consider(source)
        }
        return out
    }

    /** http / https in absolute "scheme://" form only. */
    private fun isHttpOrHttps(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}

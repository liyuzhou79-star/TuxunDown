package com.tuxundown.android.core

import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS (m3u8) parsing + AES-128 segment decryption — pure logic,
 * unit-tested. The engine (DownloadEngine.runHls) fetches the
 * playlist and the segments; everything here is string/byte work.
 *
 * Scope, honestly: media playlists and master playlists (the
 * engine still picks the highest-bandwidth variant on its own;
 * since v3.8 the creation flows offer the user the choice first
 * when a master carries more than one — see [needsChoice]),
 * relative URLs, and the common
 * EXT-X-KEY AES-128 scheme via the platform's built-in
 * javax.crypto — no ffmpeg, no transcoding, no new dependency.
 * Other key methods (SAMPLE-AES etc.) are reported, not faked.
 */
object Hls {

    data class Variant(
        val bandwidth: Long,
        val url: String,
        /** Raw RESOLUTION attribute ("1280x720"), "" when absent. */
        val resolution: String = "",
    )

    data class Segment(
        val url: String,
        val durationSec: Double,
        /** Resolved EXT-X-KEY URI in force for this segment, if any. */
        val keyUri: String?,
        /** Key method in force ("AES-128", "NONE", …), if any. */
        val keyMethod: String?,
        /** Explicit IV from the key tag, if it carried one. */
        val keyIv: ByteArray?,
        /** Media sequence number of this segment (for default IVs). */
        val sequence: Long,
    )

    sealed class Playlist {
        data class Master(val variants: List<Variant>) : Playlist()
        /** [hasMap]: the playlist uses #EXT-X-MAP (fragmented MP4) —
         *  the merged bytes would lack the init segment and not
         *  play; the engine refuses plainly instead (v1.7 audit). */
        data class Media(val segments: List<Segment>, val hasMap: Boolean = false) : Playlist()
    }

    private fun attr(line: String, name: String): String? {
        val m = Regex("""$name=("[^"]*"|[^,]*)""").find(line) ?: return null
        return m.groupValues[1].trim('"')
    }

    fun resolve(base: String, ref: String): String =
        runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)

    fun parse(text: String, baseUrl: String): Playlist {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variants = mutableListOf<Variant>()
            var pendingBw = 0L
            var pendingRes = ""
            var expectUrl = false
            for (line in lines) {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pendingBw = attr(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
                    pendingRes = attr(line, "RESOLUTION") ?: ""
                    expectUrl = true
                } else if (expectUrl && !line.startsWith("#")) {
                    variants.add(Variant(pendingBw, resolve(baseUrl, line), pendingRes))
                    expectUrl = false
                }
            }
            return Playlist.Master(variants)
        }
        var mediaSeq = 0L
        var hasMap = false
        var keyMethod: String? = null
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        var pendingDuration = 0.0
        var expectSeg = false
        val segments = mutableListOf<Segment>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP:") -> hasMap = true
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    keyMethod = attr(line, "METHOD")
                    if (keyMethod == "NONE") {
                        keyUri = null
                        keyIv = null
                    } else {
                        keyUri = attr(line, "URI")?.let { resolve(baseUrl, it) }
                        keyIv = attr(line, "IV")?.let { runCatching { hexToBytes(it) }.getOrNull() }
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',')
                        .trim().toDoubleOrNull() ?: 0.0
                    expectSeg = true
                }
                line.startsWith("#") -> Unit // other tags carry no segments
                expectSeg -> {
                    segments.add(
                        Segment(
                            url = resolve(baseUrl, line),
                            durationSec = pendingDuration,
                            keyUri = keyUri,
                            keyMethod = keyMethod,
                            keyIv = keyIv,
                            sequence = mediaSeq + segments.size,
                        )
                    )
                    expectSeg = false
                }
            }
        }
        return Playlist.Media(segments, hasMap)
    }

    // ---- v3.8: user-facing variant choice ----
    // Until now a master playlist was resolved silently inside
    // the engine (highest bandwidth wins). The creation flows
    // now offer the choice up front when there IS one; these
    // three functions are the pure part of that offer.

    /** A choice exists only when a master playlist carries
     *  more than one variant; a single variant (or a media
     *  playlist) is downloaded exactly as before. */
    fun needsChoice(p: Playlist): Boolean =
        p is Playlist.Master && p.variants.size > 1

    /** Display order for the picker: highest bandwidth first
     *  (the order a user scans qualities in). Stable, so equal
     *  bandwidths keep playlist order; when NO variant declares
     *  a bandwidth the playlist order is kept untouched —
     *  sorting on all-zero keys would be a meaningless shuffle. */
    fun sortedForChoice(variants: List<Variant>): List<Variant> =
        if (variants.any { it.bandwidth > 0 }) {
            variants.sortedByDescending { it.bandwidth }
        } else {
            variants
        }

    /**
     * One picker row: "1080p · 1920×1080 · 约 5.0 Mbps".
     * Missing pieces are simply left out — a variant that
     * declares neither resolution nor bandwidth falls back to
     * its file name (then its host), never to an invented
     * quality. BANDWIDTH is bits/s by the HLS spec, so the
     * rate is printed in Mbps/Kbps, NOT via Format.speed
     * (which is bytes/s and would understate it 8x).
     */
    fun variantLabel(v: Variant): String {
        val parts = mutableListOf<String>()
        val res = v.resolution.trim()
        if (res.isNotBlank()) {
            val m = Regex("""(\d+)\s*[xX×]\s*(\d+)""").find(res)
            if (m != null) {
                val w = m.groupValues[1]
                val h = m.groupValues[2]
                parts.add("${h}p")
                parts.add("$w×$h")
            } else {
                parts.add(res.replace('x', '×').replace('X', '×'))
            }
        }
        if (v.bandwidth > 0) {
            parts.add(
                if (v.bandwidth >= 1_000_000) {
                    val mbps = v.bandwidth / 1_000_000.0
                    "约 " + (if (mbps % 1.0 == 0.0) {
                        mbps.toInt().toString()
                    } else {
                        "%.1f".format(mbps)
                    }) + " Mbps"
                } else {
                    "约 ${(v.bandwidth + 500) / 1000} Kbps"
                }
            )
        }
        if (parts.isEmpty()) {
            val name = v.url.substringBefore('?').substringBefore('#')
                .substringAfterLast('/')
            parts.add(
                if (name.isNotBlank()) name
                else runCatching { java.net.URL(v.url).host }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "未知清晰度"
            )
        }
        return parts.joinToString(" · ")
    }

    /**
     * v2.1 audit (round 7, C6): a stable fingerprint of the
     * playlist's segment URL list, in order. The engine's resume
     * state is a written PREFIX of the segment list; before this,
     * the resume check compared only the segment COUNT — a
     * playlist whose content changed while keeping the same count
     * (an updated VOD, a slid live window) kept the old prefix
     * bytes and appended the new playlist's tail, producing a
     * silently corrupt merged file that reported DONE. The
     * fingerprint makes "same playlist" mean same segments.
     * SHA-256 over the newline-joined URLs; "" for an empty list.
     */
    fun fingerprint(segmentUrls: List<String>): String {
        if (segmentUrls.isEmpty()) return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val joined = segmentUrls.joinToString("\n")
        return digest.digest(joined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X")
        val padded = if (h.length % 2 == 1) "0$h" else h
        return ByteArray(padded.length / 2) {
            padded.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * The default IV when an AES-128 key tag carries no IV: the
     * segment's media sequence number as a 16-byte big-endian value.
     */
    fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return iv
    }

    /** AES-128 CBC decrypt with PKCS7 padding (platform crypto). */
    fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}

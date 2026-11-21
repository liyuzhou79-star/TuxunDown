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
 * highest-bandwidth variant wins), relative URLs, and the common
 * EXT-X-KEY AES-128 scheme via the platform's built-in
 * javax.crypto — no ffmpeg, no transcoding, no new dependency.
 * Other key methods (SAMPLE-AES etc.) are reported, not faked.
 */
object Hls {

    data class Variant(val bandwidth: Long, val url: String)

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
            var expectUrl = false
            for (line in lines) {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pendingBw = attr(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
                    expectUrl = true
                } else if (expectUrl && !line.startsWith("#")) {
                    variants.add(Variant(pendingBw, resolve(baseUrl, line)))
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

package com.tuxundown.android

import com.tuxundown.android.core.Hls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** v1.6: HLS playlist parsing + AES-128 segment decryption. */
class HlsTest {

    @Test
    fun mediaPlaylistParsesSegmentsDurationsAndSequence() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:42
            #EXTINF:9.8,
            seg-42.ts
            #EXTINF:10.0,
            https://cdn.example.com/v/seg-43.ts
            #EXTINF:4.2,
            ../seg-44.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = Hls.parse(text, "https://example.com/live/index.m3u8")
            as Hls.Playlist.Media
        assertEquals(3, media.segments.size)
        assertEquals("https://example.com/live/seg-42.ts", media.segments[0].url)
        assertEquals(42L, media.segments[0].sequence)
        assertEquals(43L, media.segments[1].sequence)
        assertEquals("https://example.com/seg-44.ts", media.segments[2].url)
        assertEquals(9.8, media.segments[0].durationSec, 0.001)
        assertTrue(media.segments.all { it.keyUri == null })
    }

    @Test
    fun masterPlaylistParsesVariants() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            hi/index.m3u8
        """.trimIndent()
        val master = Hls.parse(text, "https://example.com/master.m3u8")
            as Hls.Playlist.Master
        assertEquals(2, master.variants.size)
        val best = master.variants.maxByOrNull { it.bandwidth }!!
        assertEquals("https://example.com/hi/index.m3u8", best.url)
        assertEquals(2_500_000L, best.bandwidth)
    }

    @Test
    fun masterPlaylistParsesResolutionsAndResolvesRelativeVariants() {
        // v3.8: RESOLUTION is captured per variant, and relative
        // variant URLs resolve against the master URL (including
        // ../ hops), exactly like segment URLs do.
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=1920x1080
            ../hi/index.m3u8?token=abc
            #EXT-X-STREAM-INF:BANDWIDTH=2500000
            mid.m3u8
        """.trimIndent()
        val master = Hls.parse(text, "https://example.com/a/b/master.m3u8")
            as Hls.Playlist.Master
        assertEquals(3, master.variants.size)
        assertEquals("640x360", master.variants[0].resolution)
        assertEquals("https://example.com/a/b/low/index.m3u8", master.variants[0].url)
        assertEquals(0L, master.variants[1].bandwidth)
        assertEquals("https://example.com/a/hi/index.m3u8?token=abc", master.variants[1].url)
        assertEquals("", master.variants[2].resolution)
        assertEquals("https://example.com/a/b/mid.m3u8", master.variants[2].url)
    }

    @Test
    fun variantLabelShowsWhatIsKnownAndInventsNothing() {
        // Full house: p-label + dimensions + bitrate (bits/s,
        // printed as Mbps — NOT bytes/s).
        assertEquals(
            "1080p · 1920×1080 · 约 5 Mbps",
            Hls.variantLabel(Hls.Variant(5_000_000, "https://x/hi.m3u8", "1920x1080")),
        )
        assertEquals(
            "720p · 1280×720 · 约 2.5 Mbps",
            Hls.variantLabel(Hls.Variant(2_500_000, "https://x/mid.m3u8", "1280x720")),
        )
        // No resolution: bitrate alone, sub-Mbps in Kbps.
        assertEquals(
            "约 800 Kbps",
            Hls.variantLabel(Hls.Variant(800_000, "https://x/low.m3u8")),
        )
        // No bandwidth: resolution alone.
        assertEquals(
            "360p · 640×360",
            Hls.variantLabel(Hls.Variant(0, "https://x/low.m3u8", "640x360")),
        )
        // Neither: the file name speaks, never an invented quality.
        assertEquals(
            "chunklist_720.m3u8",
            Hls.variantLabel(Hls.Variant(0, "https://cdn.example.com/v/chunklist_720.m3u8?x=1")),
        )
    }

    @Test
    fun choiceExistsOnlyForMultiVariantMasters() {
        val two = Hls.parse(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n a.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2\nb.m3u8",
            "https://x/master.m3u8",
        )
        assertTrue(Hls.needsChoice(two))
        val one = Hls.parse(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nonly.m3u8",
            "https://x/master.m3u8",
        )
        assertTrue(!Hls.needsChoice(one))
        val media = Hls.parse(
            "#EXTM3U\n#EXTINF:5,\nseg.ts",
            "https://x/media.m3u8",
        )
        assertTrue(!Hls.needsChoice(media))
    }

    @Test
    fun sortedForChoicePutsHighestBandwidthFirstStably() {
        val vs = listOf(
            Hls.Variant(800_000, "https://x/low.m3u8"),
            Hls.Variant(5_000_000, "https://x/hi.m3u8"),
            Hls.Variant(800_000, "https://x/low2.m3u8"),
        )
        assertEquals(
            listOf("https://x/hi.m3u8", "https://x/low.m3u8", "https://x/low2.m3u8"),
            Hls.sortedForChoice(vs).map { it.url },
        )
        // All-zero bandwidths: playlist order is kept as-is.
        val zeros = listOf(
            Hls.Variant(0, "https://x/a.m3u8"),
            Hls.Variant(0, "https://x/b.m3u8"),
        )
        assertEquals(zeros, Hls.sortedForChoice(zeros))
    }

    @Test
    fun keyTagAppliesToFollowingSegments() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0000000000000000000000000000002a
            #EXTINF:8.0,
            enc1.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:8.0,
            plain.ts
        """.trimIndent()
        val media = Hls.parse(text, "https://example.com/v/index.m3u8")
            as Hls.Playlist.Media
        assertEquals(2, media.segments.size)
        val enc = media.segments[0]
        assertEquals("https://example.com/v/key.bin", enc.keyUri)
        assertEquals("AES-128", enc.keyMethod)
        assertEquals(42, enc.keyIv!![15].toInt())
        assertEquals(null, media.segments[1].keyUri)
    }

    @Test
    fun aes128DecryptRoundTrips() {
        val key = ByteArray(16) { (it * 7 + 1).toByte() }
        val iv = Hls.sequenceIv(42)
        val plain = "hello hls segment world!!".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain)
        val back = Hls.decryptAes128(encrypted, key, iv)
        assertEquals(plain.toList(), back.toList())
    }

    @Test
    fun fingerprintDistinguishesPlaylists() {
        // v2.1 audit C6: same count, different content MUST differ —
        // that is the stale-prefix hole the fingerprint closes.
        val a = Hls.fingerprint(listOf("https://x/1.ts", "https://x/2.ts"))
        val b = Hls.fingerprint(listOf("https://x/1.ts", "https://x/CHANGED.ts"))
        assertTrue(a.isNotBlank())
        assertTrue("same segment count, different segments must not collide", a != b)
        // Order is part of the identity (a prefix is positional).
        val c = Hls.fingerprint(listOf("https://x/2.ts", "https://x/1.ts"))
        assertTrue("segment order matters", a != c)
        // Stable for the same list, and empty for no segments.
        assertEquals(a, Hls.fingerprint(listOf("https://x/1.ts", "https://x/2.ts")))
        assertEquals("", Hls.fingerprint(emptyList()))
    }

    @Test
    fun sequenceIvIsBigEndian() {
        val iv = Hls.sequenceIv(0x0102)
        assertEquals(16, iv.size)
        assertEquals(0x01.toByte(), iv[14])
        assertEquals(0x02.toByte(), iv[15])
        assertTrue(iv.take(14).all { it == 0.toByte() })
    }
}

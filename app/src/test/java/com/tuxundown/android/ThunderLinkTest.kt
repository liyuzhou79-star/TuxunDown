package com.tuxundown.android

import com.tuxundown.android.core.ThunderLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.5: thunder:// envelope decoding. The fixed vectors were
 *  produced by an independent Base64 encoder from the
 *  documented "AA" + url + "ZZ" shape, so the test pins the
 *  FORMAT, not just round-trip self-consistency. */
class ThunderLinkTest {

    @Test
    fun decodesFixedVectors() {
        assertEquals(
            "https://example.com/file.zip",
            ThunderLink.decode("thunder://QUFodHRwczovL2V4YW1wbGUuY29tL2ZpbGUuemlwWlo="),
        )
        assertEquals(
            "http://mirrors.example.com/ubuntu.iso",
            ThunderLink.decode("thunder://QUFodHRwOi8vbWlycm9ycy5leGFtcGxlLmNvbS91YnVudHUuaXNvWlo="),
        )
        assertEquals(
            "ftp://ftp.example.com/pub/readme.txt",
            ThunderLink.decode("thunder://QUFmdHA6Ly9mdHAuZXhhbXBsZS5jb20vcHViL3JlYWRtZS50eHRaWg=="),
        )
    }

    @Test
    fun decodesWithoutMarkersAndWithMessyInput() {
        // No AA/ZZ markers at all.
        assertEquals(
            "https://example.com/plain.bin",
            ThunderLink.decode("thunder://aHR0cHM6Ly9leGFtcGxlLmNvbS9wbGFpbi5iaW4="),
        )
        // Case-insensitive scheme, surrounding whitespace.
        assertEquals(
            "https://example.com/file.zip",
            ThunderLink.decode("  THUNDER://QUFodHRwczovL2V4YW1wbGUuY29tL2ZpbGUuemlwWlo=  "),
        )
        // Percent-encoded padding (%3D = '=').
        assertEquals(
            "https://example.com/file.zip",
            ThunderLink.decode("thunder://QUFodHRwczovL2V4YW1wbGUuY29tL2ZpbGUuemlwWlo%3D"),
        )
    }

    @Test
    fun refusesWhatIsNotAWebAddress() {
        // Not a thunder link at all.
        assertNull(ThunderLink.decode("https://example.com/file.zip"))
        assertNull(ThunderLink.decode(""))
        // Valid Base64, but the payload is prose, not a URL.
        val prose = "thunder://" + java.util.Base64.getEncoder()
            .encodeToString("AAhello worldZZ".toByteArray())
        assertNull(ThunderLink.decode(prose))
        // An ed2k envelope: decodes fine as Base64, but ed2k is
        // not a protocol this app speaks — refuse, don't guess.
        val ed2k = "thunder://" + java.util.Base64.getEncoder()
            .encodeToString("AAed2k://|file|x.bin|123|ABCDEF|/ZZ".toByteArray())
        assertNull(ThunderLink.decode(ed2k))
        // Broken Base64.
        assertNull(ThunderLink.decode("thunder://!!!not-base64!!!"))
        assertNull(ThunderLink.decode("thunder://"))
    }

    @Test
    fun roundTripThroughEncoder() {
        for (url in listOf(
            "https://a.b/c d.iso".replace(" ", "%20"),
            "http://192.168.1.10:8080/f.bin?token=xyz",
            "ftp://ftp.a.b/pub/%E6%96%87%E4%BB%B6.zip",
        )) {
            val wrapped = "thunder://" + java.util.Base64.getEncoder()
                .encodeToString("AA${url}ZZ".toByteArray(Charsets.UTF_8))
            assertEquals(url, ThunderLink.decode(wrapped))
        }
    }

    @Test
    fun findsAndReplacesTokensInProse() {
        val text = "这个资源用迅雷下：thunder://QUFodHRwczovL2V4YW1wbGUuY29tL2ZpbGUuemlwWlo= ，" +
            "备用 thunder://QUFodHRwOi8vbWlycm9ycy5leGFtcGxlLmNvbS91YnVudHUuaXNvWlo="
        assertEquals(
            listOf(
                "https://example.com/file.zip",
                "http://mirrors.example.com/ubuntu.iso",
            ),
            ThunderLink.findDecoded(text),
        )
        val prep = ThunderLink.replaceTokens(text)
        assertEquals(2, prep.decoded)
        assertEquals(0, prep.failed)
        assertTrue(prep.text.contains("https://example.com/file.zip"))
        assertFalse(prep.text.contains("thunder://", ignoreCase = true))

        // A broken token is counted and removed, the good one stays.
        val mixed = ThunderLink.replaceTokens(
            "thunder://QUFodHRwczovL2V4YW1wbGUuY29tL2ZpbGUuemlwWlo= 和 thunder://AAAA"
        )
        assertEquals(1, mixed.decoded)
        assertEquals(1, mixed.failed)
        assertTrue(mixed.text.contains("https://example.com/file.zip"))
    }

    @Test
    fun plainTextIsUntouched() {
        val prep = ThunderLink.replaceTokens("https://a.b/f.zip 直接粘贴的链接")
        assertEquals(0, prep.decoded)
        assertEquals(0, prep.failed)
        assertEquals("https://a.b/f.zip 直接粘贴的链接", prep.text)
        assertFalse(ThunderLink.containsToken("https://a.b/f.zip"))
        assertTrue(ThunderLink.isThunderLink(" thunder://QUFB "))
        assertFalse(ThunderLink.isThunderLink("https://a.b/"))
    }
}

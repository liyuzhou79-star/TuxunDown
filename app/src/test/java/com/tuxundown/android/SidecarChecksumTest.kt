package com.tuxundown.android

import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.SidecarChecksum
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.8: sidecar checksum files — parse selection rules, the
 * eligibility gate (switch off = no fetch, ever), hand-typed
 * priority, primary-source-only, and the persisted source mark.
 */
class SidecarChecksumTest {

    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val sha2 = "0".repeat(64)
    private val md5 = "900150983cd24fb0d6963f7d28e17f72"
    private val md5b = "1".repeat(32)

    // ---- single-line shapes ----

    @Test
    fun gnuLineWithName() {
        assertEquals(
            Checksum.SHA256 to sha,
            SidecarChecksum.parse("$sha  app.apk", "app.apk"),
        )
    }

    @Test
    fun gnuStarAndTabLines() {
        assertEquals(
            Checksum.SHA256 to sha,
            SidecarChecksum.parse("$sha *app.apk", "app.apk"),
        )
        assertEquals(
            Checksum.MD5 to md5,
            SidecarChecksum.parse("$md5\tapp.apk", "app.apk"),
        )
    }

    @Test
    fun bsdLines() {
        assertEquals(
            Checksum.SHA256 to sha,
            SidecarChecksum.parse("SHA256 (app.apk) = $sha", "app.apk"),
        )
        assertEquals(
            Checksum.MD5 to md5,
            SidecarChecksum.parse("MD5 (app.apk) = $md5", "app.apk"),
        )
    }

    @Test
    fun pureHashLine() {
        assertEquals(Checksum.SHA256 to sha, SidecarChecksum.parse(sha, "app.apk"))
        assertEquals(Checksum.MD5 to md5, SidecarChecksum.parse("  $md5 \n", "anything.bin"))
    }

    @Test
    fun emptyAndGarbageGiveNull() {
        assertNull(SidecarChecksum.parse("", "app.apk"))
        assertNull(SidecarChecksum.parse("   \n \n", "app.apk"))
        assertNull(SidecarChecksum.parse("not a checksum file at all", "app.apk"))
        assertNull(SidecarChecksum.parse("zzzz  app.apk", "app.apk"))
    }

    // ---- multi-line selection ----

    @Test
    fun multiLinePicksTheMatchingName() {
        val body = "$sha2  other.apk\n$sha  app.apk\n$md5b  third.apk"
        assertEquals(Checksum.SHA256 to sha, SidecarChecksum.parse(body, "app.apk"))
    }

    @Test
    fun multiLineNoMatchMultipleHashesGivesUp() {
        val body = "$sha  other.apk\n$sha2  third.apk"
        assertNull(SidecarChecksum.parse(body, "app.apk"))
    }

    @Test
    fun singleHashFallbackWhenNameDiffers() {
        // The one entry names a different file, but the whole
        // body holds exactly one hash: the spec says use it.
        assertEquals(
            Checksum.SHA256 to sha,
            SidecarChecksum.parse("$sha  release-final.apk", "app.apk"),
        )
    }

    @Test
    fun sameHashRepeatedCountsAsOne() {
        val body = "$sha  a.apk\n$sha  b.apk"
        assertEquals(Checksum.SHA256 to sha, SidecarChecksum.parse(body, "app.apk"))
    }

    @Test
    fun matchingNameWithTwoDifferentHashesGivesUp() {
        val body = "$sha  app.apk\n$sha2  app.apk"
        assertNull(SidecarChecksum.parse(body, "app.apk"))
    }

    // ---- filename comparison rules (pinned) ----

    @Test
    fun nameMatchIsBasenameAndCaseInsensitive() {
        assertEquals(
            Checksum.SHA256 to sha,
            SidecarChecksum.parse("$sha  ./dist/APP.APK", "app.apk"),
        )
        assertEquals(
            Checksum.SHA256 to sha,
            SidecarChecksum.parse("$sha  dir\\App.Apk", "app.apk"),
        )
        assertTrue(SidecarChecksum.namesMatch("./x/File.ZIP", "file.zip"))
        assertFalse(SidecarChecksum.namesMatch("file.zip", "other.zip"))
        assertFalse(SidecarChecksum.namesMatch("", "file.zip"))
    }

    // ---- eligibility gate ----

    @Test
    fun switchOffNeverFetches() {
        assertFalse(SidecarChecksum.shouldFetch(false, "https://x/app.apk", false, "", ""))
        assertFalse(
            SidecarChecksum.shouldFetch(false, TaskRecord(url = "https://x/app.apk")),
        )
    }

    @Test
    fun onlyHttpAndHttpsFetch() {
        assertTrue(SidecarChecksum.shouldFetch(true, "https://x/app.apk", false, "", ""))
        assertTrue(SidecarChecksum.shouldFetch(true, "http://x/app.apk", false, "", ""))
        assertFalse(SidecarChecksum.shouldFetch(true, "ftp://x/app.apk", false, "", ""))
        assertFalse(SidecarChecksum.shouldFetch(true, "file:///x/app.apk", false, "", ""))
        assertFalse(SidecarChecksum.shouldFetch(true, "magnet:?xt=urn:x", false, "", ""))
    }

    @Test
    fun hlsAndExistingExpectedSkip() {
        assertFalse(SidecarChecksum.shouldFetch(true, "https://x/list.m3u8", true, "", ""))
        assertFalse(SidecarChecksum.shouldFetch(true, "https://x/app.apk", false, sha, ""))
        assertFalse(SidecarChecksum.shouldFetch(true, "https://x/app.apk", false, "", md5))
    }

    // ---- primary source only ----

    @Test
    fun sidecarUrlsDeriveFromPrimaryOnly() {
        val t = TaskRecord(url = "https://primary/app.apk")
        t.sources.add("https://mirror1/app.apk")
        t.sources.add("https://mirror2/app.apk")
        assertEquals(
            listOf("https://primary/app.apk.sha256", "https://primary/app.apk.md5"),
            SidecarChecksum.sidecarUrls(t.url),
        )
        // The target name also comes from the primary, never a mirror.
        assertEquals("app.apk", SidecarChecksum.targetFileName(t))
        val named = TaskRecord(url = "https://primary/dl?id=3", fileName = "real.apk")
        named.sources.add("https://mirror/other.apk")
        assertEquals("real.apk", SidecarChecksum.targetFileName(named))
    }

    // ---- apply: fill + hand-typed priority ----

    @Test
    fun applyFillsByLengthAndMarksSidecar() {
        val t = TaskRecord(url = "https://x/app.apk")
        assertTrue(SidecarChecksum.applyTo(t, Checksum.SHA256 to sha))
        assertEquals(sha, t.expectedSha256)
        assertEquals("", t.expectedMd5)
        assertTrue(t.checksumFromSidecar)
        val m = TaskRecord(url = "https://x/app.apk")
        assertTrue(SidecarChecksum.applyTo(m, Checksum.MD5 to md5))
        assertEquals(md5, m.expectedMd5)
        assertTrue(m.checksumFromSidecar)
    }

    @Test
    fun applyNeverOverwritesHandTyped() {
        val t = TaskRecord(url = "https://x/app.apk")
        t.expectedMd5 = md5 // hand-typed
        assertFalse(SidecarChecksum.applyTo(t, Checksum.SHA256 to sha))
        assertEquals("", t.expectedSha256)
        assertEquals(md5, t.expectedMd5)
        assertFalse(t.checksumFromSidecar)
    }

    // ---- persistence of the source mark ----

    @Test
    fun sidecarMarkRoundTripsAndOldJsonDefaultsToFalse() {
        val t = TaskRecord(url = "https://x/app.apk")
        t.expectedSha256 = sha
        t.checksumFromSidecar = true
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        assertTrue(back[0].checksumFromSidecar)
        // A pre-v5.8 record (field absent) parses as no mark.
        val old = """[{"id":"a","url":"https://x/app.apk","expectedSha256":"$sha"}]"""
        val parsed = TaskStore.deserialize(old)
        assertEquals(1, parsed.size)
        assertFalse(parsed[0].checksumFromSidecar)
    }

    // ---- display wording ----

    @Test
    fun stateLabelForSidecarNamesTheWebsiteSource() {
        val t = TaskRecord(url = "https://x/app.apk")
        t.expectedSha256 = sha
        t.checksumFromSidecar = true
        t.checksumState = Checksum.STATE_MATCHED
        assertTrue(Checksum.stateLabelFor(t).contains("已按网站给的校验值核对"))
        assertTrue(Checksum.stateLabelFor(t).contains("对上"))
        t.checksumState = Checksum.STATE_MISMATCHED
        assertTrue(Checksum.stateLabelFor(t).contains("对不上"))
        assertTrue(Checksum.stateLabelFor(t).contains("网站给的"))
        t.checksumState = Checksum.STATE_NONE
        assertTrue(Checksum.stateLabelFor(t).contains("来自网站"))
        // Hand-typed: wording is exactly stateLabel's, untouched.
        t.checksumFromSidecar = false
        t.checksumState = Checksum.STATE_MATCHED
        assertEquals(Checksum.stateLabel(Checksum.STATE_MATCHED), Checksum.stateLabelFor(t))
    }

    @Test
    fun boundsArePinned() {
        assertEquals(8 * 1024, SidecarChecksum.MAX_BODY_BYTES)
        assertEquals(5_000, SidecarChecksum.TIMEOUT_MS)
        assertEquals(listOf(".sha256", ".md5"), SidecarChecksum.SUFFIXES)
    }
}

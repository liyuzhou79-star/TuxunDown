package com.tuxundown.android

import com.tuxundown.android.core.TaskHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9: per-task request-header editing — text form, validation
 *  and the export-time Cookie strip. */
class TaskHeadersTest {

    @Test
    fun parseAndRoundTrip() {
        val text = "Cookie: session=abc\nReferer: https://a.b/page\nX-Test: 1"
        val parsed = TaskHeaders.parse(text)
        assertTrue(parsed.problems.isEmpty())
        assertEquals("session=abc", parsed.headers["Cookie"])
        assertEquals("https://a.b/page", parsed.headers["Referer"])
        val back = TaskHeaders.parse(TaskHeaders.toText(parsed.headers))
        assertEquals(parsed.headers, back.headers)
    }

    @Test
    fun badLinesAreReportedNotSilentlyReshaped() {
        // No colon / illegal name / a control character inside a
        // value: three problems, and only the clean line survives.
        val parsed = TaskHeaders.parse(
            "no colon here\nBad Name: x\nX-Ok: fine\nX-Tab: a\tb"
        )
        assertEquals(mapOf("X-Ok" to "fine"), parsed.headers)
        assertEquals(3, parsed.problems.size)
    }

    @Test
    fun lastDuplicateWinsCaseInsensitively() {
        val parsed = TaskHeaders.parse("cookie: one\nCookie: two")
        assertEquals(1, parsed.headers.size)
        assertEquals("two", parsed.headers["Cookie"])
    }

    @Test
    fun exportStripRemovesOnlyCookie() {
        val headers = mapOf(
            "Cookie" to "session=abc",
            "cookie2" to "not-a-cookie-header",
            "Referer" to "https://a.b/",
            "User-Agent" to "UA",
        )
        val stripped = TaskHeaders.strippedForExport(headers)
        assertFalse(stripped.keys.any { it.equals("Cookie", ignoreCase = true) })
        assertEquals("https://a.b/", stripped["Referer"])
        assertEquals("UA", stripped["User-Agent"])
        assertEquals("not-a-cookie-header", stripped["cookie2"])
        assertTrue(TaskHeaders.isSensitive("COOKIE"))
        assertFalse(TaskHeaders.isSensitive("Referer"))
    }
}

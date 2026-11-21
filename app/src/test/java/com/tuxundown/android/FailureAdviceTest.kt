package com.tuxundown.android

import com.tuxundown.android.core.Errors
import com.tuxundown.android.core.FailureAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailureAdviceTest {

    @Test
    fun forbiddenSuggestsTheBrowser() {
        val a = FailureAdvice.forError(Errors.httpMessage(403), hasSources = false)
        assertEquals(FailureAdvice.Action.OPEN_BROWSER, a?.action)
        val a401 = FailureAdvice.forError(Errors.httpMessage(401), hasSources = false)
        assertEquals(FailureAdvice.Action.OPEN_BROWSER, a401?.action)
    }

    @Test
    fun rateLimitedSuggestsWaiting() {
        val a = FailureAdvice.forError(Errors.httpMessage(429), hasSources = false)
        assertEquals(FailureAdvice.Action.RETRY, a?.action)
    }

    @Test
    fun storageFullPointsAtTheFilesScreen() {
        val a = FailureAdvice.forError(
            "手机存储空间不足，下载已停下。清出空间后点继续即可续传。", hasSources = false,
        )
        assertEquals(FailureAdvice.Action.OPEN_FILES, a?.action)
        val b = FailureAdvice.forError(
            "手机空间不够：这个文件有 1 GB，当前可用空间只有 200 MB", hasSources = false,
        )
        assertEquals(FailureAdvice.Action.OPEN_FILES, b?.action)
    }

    @Test
    fun mismatchDependsOnSources() {
        val err = "服务器上的文件在下载途中变了（校验标记对不上），已停下重试"
        assertEquals(
            FailureAdvice.Action.MANAGE_SOURCES,
            FailureAdvice.forError(err, hasSources = true)?.action,
        )
        assertEquals(
            FailureAdvice.Action.RETRY,
            FailureAdvice.forError(err, hasSources = false)?.action,
        )
    }

    @Test
    fun goneFileGetsNoButton() {
        val a = FailureAdvice.forError(Errors.httpMessage(404), hasSources = false)
        assertEquals(FailureAdvice.Action.NONE, a?.action)
    }

    @Test
    fun noRangeAndNetworkAndServerError() {
        assertEquals(
            FailureAdvice.Action.RETRY,
            FailureAdvice.forError("服务器不支持 Range", hasSources = false)?.action,
        )
        assertEquals(
            FailureAdvice.Action.RETRY,
            FailureAdvice.forError(
                "找不到这个服务器：检查一下链接对不对，或者换个网络再试",
                hasSources = false,
            )?.action,
        )
        assertEquals(
            FailureAdvice.Action.RETRY,
            FailureAdvice.forError(Errors.httpMessage(503), hasSources = false)?.action,
        )
        assertEquals(
            FailureAdvice.Action.RETRY,
            FailureAdvice.forError(Errors.httpMessage(416), hasSources = false)?.action,
        )
    }

    @Test
    fun unknownAndEmptyGetNoAdvice() {
        assertNull(FailureAdvice.forError("某种没见过的失败", hasSources = false))
        assertNull(FailureAdvice.forError("", hasSources = false))
        assertNull(FailureAdvice.forError("   ", hasSources = true))
    }
}

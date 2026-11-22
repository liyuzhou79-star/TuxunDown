package com.tuxundown.android

import com.tuxundown.android.core.Errors
import com.tuxundown.android.core.FailureAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun expiredSafGrantPointsAtReselectingTheFolder() {
        // Both obtainTarget wordings for a dead SAF grant.
        for (err in listOf(
            "自定义文件夹不可写（授权可能已失效，请在设置里重选）",
            "自定义文件夹打不开，请在设置里重选下载目录",
        )) {
            val a = FailureAdvice.forError(err, hasSources = false)
            assertEquals(FailureAdvice.Action.NONE, a?.action)
            assertTrue(a!!.text.contains("重新选一次"))
        }
    }

    @Test
    fun storageDeviceGoneSuggestsCheckingThenRetrying() {
        val a = FailureAdvice.forError(
            "存储设备好像出问题了（SD 卡可能被拔出，或者存储变成了只读），" +
                "下载已停下。确认存储设备正常后点继续即可续传。",
            hasSources = false,
        )
        assertEquals(FailureAdvice.Action.RETRY, a?.action)
    }

    @Test
    fun unknownAndEmptyGetNoAdvice() {
        assertNull(FailureAdvice.forError("某种没见过的失败", hasSources = false))
        assertNull(FailureAdvice.forError("", hasSources = false))
        assertNull(FailureAdvice.forError("   ", hasSources = true))
    }
}

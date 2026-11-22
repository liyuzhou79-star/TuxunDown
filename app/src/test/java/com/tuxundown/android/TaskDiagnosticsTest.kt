package com.tuxundown.android

import com.tuxundown.android.core.Checksum
import com.tuxundown.android.core.TaskDiagnostics
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.UserAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.8: the per-task diagnostic text and its redaction rules. */
class TaskDiagnosticsTest {

    @Test
    fun redactedUrlDropsUserinfoQueryAndFragment() {
        assertEquals(
            "https://example.com:8443/a/b.zip?（参数已隐藏）",
            TaskDiagnostics.redactedUrl(
                "https://user:secret@example.com:8443/a/b.zip?token=XYZ#frag"
            ),
        )
        // No suffix: nothing is marked, userinfo still goes.
        assertEquals(
            "ftp://files.example.com/pub/a.iso",
            TaskDiagnostics.redactedUrl("ftp://user:pw@files.example.com/pub/a.iso"),
        )
        // Plain link passes through untouched.
        assertEquals(
            "https://example.com/a.zip",
            TaskDiagnostics.redactedUrl("https://example.com/a.zip"),
        )
    }

    @Test
    fun uaLabelNamesPresetsAndNeverEchoesCustomStrings() {
        val winChrome = UserAgents.PRESETS[1].second
        assertEquals(
            "电脑 Chrome（Windows）",
            TaskDiagnostics.uaLabel(winChrome, null),
        )
        assertEquals("默认（安卓 Chrome）", TaskDiagnostics.uaLabel(null, null))
        assertEquals("自定义", TaskDiagnostics.uaLabel("MySecretUA/9.9", null))
        // A task-level UA wins over the setting, like the engine.
        assertEquals(
            "下载工具（curl）",
            TaskDiagnostics.uaLabel(UserAgents.PRESETS[5].second, winChrome),
        )
    }

    @Test
    fun buildCarriesFactsButNoSecretValues() {
        val task = TaskRecord(
            url = "https://cdn.example.com/dir/file.bin?sig=TOPSECRET3",
            totalSize = 104_857_600,
            threads = 16,
            status = TaskStatus.FAILED,
        )
        task.downloaded = 52_428_800
        task.error = "服务器拒绝访问（HTTP 403）"
        task.headers["Cookie"] = "session=TOPSECRET1"
        task.headers["Authorization"] = "Bearer TOPSECRET2"
        task.headers["User-Agent"] = "MySecretUA/9.9"
        task.expectedSha256 = "ab12"
        task.checksumState = Checksum.STATE_MISMATCHED

        val text = TaskDiagnostics.build(task, "3.8.0", null)
        // The facts a bug report needs…
        assertTrue(text.contains("版本：v3.8.0"))
        assertTrue(text.contains("状态：失败"))
        assertTrue(text.contains("https://cdn.example.com/dir/file.bin?（参数已隐藏）"))
        assertTrue(text.contains("16 条连接"))
        assertTrue(text.contains("服务器拒绝访问（HTTP 403）"))
        assertTrue(text.contains("SHA-256 · ✗ 对不上"))
        assertTrue(text.contains("UA）：自定义"))
        assertTrue(text.contains("Authorization"))
        assertTrue(text.contains("Cookie"))
        // …and none of the secret-shaped values, anywhere.
        assertFalse(text.contains("TOPSECRET1"))
        assertFalse(text.contains("TOPSECRET2"))
        assertFalse(text.contains("TOPSECRET3"))
        assertFalse(text.contains("MySecretUA"))
    }

    @Test
    fun buildHandlesUnknownSizeHlsAndNoHeaders() {
        val task = TaskRecord(
            url = "https://example.com/live/index.m3u8",
            totalSize = -1,
            status = TaskStatus.RUNNING,
        )
        task.isHls = true
        val text = TaskDiagnostics.build(task, "3.8.0", "")
        assertTrue(text.contains("大小：未知"))
        assertTrue(text.contains("流媒体分片并行下载（HLS）"))
        assertTrue(text.contains("请求头：无"))
        assertTrue(text.contains("未设置期望哈希"))
        // No error line for a task that has none.
        assertFalse(text.contains("错误："))
    }
}

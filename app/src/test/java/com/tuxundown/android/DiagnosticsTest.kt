package com.tuxundown.android

import com.tuxundown.android.core.Diagnostics
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.4: the diagnostics page's pure report building. */
class DiagnosticsTest {

    @Test
    fun fingerprint8TakesFirstEightHex() {
        assertEquals(
            "B73A2080",
            Diagnostics.fingerprint8(
                "b73a2080f93b8433f4ade626082b0902f0564e260da28ea070bc3433ca5b4513"
            ),
        )
        assertEquals("", Diagnostics.fingerprint8(""))
        assertEquals("AB12", Diagnostics.fingerprint8("ab:12"))
    }

    @Test
    fun taskCountsLineNamesEveryGroup() {
        val tasks = listOf(
            TaskRecord(url = "https://a/1").apply { status = TaskStatus.DONE },
            TaskRecord(url = "https://a/2").apply { status = TaskStatus.DONE },
            TaskRecord(url = "https://a/3").apply { status = TaskStatus.PAUSED },
            TaskRecord(url = "https://a/4").apply { status = TaskStatus.RUNNING },
        )
        val line = Diagnostics.taskCountsLine(tasks)
        assertTrue(line.startsWith("共 4 个"))
        assertTrue(line.contains("已完成 2"))
        assertTrue(line.contains("失败 0"))
    }

    @Test
    fun writeBenchLineReportsBothSpeeds() {
        // 8 MiB in 100 ms write + 100 ms sync = ~80 MB/s write,
        // ~40 MB/s durable.
        val line = Diagnostics.writeBenchLine(8L * 1024 * 1024, 100, 100)
        assertTrue(line.contains("写入约"))
        assertTrue(line.contains("落盘"))
        assertTrue(Diagnostics.writeBenchLine(0, 100, 100).contains("没跑成"))
    }

    @Test
    fun reportAssemblesLabeledLines() {
        val text = Diagnostics.buildReport(
            "2.4.0 (15)",
            listOf("网络" to "WiFi", "任务数" to "共 1 个"),
        )
        assertTrue(text.contains("版本：2.4.0 (15)"))
        assertTrue(text.contains("网络：WiFi"))
        assertTrue(text.contains("没有上传"))
    }
}

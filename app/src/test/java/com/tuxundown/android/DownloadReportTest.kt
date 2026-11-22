package com.tuxundown.android

import com.tuxundown.android.core.DownloadReport
import com.tuxundown.android.core.HostStats
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.4: the download report — every number from real data,
* empty sections omitted, never invented. */
class DownloadReportTest {

// 2026-11-22 10:00 local-ish (exact zone does not matter:
// both the code and these fixtures go through dayKey).
private val now = java.util.Calendar.getInstance().apply {
set(2026, java.util.Calendar.NOVEMBER, 22, 10, 0, 0)
}.timeInMillis

private fun done(
name: String, size: Long, finishedAt: Long, peak: Long = 0,
) = TaskRecord(
url = "https://a.example.com/$name",
fileName = name,
totalSize = size,
status = TaskStatus.DONE,
finishedAt = finishedAt,
).apply { peakBps = peak}

private fun failed() = TaskRecord(
url = "https://a.example.com/bad",
status = TaskStatus.FAILED,
finishedAt = now - 1000,
)

@Test
fun monthSectionCountsOnlyThisMonthsCompletions() {
val thisMonth = done("a.bin", 1000, now - 86_400_000L)
val lastMonth = done(
"b.bin", 2000,
java.util.Calendar.getInstance().apply {
set(2026, java.util.Calendar.OCTOBER, 31, 23, 0, 0)
}.timeInMillis,
)
val text = DownloadReport.build(
listOf(thisMonth, lastMonth),
DownloadReport.Counters(0, 0, 0),
emptyList(),
now,
)
assertTrue(text.contains("本月 · "))
assertTrue(text.contains("完成 1 个文件"))
// The all-records section still sees both.
assertTrue(text.contains("完成 2 个 · 失败 0 个 · 成功率 100%"))
}

@Test
fun successRateAndPeakAndLargest() {
val tasks = listOf(
done("small.bin", 100, now, peak = 500),
done("big.bin", 900, now, peak = 2000),
done("mid.bin", 300, now),
failed(),
)
val text = DownloadReport.build(
tasks, DownloadReport.Counters(0, 0, 0), emptyList(), now,
)
assertTrue(text.contains("完成 3 个 · 失败 1 个 · 成功率 75%"))
assertTrue(text.contains("最大文件：big.bin"))
}

@Test
fun emptyEverythingOmitsAllSections() {
val text = DownloadReport.build(
emptyList(), DownloadReport.Counters(0, 0, 0), emptyList(), now,
)
assertFalse(text.contains("本月 · "))
assertFalse(text.contains("全部记录"))
assertFalse(text.contains("本机累计统计"))
assertFalse(text.contains("下载最多的站点"))
assertTrue(text.contains("还没有可报告的数据"))
}

@Test
fun countersAndHostsAppearWhenPresent() {
val text = DownloadReport.build(
emptyList(),
DownloadReport.Counters(totalBytes = 5_000_000, doneCount = 7, avgBps = 0),
listOf(HostStats.Entry("a.example.com", 4_000_000, 3)),
now,
)
assertTrue(text.contains("本机累计统计"))
assertTrue(text.contains("完成任务：7 个"))
// avgBps is 0 -> its line is omitted, not shown as 0.
assertFalse(text.contains("平均速度"))
assertTrue(text.contains("1. a.example.com"))
// No records at all -> no records section despite counters.
assertFalse(text.contains("全部记录"))
}
}

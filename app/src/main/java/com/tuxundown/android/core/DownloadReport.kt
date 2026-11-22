package com.tuxundown.android.core

/**
* Download report (v3.4) — pure text building, unit-tested.
*
* The statistics screen's「生成报告」: this month's and
* all-time download numbers as one plain-text block the user
* can copy or share. Every number comes from data already on
* the device, computed at tap time:
*
* - 本月： from the task RECORDS — tasks whose completion
* stamp (finishedAt) falls in the current calendar month;
* - 成功率 / 最大文件 / 最快速度： also from the records
* that still exist (deleted records are gone and are NOT
* reconstructed — the report says so);
* - 累计下载 / 完成任务 / 平均速度： from the app's
* long-running counters (the same numbers the statistics
* screen's 总览 shows), which survive record cleanup.
*
* A section or line with no data behind it is OMITTED, never
* padded with invented numbers; when there is nothing at
* all, the report says exactly that.
*/
object DownloadReport {

/** The long-running counters from DownloadCenter's prefs. */
data class Counters(
val totalBytes: Long,
val doneCount: Int,
val avgBps: Long,
)

/** Effective downloaded bytes of a completed task — the
* same convention DownloadCenter.recordCompletion counts
* by (declared total when known, else what arrived). */
fun bytesOf(task: TaskRecord): Long =
if (task.totalSize > 0) task.totalSize else task.downloaded

private fun displayName(task: TaskRecord): String =
task.savedName.ifBlank { task.fileName}.ifBlank { task.url}

fun build(
tasks: List<TaskRecord>,
counters: Counters,
hostTop: List<HostStats.Entry>,
nowMs: Long,
): String {
val out = StringBuilder()
out.append("TuxunDown 下载报告\n")
val stamp = java.text.SimpleDateFormat(
"yyyy-MM-dd HH:mm", java.util.Locale.US,
).format(java.util.Date(nowMs))
out.append("生成时间：$stamp\n")

var anySection = false

// ---- this month, from completion stamps ----
val monthPrefix = StatsMath.dayKey(nowMs).substring(0, 7)
val monthDone = tasks.filter {
it.status == TaskStatus.DONE && it.finishedAt > 0 &&
StatsMath.dayKey(it.finishedAt).startsWith(monthPrefix)
}
if (monthDone.isNotEmpty()) {
anySection = true
val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs}
out.append(
"\n本月 · ${cal.get(java.util.Calendar.YEAR)} 年 " +
"${cal.get(java.util.Calendar.MONTH) + 1} 月\n",
)
out.append(
"完成 ${monthDone.size} 个文件，共 " +
"${Format.size(monthDone.sumOf { bytesOf(it)})}\n",
)
val biggest = monthDone.maxByOrNull { bytesOf(it)}
if (biggest!= null && bytesOf(biggest) > 0) {
out.append("最大文件：${displayName(biggest)}（${Format.size(bytesOf(biggest))}）\n")
}
}

// ---- all records still on the device ----
val done = tasks.filter { it.status == TaskStatus.DONE}
val failed = tasks.filter { it.status == TaskStatus.FAILED}
if (done.isNotEmpty() || failed.isNotEmpty()) {
anySection = true
out.append("\n全部记录\n")
val total = done.size + failed.size
val rate = Math.round(done.size * 100.0 / total).toInt()
out.append("完成 ${done.size} 个 · 失败 ${failed.size} 个 · 成功率 $rate%\n")
val peak = tasks.maxOfOrNull { it.peakBps}?: 0
if (peak > 0) {
out.append("单任务最快速度：${Format.speed(peak)}\n")
}
val biggest = done.maxByOrNull { bytesOf(it)}
if (biggest!= null && bytesOf(biggest) > 0) {
out.append("最大文件：${displayName(biggest)}（${Format.size(bytesOf(biggest))}）\n")
}
}

// ---- the long-running counters ----
if (counters.totalBytes > 0 || counters.doneCount > 0 || counters.avgBps > 0) {
anySection = true
out.append("\n本机累计统计\n")
if (counters.totalBytes > 0) {
out.append("累计下载：${Format.size(counters.totalBytes)}\n")
}
if (counters.doneCount > 0) {
out.append("完成任务：${counters.doneCount} 个\n")
}
if (counters.avgBps > 0) {
out.append("平均速度：${Format.speed(counters.avgBps)}\n")
}
}

// ---- top sites ----
if (hostTop.isNotEmpty()) {
anySection = true
out.append("\n下载最多的站点\n")
hostTop.forEachIndexed { i, h ->
out.append("${i + 1}. ${h.host} · ${Format.size(h.bytes)} · ${h.count} 个文件\n")
}
}

if (!anySection) {
out.append("\n还没有可报告的数据：先下完一个文件，再回来生成报告。\n")
}
out.append(
"\n数字来源：累计统计是 App 在本机的总账，删任务记录不影响它；" +
"成功率、最大文件、最快速度按现在还留着的任务记录现算，删掉的记录不算在内；" +
"本月按完成时间算。报告只在本机生成，没有上传任何数据。",
)
return out.toString()
}
}

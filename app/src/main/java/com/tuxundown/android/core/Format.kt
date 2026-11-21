package com.tuxundown.android.core

/**
 * Windows-transfer-style formatting, shared by the UI and the
 * foreground-service notification so both "speak" the same way:
 * sizes in B/KB/MB/GB, speeds as "x.x MB/秒", remaining time as
 * "大约 X 分钟". Pure functions — unit-tested.
 */
object Format {

    fun size(bytes: Long): String = when {
        bytes < 0 -> "未知大小"
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Compact size for chart axis labels (no decimals beyond need). */
    fun sizeShort(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024 * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun speed(bps: Long): String = when {
        bps < 0 -> "—"
        bps >= 1024L * 1024 * 1024 -> "%.2f GB/秒".format(bps / 1024.0 / 1024.0 / 1024.0)
        bps >= 1024 * 1024 -> "%.1f MB/秒".format(bps / 1024.0 / 1024.0)
        bps >= 1024 -> "%.1f KB/秒".format(bps / 1024.0)
        else -> "$bps B/秒"
    }

    /** Windows-style remaining time: "大约 3 分钟" / "大约 20 秒" / "大约 1 小时 5 分钟". */
    fun eta(seconds: Long): String = when {
        seconds < 0 -> ""
        seconds < 60 -> "大约 $seconds 秒"
        seconds < 3600 -> {
            val m = (seconds + 59) / 60
            "大约 $m 分钟"
        }
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600 + 59) / 60
            if (m >= 60) "大约 ${h + 1} 小时"
            else if (m == 0L) "大约 $h 小时"
            else "大约 $h 小时 $m 分钟"
        }
    }

    fun percent(progress: Float): Int =
        (progress.coerceIn(0f, 1f) * 100).toInt()

    /** "2026-11-22 14:30" label for a finished file (v1.9). */
    fun dateTime(epochMs: Long): String {
        if (epochMs <= 0) return ""
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%04d-%02d-%02d %02d:%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    /** "14:30" wall-clock label for a scheduled start (v1.4). */
    fun clockTime(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    /** Elapsed transfer time for the stats card: "45 秒" / "3 分 12 秒" / "1 小时 2 分". */
    fun duration(ms: Long): String {
        if (ms < 0) return "—"
        val s = ms / 1000
        return when {
            s < 60 -> "$s 秒"
            s < 3600 -> {
                val m = s / 60
                val r = s % 60
                if (r == 0L) "$m 分" else "$m 分 $r 秒"
            }
            else -> {
                val h = s / 3600
                val m = (s % 3600) / 60
                if (m == 0L) "$h 小时" else "$h 小时 $m 分"
            }
        }
    }

    /**
     * Nice chart ceiling for a speed graph: the smallest
     * power-of-two multiple of 1 KB/s that is >= maxBps * 1.1,
     * so the Y axis gets a clean labelled top (4 MB/秒, 8 MB/秒…)
     * like Windows graphs, and the label always formats exactly.
     */
    fun chartCeiling(maxBps: Long): Long {
        val target = maxOf(1024.0, maxBps * 1.1)
        var step = 1024L
        while (step < target) step *= 2
        return step
    }
}

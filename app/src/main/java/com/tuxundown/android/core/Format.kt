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

    /**
     * Nice chart ceiling for a speed graph: the smallest
     * 1/2/5 × 10^n value that is >= maxBps * 1.1, at least 1 KB/s,
     * so the Y axis gets a clean labelled top like Windows graphs.
     */
    fun chartCeiling(maxBps: Long): Long {
        val target = maxOf(1024.0, maxBps * 1.1)
        var step = 1024.0
        while (true) {
            for (m in doubleArrayOf(1.0, 2.0, 5.0, 10.0)) {
                if (step * m >= target) return (step * m).toLong()
            }
            step *= 10
        }
    }
}

package com.tuxundown.android.core

/**
 * Audit round 47 (v6.2 step A): WCAG contrast arithmetic and the
 * health-check status-dot palette, as pure logic so the ratios
 * are unit-pinned instead of eyeballed.
 *
 * The v2.9 / v3.7 caliber: a colour used on a surface must clear
 * WCAG AA on THAT theme's surface — light colours are chosen
 * against the light surfaces, dark colours against the dark
 * surfaces, never one hardcoded value for both. The status dots
 * in the one-tap health check were the exception that proved the
 * rule: one fixed trio for both themes, and the BAD red
 * (#B3261E) only reaches 2.37:1 on the dark surface #242424 —
 * below even the 3:1 graphics bar. The verdict never rides on
 * the dot alone (the level word 正常 / 注意 / 异常 sits next to
 * it), but a dot that nearly vanishes in dark mode is still a
 * bug. The palette below is theme-split, and every entry clears
 * 4.5:1 (the body-text bar, stricter than the 3:1 graphics bar)
 * on both of its theme's backgrounds (background + surface).
 */
object A11yContrast {

    /** WCAG relative luminance of an opaque ARGB colour. */
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92
            else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) +
            0.0722 * channel(0)
    }

    /** WCAG contrast ratio (1.0–21.0) between two opaque ARGB colours. */
    fun ratio(foregroundArgb: Long, backgroundArgb: Long): Double {
        val a = luminance(foregroundArgb)
        val b = luminance(backgroundArgb)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    // The app's theme surfaces (MainActivity's color schemes).
    const val LIGHT_BACKGROUND: Long = 0xFFF7F7F7
    const val LIGHT_SURFACE: Long = 0xFFFFFFFF
    const val DARK_BACKGROUND: Long = 0xFF1B1B1B
    const val DARK_SURFACE: Long = 0xFF242424
    const val AMOLED_SURFACE: Long = 0xFF000000

    /**
     * Health-check status-dot ARGB per level and theme. Light
     * entries keep the pre-47 hues except WARN, which darkens
     * from #B26A00 (4.24:1 on white — under the text bar) to
     * #935F00 (5.42:1); dark entries are the brightened variants
     * the same hues need on #242424 / black.
     */
    fun healthDotArgb(level: HealthCheck.Level, dark: Boolean): Long =
        when (level) {
            HealthCheck.Level.OK ->
                if (dark) 0xFF66BB6A else 0xFF2E7D32
            HealthCheck.Level.WARN ->
                if (dark) 0xFFFFB74D else 0xFF935F00
            HealthCheck.Level.BAD ->
                if (dark) 0xFFE57373 else 0xFFB3261E
        }

    /**
     * Audit round 70 (v8.5 step A): the danger-red TEXT colour
     * (the detail card's 删除文件 button and its confirmation
     * dialog), theme-split like the status greens and the
     * health dots above. The fixed WinPalette.Red #E81123 the
     * button used to take only clears the text bar on the
     * light SURFACE (4.63:1 on #FFFFFF) — it is 4.32:1 on the
     * light background #F7F7F7 and far under the bar on both
     * dark surfaces (3.35:1 on #242424, 3.72:1 on #1B1B1B;
     * 4.54:1 on AMOLED black). So the light entry darkens a
     * touch — the WARN-dot move above — to #DD0F21 (5.04:1 on
     * #FFFFFF, 4.71:1 on #F7F7F7) and the dark entry brightens
     * to #FF5252 (4.86:1 on #242424, 5.40:1 on #1B1B1B, 6.58:1
     * on black). WinPalette.Red itself stays untouched: its
     * other uses are graphics and large/bold text, where the
     * bar is 3:1 and it still clears it.
     */
    fun dangerTextArgb(dark: Boolean): Long =
        if (dark) 0xFFFF5252 else 0xFFDD0F21

    /** The sniff browser's floating-button green (SniffActivity). */
    const val SNIFF_FAB_GREEN: Long = 0xFF06B025
    const val WHITE: Long = 0xFFFFFFFF
    const val BLACK: Long = 0xFF000000
}

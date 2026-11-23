package com.tuxundown.android.core

/**
 * Canvas chart label sizing (v6.5, audit round 50, item A-F2).
 *
 * The hand-drawn charts (WeekChart / SpeedChart) paint their
 * axis labels with a raw android.graphics.Paint, whose textSize
 * is in PIXELS — so the Compose side has to do the unit math
 * itself. The old code used `10.dp.toPx()`: dp follows the
 * screen density only, so a user who turned the system font
 * size up (a common accessibility setting, and exactly the
 * users small chart labels hurt most) got every other label in
 * the app bigger and the chart labels stuck at the same size.
 *
 * Labels are TEXT, so they take the sp caliber: pixels =
 * sp × density × fontScale. At fontScale 1.0 that is exactly
 * the old dp result — the default look does not change by a
 * single pixel — and with a larger system font setting the
 * labels grow in proportion, like every other text.
 *
 * Pure — unit-tested (ChartTextTest).
 */
object ChartText {

    /** Chart label size the charts have always used, in sp. */
    const val LABEL_SP = 10f

    /**
     * Pixel text size for a label of [sp] sp on a display with
     * the given [density] and system [fontScale].
     */
    fun labelPx(sp: Float, density: Float, fontScale: Float): Float =
        sp * density * fontScale

    /** The standard chart label, in pixels (see [LABEL_SP]). */
    fun labelPx(density: Float, fontScale: Float): Float =
        labelPx(LABEL_SP, density, fontScale)
}

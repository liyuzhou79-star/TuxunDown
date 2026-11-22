package com.tuxundown.android.core

/**
 * Three-tier task priority (v3.1), stored in the record's
 * existing `priority` Int so no migration is needed:
 *
 *  - [HIGH] = 1 — exactly what 插队 has written since v1.6,
 *    so every legacy "jumped" task IS a high-priority task
 *    (the mapping the upgrade promises: 老数据的插队标记 = 高）.
 *  - [NORMAL] = 0 — the standing default since v1.6.
 *  - [LOW] = -1 — new in v3.1.
 *
 * Two consumers, one meaning: the queue drain starts higher
 * tiers first (see QueueOrder.nextEligible), and the shared
 * speed budget splits 2 : 1 : 0.5 across 高 / 中 / 低 (see
 * BandwidthBudget.weightOf). Pure — unit-tested.
 */
object TaskPriority {
    const val LOW = -1
    const val NORMAL = 0
    const val HIGH = 1

    /** Anything outside the three tiers reads as NORMAL. */
    fun clamp(level: Int): Int = when (level) {
        LOW -> LOW
        HIGH -> HIGH
        else -> NORMAL
    }

    fun label(level: Int): String = when (clamp(level)) {
        HIGH -> "高"
        LOW -> "低"
        else -> "中"
    }
}

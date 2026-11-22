package com.tuxundown.android.core

/**
 * v3.7: which OPTIONAL rows the expanded task card shows.
 *
 * The card's detail area grew organically — average speed,
 * retry count, the chunk row + map — while two useful facts
 * (when the task was created, which site it came from) were
 * never shown at all. All five are now user switches in
 * 设置 → 任务卡片显示. Defaults reproduce the pre-3.7 card
 * EXACTLY: the three rows that existed default ON, the two
 * new ones default OFF, so an upgrading user sees no change
 * until they go looking for the switches.
 *
 * This object is the field catalogue (key / label / default)
 * the settings screen renders and the tests pin; the values
 * themselves live in DownloadCenter prefs (cardShow*) and
 * travel in the settings backup snapshot like every other
 * switch.
 */
object CardFields {

    const val AVG_SPEED = "avgSpeed"
    const val RETRY_COUNT = "retryCount"
    const val CREATED_AT = "createdAt"
    const val HOST = "host"
    const val CHUNKS = "chunks"
    /** v4.6: the whole 「服务器画像」 section is ONE switch —
     *  its rows are already data-gated individually (a row with
     *  no recorded data is absent, see ServerProfile), so the
     *  section defaults ON: a task with no profile data shows
     *  nothing new, and the switch exists for users who find
     *  the section noise. */
    const val SERVER_PROFILE = "serverProfile"

    data class Field(val key: String, val label: String, val hint: String, val default: Boolean)

    val FIELDS: List<Field> = listOf(
        Field(AVG_SPEED, "平均速度", "展开详情时显示这个任务的平均下载速度", true),
        Field(RETRY_COUNT, "重试次数", "显示分片级重试发生的次数（没重试过时不显示这一行）", true),
        Field(CREATED_AT, "创建时间", "显示这个任务是什么时候建的", false),
        Field(HOST, "来源站点", "显示链接所在的站点（域名）", false),
        Field(CHUNKS, "分片进度", "显示「分片」行和分片图（流媒体任务是分段行和合并图）", true),
        Field(SERVER_PROFILE, "服务器画像", "展开详情时显示服务器画像一段（断点续传、声明大小、峰值连接、各源贡献等；没数据的行不显示）", true),
    )

    fun field(key: String): Field? = FIELDS.firstOrNull { it.key == key }

    /** The default for [key]; unknown keys default to hidden. */
    fun defaultOf(key: String): Boolean = field(key)?.default ?: false
}

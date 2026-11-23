package com.tuxundown.android.core

/**
 * v6.2 step B: which items the task card's long-press quick
 * menu shows, as pure logic so the state -> items table is
 * unit-pinned instead of eyeballed.
 *
 * The menu aggregates ONLY actions the card already has, and
 * every item's visibility copies the caliber of the card's
 * own controls for the same action (MainActivity TaskCard):
 *  - PAUSE: the header pause button shows for RUNNING, and
 *    the v1.8 swipe-left gesture offers 暂停 for RUNNING and
 *    QUEUED (both call the same onPause) — so PAUSE shows
 *    for RUNNING / QUEUED.
 *  - RESUME: the header resume button shows for PAUSED /
 *    FAILED (the FAILED expanded section's retry button is
 *    the same onResume) — so RESUME shows for PAUSED /
 *    FAILED.
 *  - JUMP_QUEUE: the 插队到最前 button exists only in the
 *    QUEUED expanded section — QUEUED only.
 *  - PRIORITY_*: the priority ChipRow is gated on
 *    status != DONE — the three tiers show in every other
 *    state.
 *  - EDIT_NOTE / COPY_LINK / COPY_DIAGNOSTICS / DELETE: the
 *    note button, the diagnostics-copy button and the header
 *    delete (X) button are all unconditional on the card, so
 *    these show in every state. (Copy-link has no older card
 *    button; it rides the same clipboard idiom as the
 *    diagnostics-copy button in the same function.)
 *  - RE_DOWNLOAD (v8.5): 「再下一次」 — builds a BRAND-NEW
 *    task from this finished one (see RedownloadPlan /
 *    DownloadCenter.redownload). Shows ONLY for DONE with a
 *    non-blank url: an adopted / orphan record carries
 *    url = "" and has nothing to download again, so the
 *    entry must hide there. This one rule ([canRedownload])
 *    decides BOTH the menu item and the detail action
 *    flow's button — the two surfaces never re-derive it.
 *  - MULTI_SELECT: long-press used to BE the multi-select
 *    entry (it is still the toggle while selecting). The
 *    menu now owns the long-press outside selecting mode,
 *    so the entry survives as a menu item calling the same
 *    onToggleSelect; while selecting it is absent (the menu
 *    does not open then, and the toggle is the long-press
 *    itself).
 * No item may appear in a state where tapping it would turn
 * out unusable — that is the whole point of pinning this
 * table here instead of scattering `if`s through the menu
 * composable.
 */
object CardQuickMenu {

    enum class Item {
        PAUSE,
        RESUME,
        JUMP_QUEUE,
        PRIORITY_HIGH,
        PRIORITY_NORMAL,
        PRIORITY_LOW,
        EDIT_NOTE,
        COPY_LINK,
        COPY_DIAGNOSTICS,
        DELETE,
        MULTI_SELECT,
        RE_DOWNLOAD,
    }

    /**
     * v8.5: the ONE 「再下一次」 visibility rule — DONE and a
     * non-blank url. Adopted / orphan records (url = "") can
     * never re-download. The long-press menu (via [itemsFor])
     * and the detail action flow both ask here; neither
     * surface writes its own copy of the condition.
     */
    fun canRedownload(status: TaskStatus, url: String): Boolean =
        status == TaskStatus.DONE && url.isNotBlank()

    /** Record form of [canRedownload]. */
    fun canRedownload(task: TaskRecord): Boolean =
        canRedownload(task.status, task.url)

    /** Menu items for [status], in display order. [url] only
     *  feeds the RE_DOWNLOAD rule (see [canRedownload]). */
    fun itemsFor(
        status: TaskStatus,
        selecting: Boolean,
        url: String = "",
    ): List<Item> {
        val items = mutableListOf<Item>()
        when (status) {
            TaskStatus.RUNNING, TaskStatus.QUEUED -> items += Item.PAUSE
            TaskStatus.PAUSED, TaskStatus.FAILED -> items += Item.RESUME
            TaskStatus.DONE -> Unit
        }
        if (canRedownload(status, url)) items += Item.RE_DOWNLOAD
        if (status == TaskStatus.QUEUED) items += Item.JUMP_QUEUE
        if (status != TaskStatus.DONE) {
            items += Item.PRIORITY_HIGH
            items += Item.PRIORITY_NORMAL
            items += Item.PRIORITY_LOW
        }
        items += Item.EDIT_NOTE
        items += Item.COPY_LINK
        items += Item.COPY_DIAGNOSTICS
        items += Item.DELETE
        if (!selecting) items += Item.MULTI_SELECT
        return items
    }
}

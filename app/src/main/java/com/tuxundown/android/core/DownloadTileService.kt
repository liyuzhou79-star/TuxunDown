package com.tuxundown.android.core

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tuxundown.android.R

/**
 * Quick-settings tile (v2.7): shows how many downloads are in
 * flight and toggles between 全部暂停 and 全部继续 on tap.
 *
 * The decision comes from the same pure [BulkControl] the home
 * summary card's button uses, and the work goes through the
 * same DownloadCenter calls as the in-app buttons — the tile
 * is a remote control for the one queue, not a second queue.
 *
 * Everything here is wrapped: the tile can be bound while the
 * app process is cold (or in CrashGuard safe mode), and a tile
 * that throws would vanish from the user's panel. State is
 * re-read on every listen, so the subtitle never goes stale
 * by more than the panel's own refresh.
 */
class DownloadTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            DownloadCenter.init(applicationContext)
            when (BulkControl.action(DownloadCenter.store.tasks)) {
                BulkControl.Action.PAUSE_ALL -> DownloadCenter.pauseAll()
                BulkControl.Action.RESUME_ALL -> DownloadCenter.resumeAll()
                BulkControl.Action.NONE -> Unit
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        runCatching {
            DownloadCenter.init(applicationContext)
            val tasks = DownloadCenter.store.tasks
            tile.label = "TuxunDown"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_download)
            when (BulkControl.action(tasks)) {
                BulkControl.Action.PAUSE_ALL -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.subtitle =
                        "进行中 ${BulkControl.activeCount(tasks)} 个 · 点按全部暂停"
                }
                BulkControl.Action.RESUME_ALL -> {
                    tile.state = Tile.STATE_INACTIVE
                    // v5.3 (audit round 38): wording from
                    // BulkControl.resumeSubtitle — PAUSED and
                    // FAILED halves named separately, never one
                    // 「已暂停」 count covering both.
                    tile.subtitle = BulkControl.resumeSubtitle(tasks)
                }
                BulkControl.Action.NONE -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.subtitle = "没有进行中的任务"
                }
            }
            tile.updateTile()
        }
    }
}

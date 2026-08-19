package com.carlmanning.carlsbrain.ui.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService
import com.carlmanning.carlsbrain.data.local.worker.AmbientState

/**
 * Quick Settings tile that turns the rolling ambient buffer into a meeting recording, and
 * stops it again.
 *
 * The whole point of the buffer is that Carl realises after the fact, so the trigger has to be
 * reachable without unlocking, opening the app and finding a screen. Unlike
 * [CaptureTileService] this launches no activity at all — it messages the service directly, so
 * it works from a locked screen with nothing to dismiss.
 *
 * It is a genuine toggle rather than a one-shot: ACTIVE means recording, so the same tile that
 * started it is the one that stops it.
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        AmbientBufferService.send(this, AmbientBufferService.ACTION_TOGGLE)
        // The service state changes asynchronously, so paint the expected state immediately
        // rather than leaving the tile looking unresponsive; onStartListening corrects it.
        val tile = qsTile ?: return
        tile.state =
            if (tile.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = AmbientBufferService.state.value
        tile.state = if (state is AmbientState.Recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (state is AmbientState.Recording) "Recording" else "Record"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        tile.updateTile()
    }
}

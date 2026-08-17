package com.carlmanning.carlsbrain.ui.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.carlmanning.carlsbrain.MainActivity

/**
 * Quick Settings tile that starts voice-first Quick Capture in one tap, including from
 * the lock screen.
 *
 * This is the replacement for the (dead) "Hey Brain" wake word: user-initiated only,
 * nothing runs in the background, zero battery cost.
 *
 * The tile reuses the existing [MainActivity.ACTION_OPEN_CAPTURE_VOICE] deep link, which
 * routes through AppViewModel.requestCapture(type = "TODO", startVoice = true).
 */
class CaptureTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        markActive()
    }

    override fun onStartListening() {
        super.onStartListening()
        markActive()
    }

    /**
     * A tile left in STATE_INACTIVE renders greyed out. This tile is a one-shot action,
     * not a toggle, so it is always ACTIVE.
     */
    private fun markActive() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(com.carlmanning.carlsbrain.R.string.app_name)
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        // On a secure lock screen the shade can't launch an activity directly — unlockAndRun
        // dismisses the keyguard first and then runs. (The app's own biometric gate still
        // applies on top of that; see the note on pending capture below.)
        if (isLocked) {
            unlockAndRun { launchVoiceCapture() }
        } else {
            launchVoiceCapture()
        }
    }

    private fun launchVoiceCapture() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CAPTURE_VOICE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ removed the Intent overload — it throws UnsupportedOperationException.
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_CODE = 4101
    }
}

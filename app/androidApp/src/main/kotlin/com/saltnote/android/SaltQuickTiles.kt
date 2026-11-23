package com.saltnote.android

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Android Quick Settings Tile launching SaltNote directly into 1-tap Ogg/Opus voice capture.
 */
class SaltQuickVoiceTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        launchMainForAction(SaltClientRegistry.ACTION_CAPTURE_VOICE, requestCode = 201)
    }
}

/**
 * Android Quick Settings Tile launching SaltNote directly into CameraX board/journal capture.
 */
class SaltQuickCameraTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        launchMainForAction(SaltClientRegistry.ACTION_CAPTURE_CAMERA, requestCode = 202)
    }
}

private fun TileService.launchMainForAction(action: String, requestCode: Int) {
    val intent = SaltClientRegistry.buildLaunchMainIntent(this, action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pending = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(intent)
    }
}

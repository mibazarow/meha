package com.andharness.ru.inbound

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.andharness.ru.R

/**
 * Quick Settings tile «ANDharness» (update.md idea #10). Tapping it opens the app on a brand-new
 * chat; long-pressing it opens the app itself — the platform's default long-press (the App Info
 * screen) is replaced by the `ACTION_QS_TILE_PREFERENCES` intent-filter declared on `MainActivity`,
 * which is the documented way to override it:
 * https://developer.android.com/develop/ui/views/quicksettings-tiles#long-tap
 *
 * `startActivityAndCollapse(Intent)` throws on API 34+, so the PendingIntent overload documented for
 * `Build.VERSION_CODES.UPSIDE_DOWN_CAKE` and above is used there:
 * https://developer.android.com/reference/android/service/quicksettings/TileService#startActivityAndCollapse(android.app.PendingIntent)
 */
class QuickChatTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Non-toggleable tile: taps run onClick() and the tile never stays "active".
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            icon = Icon.createWithResource(this@QuickChatTileService, R.drawable.ic_tile_chat)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = InboundIntents.newChat(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                REQUEST_NEW_CHAT,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_NEW_CHAT = 1001
    }
}

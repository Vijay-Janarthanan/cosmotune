package com.cosmotune.music.widget

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile (from upstream 1.10, GPL-3.0): plays or pauses whatever Cosmotune last had queued, streamed or on the
 * phone, without opening the app. A tap is the widget's own play button ([MediaWidgetActions.ACTION_TOGGLE]), so it brings the
 * restored queue up the way the widget does. The tile shows the widget's snapshot (the song's title under the app's name, lit while
 * it plays) and follows it while the panel is open: the service saves a new snapshot on every play, pause and song change.
 */
class PlaybackTile : TileService() {
    private val redraw = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> draw() }

    override fun onStartListening() {
        MediaWidgetSnapshot.prefs(this).registerOnSharedPreferenceChangeListener(redraw)
        draw()
    }

    override fun onStopListening() {
        MediaWidgetSnapshot.prefs(this).unregisterOnSharedPreferenceChangeListener(redraw)
    }

    override fun onClick() {
        sendBroadcast(Intent(this, MediaWidgetActions::class.java).setAction(MediaWidgetActions.ACTION_TOGGLE))
    }

    private fun draw() {
        val tile = qsTile ?: return
        val snapshot = MediaWidgetSnapshot.load(this)
        tile.state = if (snapshot.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = snapshot.title.takeIf { snapshot.hasTrack }
        tile.updateTile()
    }
}

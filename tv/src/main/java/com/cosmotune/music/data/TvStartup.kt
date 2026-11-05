/*
 * Source: FORK.md companion source record TV-0005
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.data

import android.content.Context
import androidx.startup.Initializer
import com.cosmotune.music.data.settings.TvSettings
import com.cosmotune.music.ui.player.CanvasPlaybackLimits
import com.cosmotune.music.ui.tv.DeviceType

/**
 * Runs once at process start, before any activity or the playback service, so
 * the TV-only hooks into the shared code are in place whichever of them gets
 * there first. Registered in the TV manifest.
 */
class TvStartup : Initializer<Unit> {
    override fun create(context: Context) {
        TvSettings.init(context)
        TvUsbMusic.install()
        com.cosmotune.music.ui.tv.audio.TvSpatialAudioEngine.install()
        // Prefer <=24 fps renditions on TV; this is a selection preference, not a decoder cap.
        if (DeviceType.isTv(context)) CanvasPlaybackLimits.maxFrameRate = 24
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

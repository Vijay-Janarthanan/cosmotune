package com.cosmotune.video.player.android

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * The service that keeps Video's player alive in the background and lets system controllers and headsets reach it (a
 * foreground service of type `mediaPlayback`, declared in this module's manifest with the Media3 session action).
 *
 * Stub until the Android engine (Y-11), which fills it: no session yet, so a controller that binds gets nothing.
 */
class VideoPlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
}

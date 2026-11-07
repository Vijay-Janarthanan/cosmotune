package com.cosmotune.music.desktop

import androidx.compose.ui.input.key.KeyEvent
import com.cosmotune.design.AppMode
import com.cosmotune.ui.components.SpacePlayPause

/**
 * Space plays and pauses in the main window, as in every player: Music's song in Music, the video in Video and Watch (which
 * both play on Video's own engine). Given the window's unclaimed key events (its `onKeyEvent`), so a focused text field keeps
 * its spaces and a focused button its press; see [SpacePlayPause] for how the two are told apart.
 */
internal object DesktopSpaceKey {
    private val space = SpacePlayPause()

    /**
     * Music's play / pause as its own button does it (a room hears it as this listener's), set while the app is composed;
     * true when there was a song to play or pause.
     */
    @Volatile var music: (() -> Boolean)? = null

    /** One key event of the main window that nothing focused claimed, in [mode]. True when Space was used. */
    fun onKeyEvent(event: KeyEvent, mode: AppMode): Boolean = space.onKeyEvent(event) { toggle(mode) }

    /** Plays or pauses what [mode] plays; false when it has nothing loaded, and the key is then left alone. */
    internal fun toggle(mode: AppMode): Boolean = when (mode) {
        AppMode.MUSIC -> music?.invoke() == true
        AppMode.VIDEO, AppMode.WATCH -> toggleVideo()
        else -> false
    }

    /** The video on Video's engine, Video's or Watch's, through the hook Video sets when its engine is built. */
    private fun toggleVideo(): Boolean = com.cosmotune.ui.components.ModePlayPause.video?.invoke() == true
}

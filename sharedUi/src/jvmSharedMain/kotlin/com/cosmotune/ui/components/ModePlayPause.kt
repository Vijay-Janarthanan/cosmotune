package com.cosmotune.ui.components

/**
 * Play / pause of a mode's own player, for a key the host window handles (the desktop's Space): Video's player, which Watch
 * also plays on, sets [video] when it is built. Music's window only calls it, so Music never names Video, and a build
 * without Video leaves it null.
 */
object ModePlayPause {
    /** Plays or pauses Video's player; true when it had a video loaded to play or pause. */
    @Volatile var video: (() -> Boolean)? = null
}

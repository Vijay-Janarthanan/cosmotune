package com.cosmotune.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect

/**
 * Picture-in-picture, as far as the screens need to know it. On Android 12 and later the window enters it by itself when the
 * app is left (auto-enter), on Android 11 and earlier from the activity's user-leave hint; the desktop has none.
 */
@Stable
interface PictureInPictureState {
    /** Whether this device and this window can enter picture-in-picture at all. */
    val supported: Boolean

    /** Whether the window is in picture-in-picture now. */
    val active: Boolean

    /**
     * Tells the window whether leaving the app should enter picture-in-picture now ([enabled]: a video is playing, the player is
     * on screen and the setting is on), and with what shape: the picture's [aspectWidth] to [aspectHeight] and, when known, the
     * [sourceRect] of the picture in the window, from which the system animates.
     */
    fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?)
}

/** The picture-in-picture state of the window this is called in. The Android actual follows the activity (Y-11); the desktop's is never supported. */
@Composable
expect fun rememberPictureInPicture(): PictureInPictureState

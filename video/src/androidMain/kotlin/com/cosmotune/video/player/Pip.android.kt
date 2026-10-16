package com.cosmotune.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect

/** Stub until the Android engine (Y-11): picture-in-picture is reported as not supported, and `update` does nothing. */
@Composable
actual fun rememberPictureInPicture(): PictureInPictureState = remember { UnsupportedPictureInPicture }

private object UnsupportedPictureInPicture : PictureInPictureState {
    override val supported: Boolean get() = false
    override val active: Boolean get() = false
    override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) {}
}

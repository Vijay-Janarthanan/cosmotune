package com.cosmotune.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect

/** The desktop has no picture-in-picture, for good: never supported, never active, and `update` does nothing. */
@Composable
actual fun rememberPictureInPicture(): PictureInPictureState = remember { NoPictureInPicture }

private object NoPictureInPicture : PictureInPictureState {
    override val supported: Boolean get() = false
    override val active: Boolean get() = false
    override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) {}
}

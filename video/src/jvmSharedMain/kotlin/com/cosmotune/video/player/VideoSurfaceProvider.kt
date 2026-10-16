package com.cosmotune.video.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws a platform player's picture. The engines implement it (the Android one with a Media3 surface, Y-11; the desktop one
 * with the frames of its software renderer, Y-12), so the watch page and the Shorts pager ask for "the picture" and never learn
 * which player is behind it.
 *
 * [Surface] fills the space [modifier] gives it, with the picture placed by [scale].
 */
interface VideoSurfaceProvider {
    @Composable
    fun Surface(modifier: Modifier, scale: VideoScale)
}

/** How the picture sits in its space: [FIT] shows all of it (bars where the shapes differ), [FILL] covers the space and crops. */
enum class VideoScale { FIT, FILL }

package com.cosmotune.video.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.video.local.VideoAspect

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

    /**
     * [Surface] for a local file, whose picture the person shapes ([VideoAspect]: stretched, forced to 16:9, and so on). An engine that
     * cannot do more than fit and fill keeps this default.
     */
    @Composable
    fun Surface(modifier: Modifier, aspect: VideoAspect) = Surface(modifier, aspect.baseScale())
}

/** What [VideoScale] an engine that knows only fit and fill uses for this aspect: fill for the ones that cover the space. */
fun VideoAspect.baseScale(): VideoScale = if (this == VideoAspect.FILL || this == VideoAspect.STRETCH) VideoScale.FILL else VideoScale.FIT

/** How the picture sits in its space: [FIT] shows all of it (bars where the shapes differ), [FILL] covers the space and crops. */
enum class VideoScale { FIT, FILL }

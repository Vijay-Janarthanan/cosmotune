package com.cosmotune.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.cosmotune.ui.pip.PipOwner
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.settings.VideoSettingsRepository
import java.awt.GraphicsEnvironment

/**
 * The desktop's picture-in-picture is a window of its own ([DesktopPip]): small, borderless and always on top, opened by
 * [PictureInPictureState.enter] (the button, the P key) and closed by [PictureInPictureState.exit]. It is supported where there is a
 * screen and the engine draws a surface; `update` has nothing to tell it (nothing enters by itself when the app is left). The window
 * is the one Watch and the song's music video open too ([com.cosmotune.ui.pip.DesktopPipBoard]): one player in it at a time.
 */
@Composable
actual fun rememberPictureInPicture(): PictureInPictureState {
    val graph = LocalVideoGraph.current
    val type = LocalCosmoType.current
    return remember(graph, type) {
        DesktopPictureInPicture(graph.playback, graph.surface, graph.settings) { content ->
            CompositionLocalProvider(LocalCosmoType provides type) { content() }
        }
    }
}

private class DesktopPictureInPicture(
    private val playback: VideoPlayback,
    private val surface: VideoSurfaceProvider?,
    private val settings: VideoSettingsRepository,
    private val provide: @Composable (@Composable () -> Unit) -> Unit,
) : PictureInPictureState {
    override val supported: Boolean get() = surface != null && !GraphicsEnvironment.isHeadless()

    /** The window is open with Video in it (Watch and the song's music video use the same window). */
    override val active: Boolean get() = DesktopPip.isOpen && DesktopPip.owner == PipOwner.VIDEO

    override val manual: Boolean get() = true

    override fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?) = Unit

    override fun enter() = DesktopPip.open(playback, surface, settings, provide)

    override fun exit() = DesktopPip.close(returnToApp = false)
}

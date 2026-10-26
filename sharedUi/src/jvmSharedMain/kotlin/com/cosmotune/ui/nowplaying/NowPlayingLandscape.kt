package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.cosmotune.design.NowPlayingVideoMetrics
import com.cosmotune.design.LandscapeMetrics
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.cosmoAmbientGround
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.ui.player.CanvasArtworkPlayer
import com.cosmotune.music.ui.player.CanvasContentMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Now Playing in a window that is wider than tall and not wide ([com.cosmotune.design.NowPlayingLayout.choose]: narrower than 840 dp
 * or shorter than 600 dp; a phone on its side, a small tablet): two panes, side by side, half the window each ([LandscapeMetrics.MEDIA_FRACTION]).
 * On the left the media ([media]: the video, or the cover, as big as the pane allows), on the right a column: [topBar], then
 * [controls], which is where the title, the lyrics or the queue, the scrubber, the transport and the tools go.
 *
 * Under the two panes is the ambient ground, with the media, marked as what the sheets blur ([hazeState]). The panes keep
 * clear of the display cutout and the system bars at the sides and the foot (not of the keyboard); the top bar and the media pane keep clear of the
 * status bar themselves. Nothing here reads the playhead or the reveal.
 */
@Composable
internal fun NowPlayingLandscape(
    hazeState: HazeState,
    media: @Composable BoxScope.() -> Unit,
    topBar: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    val mediaShare = LandscapeMetrics.MEDIA_FRACTION
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            Box(Modifier.fillMaxSize().cosmoAmbientGround())
            Row(Modifier.fillMaxSize().windowInsetsPadding(sides)) {
                Box(Modifier.weight(mediaShare).fillMaxHeight(), content = media)
                Spacer(Modifier.weight(1f - mediaShare).fillMaxHeight())
            }
        }
        Row(Modifier.fillMaxSize().windowInsetsPadding(sides)) {
            Spacer(Modifier.weight(mediaShare).fillMaxHeight())
            Column(Modifier.weight(1f - mediaShare).fillMaxHeight()) {
                topBar()
                controls()
            }
        }
    }
}

/**
 * The left pane's content: the video when one is wanted, filling the pane ([CanvasContentMode.CROP]) under the same superellipse
 * as the cover, or the cover, square, centred and as big as the pane allows ([LandscapeMetrics.coverSideDp]), radius 28.
 * [reveal] (0 = the cover shows, 1 = the video is all the way in) crosses one into the other: the cover fades out as the clip's
 * own alpha comes up (`presentationAlpha`, not a Compose layer: on the phone the clip is a `TextureView`). The cover's
 * resting rectangle goes to the shell as `playerCoverBounds`, which the open and close morph lands its travelling cover on.
 */
@Composable
internal fun NowPlayingLandscapeMedia(
    canvas: CanvasArtwork?,
    wantVideo: Boolean,
    isPlaying: Boolean,
    reveal: State<Float>,
    onRenderedChanged: (Boolean) -> Unit,
    coverUrl: String?,
    shell: CosmoShellState?,
    modifier: Modifier = Modifier,
) {
    var videoBounds by remember(canvas) { mutableStateOf(IntSize.Zero) }
    val shape = remember { SuperellipseShape(LandscapeMetrics.MEDIA_RADIUS_DP.dp) }
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(com.cosmotune.music.ui.theme.stableStatusBarInsets()).padding(LandscapeMetrics.MEDIA_PADDING_DP.dp)) {
        val side = LandscapeMetrics.coverSideDp(maxWidth.value, maxHeight.value).dp
        Box(
            Modifier
                .align(Alignment.Center)
                .size(side)
                .onGloballyPositioned { shell?.playerCoverBounds = it.boundsInRoot() }
                .graphicsLayer { alpha = 1f - reveal.value.coerceIn(0f, 1f) },
        ) {
            CoverImage(coverUrl, side, CosmoRadii.coverNowPlaying)
        }
        if (wantVideo && canvas != null) {
            val frame = NowPlayingVideoMetrics.fit(maxWidth.value, maxHeight.value)
            Box(Modifier.align(Alignment.Center).size(frame.width.dp, frame.height.dp).clip(shape)) {
                CanvasArtworkPlayer(
                    canvas = canvas,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize().onSizeChanged { videoBounds = it },
                    contentMode = CanvasContentMode.FIT_PORTRAIT,
                    portraitRevealBounds = videoBounds,
                    onRenderedChanged = onRenderedChanged,
                    presentationAlpha = { reveal.value.coerceIn(0f, 1f) },
                )
            }
        }
    }
}

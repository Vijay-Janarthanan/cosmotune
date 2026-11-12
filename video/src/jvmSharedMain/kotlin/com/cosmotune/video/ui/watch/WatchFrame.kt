package com.cosmotune.video.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_collapse
import com.cosmotune.video.resources.video_more
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.VideoPlayerBox
import org.jetbrains.compose.resources.stringResource

/*
 * The frame of the Orbit watch page (board B): the top row (collapse, the mono "VIDEO · CHANNEL", the chips and more), the video in
 * its rounded frame with nothing on it, and the chips that pick what the page shows beside or under it (Up next, Comments, Chapters).
 */

/** The video's frame: radius 28 (a 16:9 card of the board), on the colour card's own colour round a fitted picture. */
private val FRAME_SHAPE = RoundedCornerShape(28.dp)

/**
 * The height of the video's frame [width] wide: the video's own shape ([aspect], 16:9 until known) for Whole, 16:9 for [fill], at
 * most [max] (a tall video in Whole is then shown whole in a shorter frame, the card's colour at its sides).
 */
internal fun watchFrameHeight(width: Dp, max: Dp, aspect: Float, fill: Boolean): Dp {
    val shape = if (fill || !(aspect > 0f) || !aspect.isFinite()) 16f / 9f else aspect
    return (width / shape).coerceAtMost(max).coerceAtLeast(0.dp)
}

/**
 * The video in its frame: `VideoPlayerBox(controls = false)` (the picture, its subtitles and gestures; the controls are the card's),
 * fitted whole or cut to fill as [WatchSession.fill] says, on [ground]. [onCinema] is the swipe up (and the desktop's double click),
 * [onCollapse] the swipe down; [onBounds] reports the picture's place for picture-in-picture.
 */
@Composable
internal fun WatchVideoFrame(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    layout: PlayerLayout,
    ground: Color,
    modifier: Modifier,
    onCinema: () -> Unit,
    onBounds: (Rect) -> Unit,
    onCollapse: (() -> Unit)? = null,
    title: String? = null,
    poster: String? = null,
) {
    Box(modifier.clip(FRAME_SHAPE).background(ground).testTag("video:watch:frame")) {
        VideoPlayerBox(
            playback = playback,
            surface = surface,
            layout = layout,
            modifier = Modifier.fillMaxSize(),
            onCollapse = onCollapse,
            onToggleFullscreen = onCinema,
            onBoundsChanged = onBounds,
            title = title,
            controls = false,
            scale = if (WatchSession.fill) VideoScale.FILL else VideoScale.FIT,
            ground = ground,
            poster = poster,
            hoverControls = true,
        )
    }
}

/**
 * The page's top row (board B): a round collapse button when [onCollapse] is given, the mono [label] ("VIDEO · KAAPI STORIES"),
 * [chips] (the wide page's segment chips) and the round more button ([onMore], the video's sheet).
 */
@Composable
internal fun WatchTopRow(
    label: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    onCollapse: (() -> Unit)?,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    chips: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onCollapse != null) {
            OrbitRoundButton(CosmoIcons.ChevronDown, stringResource(Res.string.video_collapse), look.ink, onCollapse, onInk = look.ground)
            Spacer(Modifier.width(12.dp))
        }
        OrbitLabel(label, fonts, look.ink.copy(alpha = 0.75f), Modifier.weight(1f))
        if (chips != null) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), content = chips)
        }
        if (onMore != null) {
            Spacer(Modifier.width(8.dp))
            OrbitRoundButton(CosmoIcons.More, stringResource(Res.string.video_more), look.ink, onMore, onInk = look.ground)
        }
    }
}

/** The segment chips (Up next, Chapters when there are some, Comments unless offline): the one showing is solid ink. */
@Composable
internal fun RowScope.WatchSegmentChips(segments: List<WideSegment>, chosen: WideSegment, onChoose: (WideSegment) -> Unit, fonts: OrbitFonts, look: OrbitLook) {
    for (segment in segments) {
        OrbitSelectChip(stringResource(segment.label), selected = segment == chosen, fonts = fonts, look = look, onClick = { onChoose(segment) })
    }
}

/** The row of segment chips standing on its own (the phone's, under the actions), in the page's margin. */
@Composable
internal fun WatchSegmentRow(segments: List<WideSegment>, chosen: WideSegment, onChoose: (WideSegment) -> Unit, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    com.cosmotune.ui.orbit.OrbitChipRow(side, Modifier.padding(top = 14.dp, bottom = 4.dp)) {
        WatchSegmentChips(segments, chosen, onChoose, fonts, look)
    }
}

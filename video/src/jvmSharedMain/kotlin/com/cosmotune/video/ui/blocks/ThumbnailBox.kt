package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_badge_live
import com.cosmotune.video.resources.video_badge_upcoming
import com.cosmotune.video.resources.video_dl_done_cd
import com.cosmotune.video.resources.video_duration_hm
import com.cosmotune.video.resources.video_duration_m
import org.jetbrains.compose.resources.stringResource

/*
 * The picture of a video and what sits on it (spec 5.0): a 16:9 thumbnail clipped to a superellipse, the duration, Live and Upcoming
 * badges, the watched bar, the count strip of a playlist and the downloaded badge. Every fill is drawn from the live palette in a
 * draw lambda; the only colours that are not the palette's are white text and black scrims over a picture, the documented exception.
 */

/** The white of the watched bar's unplayed part: 30 % (a media colour over a picture). */
private val WATCHED_TRACK = Color.White.copy(alpha = 0.30f)

/** The black behind the duration badge's white text: 72 % (a media scrim). */
private val DURATION_SCRIM = Color.Black.copy(alpha = VideoMetrics.BADGE_SCRIM_ALPHA)

/** The badge's shape: radius 6 (the superellipse rule). */
private val BADGE_SHAPE = SuperellipseShape(VideoMetrics.BADGE_RADIUS_DP.dp)

/** The count strip of a playlist (spec 5.3): the right 40 % of the thumbnail, black at 60 %. */
internal const val COUNT_STRIP_FRACTION = 0.40f
internal const val COUNT_STRIP_ALPHA = 0.60f
private val COUNT_STRIP_SCRIM = Color.Black.copy(alpha = COUNT_STRIP_ALPHA)
private val COUNT_STRIP_ICON = 20.dp
private const val COUNT_STRIP_SP = 13f

/**
 * The downloaded badge (spec 5.0): a 20 dp `accent` circle 6 dp inside the thumbnail's top-right corner, with
 * `CosmoIcons.Downloaded` at 14 dp in `onAccent` (the palette's accent, so it reads on any picture).
 */
internal const val DOWNLOADED_BADGE_DP = 20f
internal const val DOWNLOADED_ICON_DP = 14f
internal const val DOWNLOADED_INSET_DP = 6f

/**
 * A thumbnail: a 16:9 box [widthDp] wide, clipped to a superellipse whose radius follows the cover rule keyed on its height
 * ([VideoMetrics.thumbRadiusDp]: 22 from 160 dp up, 20 from 140, 18 from 120, else 16). The picture is [url] (the shimmer while it
 * loads, the placeholder fill behind it and when there is none).
 *
 * On the picture, at most one of: the Live badge ([live]), the Upcoming badge ([upcoming]) or the duration ([durationSec]), 8 dp
 * from the bottom-right corner; the count strip ([countStrip], the number a playlist has, drawn over the right 40 %) takes the
 * duration's place. [watchedFraction] draws the watched bar at the bottom edge (null: none; the caller passes null below
 * [VideoMetrics.WATCHED_MIN_MS], as `VideoItem.watchedFraction` does). [downloaded] adds the badge of a downloaded video.
 */
@Composable
fun ThumbnailBox(
    url: String?,
    widthDp: Dp,
    modifier: Modifier = Modifier,
    durationSec: Long? = null,
    live: Boolean = false,
    upcoming: Boolean = false,
    watchedFraction: Float? = null,
    countStrip: String? = null,
    downloaded: Boolean = false,
) {
    ThumbnailFrame(
        url = url,
        modifier = modifier.size(widthDp, VideoMetrics.thumbHeightDp(widthDp.value).dp),
        shape = ThumbnailShape,
        durationSec = durationSec,
        live = live,
        upcoming = upcoming,
        watchedFraction = watchedFraction,
        countStrip = countStrip,
        downloaded = downloaded,
    )
}

/**
 * The corner of a thumbnail of any size: [VideoMetrics.thumbRadiusDp] of the box's shorter side (the height of a 16:9 picture, the
 * width of a vertical Shorts card: the cover rule is keyed on the shorter side), so a card that fills its width and a box of a fixed
 * width round alike, and nothing has to measure the card to know its radius.
 */
internal object ThumbnailShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radiusDp = VideoMetrics.thumbRadiusDp(minOf(size.width, size.height) / density.density)
        return SuperellipseShape(radiusDp.dp).createOutline(size, layoutDirection, density)
    }
}

/** Where the picture is: asked for, on its way, or not coming. The shimmer shows only while it is on its way. */
private enum class ImagePhase { LOADING, SHOWN, FAILED }

/**
 * The thumbnail with everything on it, sized by [modifier] (a fixed size, or a width and an aspect ratio) and cut to [shape]. The
 * public [ThumbnailBox] and the cards, rows and the sheet's header are all this.
 */
@Composable
internal fun ThumbnailFrame(
    url: String?,
    modifier: Modifier,
    shape: Shape,
    durationSec: Long? = null,
    live: Boolean = false,
    upcoming: Boolean = false,
    watchedFraction: Float? = null,
    countStrip: String? = null,
    downloaded: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    var phase by remember(url) { mutableStateOf(ImagePhase.LOADING) }
    Box(
        modifier
            .clip(shape)
            // the placeholder: read while drawing, so it follows a palette fade
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) },
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { phase = ImagePhase.SHOWN },
                onError = { phase = ImagePhase.FAILED },
            )
            if (phase == ImagePhase.LOADING) CosmoShimmer(Modifier.fillMaxSize(), RectangleShape)
        }
        if (watchedFraction != null) WatchedBar(watchedFraction)
        if (countStrip != null) {
            CountStrip(countStrip)
        } else when {
            live -> TimeBadge(BadgeKind.LIVE, stringResource(Res.string.video_badge_live), null)
            upcoming -> TimeBadge(BadgeKind.UPCOMING, stringResource(Res.string.video_badge_upcoming), null)
            durationSec != null && durationSec > 0 -> TimeBadge(BadgeKind.DURATION, VideoText.duration(durationSec), spokenDuration(durationSec))
        }
        if (downloaded) VideoDownloadedBadge(Modifier.align(Alignment.TopEnd).padding(DOWNLOADED_INSET_DP.dp))
    }
}

/** The watched bar (spec 5.0): 3 dp at the bottom edge, the played part in `accent`, the rest white at 30 %. */
@Composable
private fun BoxScope.WatchedBar(fraction: Float) {
    val colors = LocalCosmoColors.current
    val played = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(VideoMetrics.WATCHED_BAR_DP.dp)
            .drawBehind {
                drawRect(WATCHED_TRACK)
                val width = size.width * played
                val rtl = layoutDirection == LayoutDirection.Rtl
                drawRect(colors.live(CoverField.ACCENT), topLeft = Offset(if (rtl) size.width - width else 0f, 0f), size = Size(width, size.height))
            },
    )
}

private enum class BadgeKind { DURATION, LIVE, UPCOMING }

/**
 * The duration, Live or Upcoming badge (spec 5.0): 8 dp from the bottom-right corner, 20 high, padding 0 6, radius 6, 12 sp 650
 * tabular. Duration: white on black at 72 %. Live: `onDanger` on `danger`. Upcoming: `onAccentContainer` on `accentContainer`.
 * [description] is what a screen reader says instead of the digits ("4 min").
 */
@Composable
private fun BoxScope.TimeBadge(kind: BadgeKind, text: String, description: String?) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val style = remember(type) {
        type.label.copy(fontSize = VideoMetrics.BADGE_SP.sp, fontWeight = FontWeight(VideoMetrics.FEED_TITLE_WEIGHT), letterSpacing = 0.sp).tabular()
    }
    val ink = when (kind) {
        BadgeKind.DURATION -> Color.White
        BadgeKind.LIVE -> Color(palette.onDanger)
        BadgeKind.UPCOMING -> Color(palette.onAccentContainer)
    }
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(VideoMetrics.BADGE_INSET_DP.dp)
            .height(VideoMetrics.BADGE_H_DP.dp)
            .clip(BADGE_SHAPE)
            .drawBehind {
                drawRect(
                    when (kind) {
                        BadgeKind.DURATION -> DURATION_SCRIM
                        BadgeKind.LIVE -> colors.live(CoverField.DANGER)
                        BadgeKind.UPCOMING -> colors.live(CoverField.ACCENT_CONTAINER)
                    },
                )
            }
            .padding(horizontal = VideoMetrics.BADGE_PAD_H_DP.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** A duration for a screen reader: "1 h 2 min", "4 min"; under a minute the digits ("0:45") are all there is. */
@Composable
private fun spokenDuration(sec: Long): String {
    val parts = VideoText.durationParts(sec)
    return when {
        parts.hours > 0 -> stringResource(Res.string.video_duration_hm, parts.hours, parts.minutes)
        parts.minutes > 0 -> stringResource(Res.string.video_duration_m, parts.minutes)
        else -> VideoText.duration(sec)
    }
}

/**
 * The count strip of a playlist (spec 5.3): the right 40 % of the thumbnail in black at 60 %, `CosmoIcons.List` over the count, white
 * and centred. The row's meta line says the same in words, so the strip is skipped by screen readers.
 */
@Composable
private fun BoxScope.CountStrip(count: String) {
    val type = LocalCosmoType.current
    val style = remember(type) { type.label.copy(fontSize = COUNT_STRIP_SP.sp, fontWeight = FontWeight(VideoMetrics.FEED_TITLE_WEIGHT), letterSpacing = 0.sp).tabular() }
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(COUNT_STRIP_FRACTION)
            .drawBehind { drawRect(COUNT_STRIP_SCRIM) }
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(CosmoIcons.List, contentDescription = null, tint = Color.White, modifier = Modifier.size(COUNT_STRIP_ICON))
        Text(text = count, style = style, color = Color.White, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * The badge of a downloaded video (spec 5.0): a 20 dp `accent` circle with `CosmoIcons.Downloaded` at 14 dp in `onAccent`, described
 * as "Downloaded" (`video_dl_done_cd`). The thumbnail puts it 6 dp inside its top-right corner; a Shorts card places it the same way.
 */
@Composable
fun VideoDownloadedBadge(modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    Box(
        modifier
            .size(DOWNLOADED_BADGE_DP.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(colors.live(CoverField.ACCENT)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CosmoIcons.Downloaded,
            contentDescription = stringResource(Res.string.video_dl_done_cd),
            tint = Color(colors.palette.onAccent),
            modifier = Modifier.size(DOWNLOADED_ICON_DP.dp),
        )
    }
}

/** A channel's picture: a circle of [size], the placeholder fill behind it and when there is none. Decoration: the name is read beside it. */
@Composable
internal fun ChannelAvatar(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) },
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

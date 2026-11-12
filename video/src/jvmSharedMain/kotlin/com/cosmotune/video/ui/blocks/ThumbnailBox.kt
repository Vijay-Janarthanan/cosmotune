package com.cosmotune.video.ui.blocks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.orbit.liveRed
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_badge_live
import com.cosmotune.video.resources.video_badge_upcoming
import com.cosmotune.video.resources.video_dl_done_cd
import com.cosmotune.video.resources.video_duration_hm
import com.cosmotune.video.resources.video_duration_m
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cosmotune.ui.theme.CosmoRadii
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The picture of a video (Orbit rule 9: nothing is drawn on a picture): a thumbnail clipped to its shape, the shimmer while it loads
 * and the palette's placeholder behind it. What used to sit on it (the length, Live, the watched bar, the downloaded mark, a
 * playlist's count) is said beside it now: in the card's meta line, its watched line under the picture, a row's words.
 */

/**
 * A thumbnail: a 16:9 box [widthDp] wide, clipped to the One radius keyed on its height ([VideoMetrics.thumbRadiusDp]). The picture
 * is [url] (the shimmer while it loads, the placeholder fill behind it and when there is none).
 */
@Composable
fun ThumbnailBox(url: String?, widthDp: Dp, modifier: Modifier = Modifier) {
    ThumbnailFrame(url = url, modifier = modifier.size(widthDp, VideoMetrics.thumbHeightDp(widthDp.value).dp), shape = ThumbnailShape)
}

/**
 * The corner of a thumbnail of any size: [VideoMetrics.thumbRadiusDp] of the box's shorter side, so a card that fills its width and a
 * box of a fixed width round alike, and nothing has to measure the card to know its radius.
 */
internal object ThumbnailShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radiusDp = VideoMetrics.thumbRadiusDp(minOf(size.width, size.height) / density.density)
        return RoundedCornerShape(radiusDp.dp).createOutline(size, layoutDirection, density)
    }
}

/** An Orbit picture's corner: 16 dp for a row's thumbnail, the cards' 24 being the kit's. */
internal val RowPictureShape = RoundedCornerShape(16.dp)

/**
 * Draws the picture of a thumbnail address instead of loading it. Null in the app; the screen previews (which have no network) provide
 * one that paints a distinct picture for each address.
 */
internal val LocalThumbnailStandIn = staticCompositionLocalOf<(@Composable (url: String, modifier: Modifier) -> Unit)?> { null }

/** Where the picture is: asked for, on its way, or not coming. The shimmer shows only while it is on its way. */
private enum class ImagePhase { LOADING, SHOWN, FAILED }

/**
 * The picture of a video, sized by [modifier] (a fixed size, or a width and an aspect ratio) and cut to [shape]; nothing on it. The
 * public [ThumbnailBox] and the cards, rows and the sheets' headers are all this.
 */
@Composable
internal fun ThumbnailFrame(url: String?, modifier: Modifier, shape: Shape, badge: String? = null, badgeLive: Boolean = false, badgeEnd: Dp = BADGE_INSET) {
    val colors = LocalCosmoColors.current
    val stand = LocalThumbnailStandIn.current
    var phase by remember(url) { mutableStateOf(ImagePhase.LOADING) }
    Box(
        modifier
            .clip(shape)
            // the placeholder: read while drawing, so it follows a palette fade
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) },
    ) {
        if (url != null && stand != null) {
            stand(url, Modifier.fillMaxSize())
        } else if (url != null) {
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
        if (!badge.isNullOrBlank()) ThumbnailBadge(badge, badgeLive, Modifier.align(Alignment.BottomEnd).padding(end = badgeEnd, bottom = BADGE_INSET))
    }
}

/** The room between a picture's corner and its badge. */
internal val BADGE_INSET = 8.dp

/**
 * The label in the bottom-right corner of a picture, as the site has it: the video's length ("12:04", "1:02:33"), "LIVE" for a live
 * stream (red), "Mix" or "Playlist" for a suggested list. A small dark rounded label with white text, the same in the light and dark looks.
 */
@Composable
internal fun ThumbnailBadge(text: String, live: Boolean, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    val look = orbitLook()
    val style = remember(type) { type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).tabular() }
    Text(
        text = text,
        style = style,
        color = if (live && look.dark) Color.Black else Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (live) liveRed(look) else Color.Black.copy(alpha = BADGE_ALPHA))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private const val BADGE_ALPHA = 0.78f

/**
 * What the badge on [item]'s picture says: "LIVE" for a live stream, "Mix" or "Playlist" for a suggested list, else its length. Null for a
 * premiere that has not started, a Short, and a video whose length is not known.
 */
@Composable
internal fun badgeText(item: VideoItem): String? {
    val live = stringResource(Res.string.video_badge_live)
    return remember(item.id, item.durationSec, item.isLive, item.isUpcoming, item.playlist?.label, live) { badgeTextOf(item, live) }
}

/** [badgeText] without the composition: the words for "LIVE" are passed in. */
internal fun badgeTextOf(item: VideoItem, liveWord: String): String? = when {
    item.playlist != null -> item.playlist?.label
    item.isLive -> liveWord
    item.isUpcoming || item.isShort -> null
    else -> item.durationSec?.takeIf { it > 0 }?.let { VideoText.duration(it) }
}

/**
 * A channel's picture: a circle of [size], the placeholder fill behind it. When there is no picture (the list carried none, or it
 * did not load) and the channel's [name] is known, the circle shows the name's first letter in white on a colour that follows the
 * name, the same for the same name everywhere. Decoration: the name is read beside it.
 */
@Composable
internal fun ChannelAvatar(url: String?, size: Dp, modifier: Modifier = Modifier, name: String? = null) {
    val colors = LocalCosmoColors.current
    val stand = LocalThumbnailStandIn.current
    var failed by remember(url) { mutableStateOf(false) }
    val initial = remember(name) { name?.let(::avatarInitial) }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) },
    ) {
        if (url != null && stand != null) {
            stand(url, Modifier.fillMaxSize())
        } else if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        } else if (initial != null) {
            AvatarInitial(initial, name.orEmpty(), size)
        }
    }
}

/** The letter in an avatar with no picture, on the colour of its name. */
@Composable
private fun AvatarInitial(initial: String, name: String, size: Dp) {
    val type = LocalCosmoType.current
    val tint = remember(name) { avatarTint(name) }
    val style = remember(type, size) {
        type.label.copy(fontSize = (size.value * AVATAR_LETTER).sp, fontWeight = FontWeight(VideoMetrics.FEED_TITLE_WEIGHT), letterSpacing = 0.sp)
    }
    Box(Modifier.fillMaxSize().drawBehind { drawRect(tint) }, contentAlignment = Alignment.Center) {
        Text(text = initial, style = style, color = Color.White, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** The letter's size against the circle's. */
private const val AVATAR_LETTER = 0.44f

/** The first letter or digit of [name], upper case; null when the name has none. */
internal fun avatarInitial(name: String): String? {
    val at = name.indexOfFirst { it.isLetterOrDigit() }
    if (at < 0) return null
    val code = name.codePointAt(at)
    return String(Character.toChars(code)).uppercase()
}

/** A colour that follows [name]: 12 hues around the wheel, dark enough for white text. The same name gives the same colour. */
internal fun avatarTint(name: String): Color {
    val hash = name.trim().lowercase().fold(0) { acc, c -> acc * 31 + c.code }
    val hue = (Math.floorMod(hash, AVATAR_HUES)) * (360f / AVATAR_HUES)
    return Color.hsv(hue, 0.50f, 0.52f)
}

private const val AVATAR_HUES = 12

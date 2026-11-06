package com.cosmotune.video.ui.shorts

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.PEACH
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.Springs
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_comments
import com.cosmotune.video.resources.video_like
import com.cosmotune.video.resources.video_liked_state
import com.cosmotune.video.resources.video_more
import com.cosmotune.video.resources.video_mute
import com.cosmotune.video.resources.video_share
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.resources.video_subscribe
import com.cosmotune.video.resources.video_subscribed
import com.cosmotune.video.resources.video_unmute
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.music.sharedui.resources.cosmo_back
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.cosmotune.video.resources.video_search
import com.cosmotune.video.resources.video_dislike
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * What lies at the edges of a Short (Orbit board B: "Shorts, a full-height vertical pager, the actions as round buttons beside, nothing
 * over the video's centre"): the scrims, the top row (round back, "Shorts." in the wide face, round search and mute), the rail of
 * round buttons at the end edge with their counts in the mono label face, the bottom block (the channel's disc, the name and the
 * Subscribe pill, the title), the paused mark under the top row and the 2x pill. Nothing here is glass. White ink and black scrims
 * over the video are the documented media exception.
 *
 * The numbers VideoMetrics does not carry: the top row's 8 dp under the status bar and 12 dp sides, 8 dp inside the channel row and between
 * it and the title; the chip's 12 dp sides.
 */

private val SCRIM_TOP = Color.Black.copy(alpha = VideoMetrics.PAGER_TOP_SCRIM_ALPHA)
private val SCRIM_BOTTOM = Color.Black.copy(alpha = VideoMetrics.PAGER_BOTTOM_SCRIM_ALPHA)

/** What the bottom scrim covers of a Short (DESIGN_SYSTEM.md 6.2: the bottom 35 %). */
private const val SCRIM_BOTTOM_SHARE = 0.35f

/** The disc behind a rail button and the small glyphs over the video: black at 28 %, so white reads over a bright frame. */
private val RAIL_DISC = Color.Black.copy(alpha = 0.28f)

/** The pale wash over a round button's dark disc, so it reads over a dark frame as well as a bright one. */
private val RAIL_LIFT = Color.White.copy(alpha = 0.14f)

/** The disc of the pause glyph and the fill of the 2x pill: black at 56 %. */
private val GLYPH_FILL = Color.Black.copy(alpha = 0.56f)

/** The small tonal Subscribe over the video: white at 18 %. */
private val CHIP_FILL = Color.White.copy(alpha = 0.18f)

/** The top row stands this far under the status bar, this far from the sides. */
internal val TOP_ROW_TOP = 8.dp
internal val TOP_ROW_SIDE = 12.dp
private val TOP_ROW_TITLE_GAP = 8.dp

/** Inside the bottom block: between the avatar, the name and the chip; between the channel row and the title. */
private val BLOCK_GAP = 8.dp

/** The Subscribe chip: 12 dp in from its sides. */
private val CHIP_SIDE = 12.dp

/** The title shows two lines, five with the start of the description when tapped. */
internal const val TITLE_LINES = 2
internal const val TITLE_LINES_OPEN = 5

/** A rail count: `caption`, 4 dp under its button. */
private val COUNT_GAP = 4.dp

/** The pause glyph's icon inside its 64 dp circle. */
private val GLYPH_ICON = 22.dp

/** The "2x speed" pill: 36 dp, 14 dp sides (the seek pill's look, spec 6.5). */
private val PILL_SIDE = 14.dp
private val PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * The scrims of a Short (DESIGN_SYSTEM.md 6.2): black from 40 % to 0 over the top 120 dp, from 0 to 72 % over the bottom 35 %; the brushes
 * are made once per size.
 */
internal fun Modifier.shortsScrims(): Modifier = drawWithCache {
    val topHeight = VideoMetrics.PAGER_TOP_SCRIM_DP.dp.toPx().coerceAtMost(size.height)
    val bottomHeight = size.height * SCRIM_BOTTOM_SHARE
    val top = Brush.verticalGradient(0f to SCRIM_TOP, 1f to Color.Transparent, startY = 0f, endY = topHeight)
    val bottomAt = size.height - bottomHeight
    val bottom = Brush.verticalGradient(0f to Color.Transparent, 1f to SCRIM_BOTTOM, startY = bottomAt, endY = size.height)
    val topSize = Size(size.width, topHeight)
    val bottomOffset = Offset(0f, bottomAt)
    val bottomSize = Size(size.width, bottomHeight)
    onDrawBehind {
        drawRect(top, size = topSize)
        drawRect(bottom, topLeft = bottomOffset, size = bottomSize)
    }
}

/**
 * The top row: [topInset] plus 8 dp from the top, 12 dp from the sides: the round back button, "Shorts." in the wide face with its
 * peach full stop, and at the end round Search and mute (`CosmoIcons.Volume`, or `VideoIcons.VolumeOff` while [muted]); white over
 * the video, on faint dark discs.
 */
@Composable
internal fun ShortsTopRow(muted: Boolean, onBack: () -> Unit, onToggleMute: () -> Unit, topInset: Dp, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val fonts = orbitFonts()
    val title = stringResource(Res.string.video_shorts)
    Row(
        modifier.padding(start = TOP_ROW_SIDE, end = TOP_ROW_SIDE, top = topInset + TOP_ROW_TOP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaRoundButton(CosmoIcons.ArrowLeft, stringResource(SharedRes.string.cosmo_back), onBack, VideoMetrics.PAGER_TOP_BUTTON_DP.dp)
        Spacer(Modifier.width(TOP_ROW_TITLE_GAP + 4.dp))
        Text(
            buildAnnotatedString { append(title); withStyle(SpanStyle(color = PEACH)) { append(".") } },
            style = fonts.section.copy(fontSize = 22.sp), color = Color.White, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() },
        )
        MediaRoundButton(CosmoIcons.Search, stringResource(Res.string.video_search), onSearch, VideoMetrics.PAGER_TOP_BUTTON_DP.dp)
        Spacer(Modifier.width(8.dp))
        MediaRoundButton(
            if (muted) VideoIcons.VolumeOff else CosmoIcons.Volume,
            stringResource(if (muted) Res.string.video_unmute else Res.string.video_mute),
            onToggleMute, VideoMetrics.PAGER_TOP_BUTTON_DP.dp,
        )
    }
}

/**
 * A round button over the video (Orbit's round button in the media's ink): white on a faint dark disc, or solid white with a dark
 * glyph when [on]. [enabled] false fades it.
 */
@Composable
internal fun MediaRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: Dp, on: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .cosmoFocusRing(interaction, size / 2, floating = true)
            .clip(CircleShape)
            .background(if (on) Color.White else RAIL_DISC)
            .background(if (on) Color.Transparent else RAIL_LIFT)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(size * 0.45f)) }
}

/**
 * The right rail (DESIGN_SYSTEM.md 6.2), 48 dp icons with a `caption` count under each: like (filled when [liked]), dislike (a figure while
 * signed out: [onDislike] null), comments, share and more (the video sheet). [overMedia]: white over the video, on a faint disc (the phone's
 * rail); on the page (the desktop's column of buttons) tonal, with the palette's secondary ink.
 */
@Composable
internal fun ShortsRail(
    liked: Boolean,
    likeCount: Long?,
    commentCount: Long?,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    overMedia: Boolean,
    modifier: Modifier = Modifier,
    disliked: Boolean = false,
    dislikeCount: Long? = null,
    onDislike: (() -> Unit)? = null,
) {
    val likedState = stringResource(Res.string.video_liked_state)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VideoMetrics.PAGER_RAIL_GAP_DP.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RailButton(
            icon = if (liked) CosmoIcons.HeartFill else CosmoIcons.Heart,
            label = stringResource(Res.string.video_like),
            count = likeCount,
            onClick = onLike,
            overMedia = overMedia,
            state = if (liked) likedState else null,
        )
        RailButton(
            icon = VideoIcons.ThumbDown,
            label = stringResource(Res.string.video_dislike),
            count = dislikeCount,
            onClick = onDislike ?: {},
            overMedia = overMedia,
            enabled = onDislike != null,
            on = disliked,
        )
        RailButton(VideoIcons.Comment, stringResource(Res.string.video_comments), commentCount, onComments, overMedia)
        RailButton(CosmoIcons.Share, stringResource(Res.string.video_share), null, onShare, overMedia)
        RailButton(CosmoIcons.More, stringResource(Res.string.video_more), null, onMore, overMedia)
    }
}

/**
 * One button of the rail: a round 48 dp button (over the video, [MediaRoundButton]; on the page, Orbit's tonal round button in the
 * page's ink) and, when [count] is known, the count in the mono label face under it.
 */
@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Long?,
    onClick: () -> Unit,
    overMedia: Boolean,
    state: String? = null,
    enabled: Boolean = true,
    on: Boolean = false,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val size = VideoMetrics.PAGER_RAIL_BUTTON_DP.dp
    val marked = if (state != null) Modifier.semantics { stateDescription = state } else Modifier
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (overMedia) {
            MediaRoundButton(icon, label, onClick, size, on = on, enabled = enabled, modifier = marked)
        } else {
            OrbitRoundButton(icon, label, if (enabled) look.ink else look.ink.copy(alpha = 0.4f), { if (enabled) onClick() }, marked, size = size, on = on, onInk = look.ground)
        }
        if (count != null) {
            Text(
                text = VideoText.compact(count).uppercase(),
                style = fonts.label,
                color = if (overMedia) Color.White else look.dim,
                maxLines = 1,
                modifier = Modifier.padding(top = COUNT_GAP + 2.dp).clearAndSetSemantics { },
            )
        }
    }
}

/**
 * The bottom block of a Short (DESIGN_SYSTEM.md 6.2): the channel row (a 32 dp avatar and the name in `rowTitle`, which open the channel,
 * then the small tonal "Subscribe" or "Subscribed"), then [title] (DeArrow's when on) in `bodyText`, two lines; a tap on the title shows five lines with the start of [description] ([expanded], [onToggleExpanded]).
 */
@Composable
internal fun ShortsBottomBlock(
    item: VideoItem,
    title: String,
    description: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
    onOpenChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val name = item.channelName?.takeIf { it.isNotBlank() }
    val nameStyle = fonts.title.copy(fontSize = 14.5.sp)
    val shown = if (expanded && !description.isNullOrBlank()) "$title\n${description.trim()}" else title
    val titleStyle = fonts.body.copy(fontSize = 14.sp, lineHeight = 19.sp)
    Column(modifier) {
        if (item.channelId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // weighted, so the chip is measured first and a long name is the one that gives way
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onOpenChannel)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelAvatar(item.channelAvatarUrl, VideoMetrics.PAGER_AVATAR_DP.dp)
                    if (name != null) {
                        Spacer(Modifier.width(BLOCK_GAP))
                        Text(name, style = nameStyle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                Spacer(Modifier.width(BLOCK_GAP))
                ShortsSubscribeChip(subscribed, onSubscribe)
            }
            Spacer(Modifier.height(BLOCK_GAP))
        }
        Text(
            text = shown,
            style = titleStyle,
            color = Color.White,
            maxLines = if (expanded) TITLE_LINES_OPEN else TITLE_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(role = Role.Button, onClick = onToggleExpanded),
        )
    }
}

/**
 * The channel row's chip (DESIGN_SYSTEM.md 6.2): a small tonal pill, 30 dp, white at 18 % over the video, "Subscribe" or "Subscribed" in
 * white; a 48 dp touch target.
 */
@Composable
internal fun ShortsSubscribeChip(subscribed: Boolean, onClick: () -> Unit) {
    val fonts = orbitFonts()
    val label = stringResource(if (subscribed) Res.string.video_subscribed else Res.string.video_subscribe)
    val style = fonts.chip.copy(fontSize = 13.sp)
    val ink = if (subscribed) Color.White else Color.Black
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(VideoMetrics.PAGER_CHIP_DP.dp)
                .cosmoFocusRing(interaction, (VideoMetrics.PAGER_CHIP_DP / 2).dp, floating = true)
                .clip(PILL_SHAPE)
                .background(if (subscribed) CHIP_FILL else Color.White)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = CHIP_SIDE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (subscribed) {
                Icon(CosmoIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = style, color = ink, maxLines = 1)
        }
    }
}

/**
 * The play glyph that comes up when a Short is paused (spec 5.15), under the top row and never at the video's centre (Orbit
 * board B): a 48 dp circle of black at 56 % with a white
 * `CosmoIcons.Play`, fading in and out over [VideoMetrics.PAGER_PAUSE_GLYPH_MS]; [serial] starts it again (0: none yet). Reduced
 * motion: it shows without a fade for the same time. The fade is read in the layer block.
 */
@Composable
internal fun PauseGlyph(serial: Int, modifier: Modifier = Modifier) {
    if (serial == 0) return
    val reduced = LocalReducedMotion.current
    val alpha = remember(serial) { Animatable(if (reduced) 1f else 0f) }
    var shown by remember(serial) { mutableStateOf(true) }
    LaunchedEffect(serial) {
        val total = VideoMetrics.PAGER_PAUSE_GLYPH_MS
        if (reduced) {
            alpha.animateTo(1f, keyframes { durationMillis = total; 1f at total })
        } else {
            alpha.animateTo(0f, keyframes {
                durationMillis = total
                0f at 0
                1f at Springs.REDUCED_MS
                1f at total - Springs.REDUCED_MS
                0f at total
            })
        }
        shown = false
    }
    if (!shown) return
    Box(
        modifier
            .size(48.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .clip(CircleShape)
            .background(GLYPH_FILL)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CosmoIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(GLYPH_ICON))
    }
}

/** A small pill over the video ("2x speed"): 36 dp, black at 56 %, the label in white. */
@Composable
internal fun ShortsPill(text: String, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    val style = remember(type, text) { type.forText(type.control, text) }
    Box(
        modifier
            .height(VideoMetrics.SEEK_PILL_H_DP.dp)
            .clip(PILL_SHAPE)
            .background(GLYPH_FILL)
            .padding(horizontal = PILL_SIDE),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = Color.White, maxLines = 1)
    }
}

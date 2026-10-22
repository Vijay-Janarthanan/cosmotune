package com.cosmotune.video.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.design.HomeGreeting
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.Kiosk
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_home_overline
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.video.ui.blocks.VideoChips
import com.cosmotune.video.ui.blocks.VideoShelfCard
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.music.sharedui.resources.cosmo_home_afternoon
import com.cosmotune.music.sharedui.resources.cosmo_home_evening
import com.cosmotune.music.sharedui.resources.cosmo_home_late
import com.cosmotune.music.sharedui.resources.cosmo_home_morning
import com.cosmotune.music.ui.components.LocalShelfRowChrome
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import com.cosmotune.music.sharedui.resources.Res as SharedRes

/*
 * The parts Home is built of that the shared blocks do not give (plan task Y-17): the heading with the time-of-day greeting, the chip
 * row with the Shorts chip, a shelf whose cards take DeArrow's title and picture, and the placeholders. The cards themselves, the
 * chips of the kiosks and the sheet are the blocks of Y-13.
 */

// ---- DeArrow ---------------------------------------------------------------------------------------------------------------

/**
 * [item] with the title and the picture a card shows (DeArrow's when they are on and it has them, spec 7.2), looked up while the card
 * is composed. Taps and menus still go to the original [item]. The shelf and card blocks take the item whole, so the substitution
 * is made here, inside the lazy list's own item.
 */
@Composable
internal fun rememberBranded(item: VideoItem): VideoItem {
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    return remember(item, title, picture) {
        if (title == item.title && picture == item.thumbnailUrl) item else item.copy(title = title, thumbnailUrl = picture)
    }
}

// ---- The heading -----------------------------------------------------------------------------------------------------------

/** How often the greeting looks at the clock again: a page left open through midnight turns into "Late Wednesday". */
private const val GREETING_REFRESH_MS = 60_000L

/** The weekday and the period of the day, which is all the greeting depends on. */
private data class DayPart(val period: HomeGreeting.Period, val dayOfWeek: Int)

private fun dayPartNow(): DayPart {
    val now = java.time.LocalDateTime.now()
    return DayPart(HomeGreeting.period(now.hour), now.dayOfWeek.value)
}

/** "Tuesday morning", "Tuesday afternoon", "Tuesday evening" or "Late Tuesday": the same greeting Music's Home has (`cosmo_home_*`). */
@Composable
private fun rememberGreeting(): String {
    val now by produceState(initialValue = dayPartNow()) {
        while (true) {
            delay(GREETING_REFRESH_MS)
            value = dayPartNow()
        }
    }
    val locale = Locale.getDefault()
    val day = java.time.DayOfWeek.of(now.dayOfWeek).getDisplayName(java.time.format.TextStyle.FULL, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return when (now.period) {
        HomeGreeting.Period.MORNING -> stringResource(SharedRes.string.cosmo_home_morning, day)
        HomeGreeting.Period.AFTERNOON -> stringResource(SharedRes.string.cosmo_home_afternoon, day)
        HomeGreeting.Period.EVENING -> stringResource(SharedRes.string.cosmo_home_evening, day)
        HomeGreeting.Period.LATE -> stringResource(SharedRes.string.cosmo_home_late, day)
    }
}

/** `.dh-head { margin-top: 22px }` less the 14 dp the page header already keeps above its first line. */
private val WIDE_HEAD_TOP = 8.dp

/**
 * The heading (spec 5.2): "Watch now" over the greeting. On a [wide] page the title is `display(DISPLAY_L)` with 22 dp above it.
 * [extra] moves the block in from the page's edge by what the page wants more than the block's own 16 dp margin.
 */
@Composable
internal fun HomeHeader(wide: Boolean, extra: Dp, modifier: Modifier = Modifier) {
    CosmoPageHeader(
        title = rememberGreeting(),
        modifier = modifier.padding(start = extra, end = extra, top = if (wide) WIDE_HEAD_TOP else 0.dp),
        overline = stringResource(Res.string.video_home_overline),
        large = wide,
    )
}

// ---- The chip row ----------------------------------------------------------------------------------------------------------

/** How many placeholder chips stand in while the kiosks are on the way. */
private const val CHIP_PLACEHOLDERS = 4
private val CHIP_PLACEHOLDER_WIDTH = 96.dp
private val CHIP_PLACEHOLDER_RADIUS = 8.dp

/**
 * The chip row (spec 5.2): the Shorts chip first when [showShorts], then a chip for each of [kiosks] with [selectedId] chosen; a tap
 * on a kiosk calls [onSelect]. While [kiosks] is null (not answered yet) four placeholders stand after the Shorts chip.
 */
@Composable
internal fun HomeChips(
    kiosks: List<Kiosk>?,
    selectedId: String?,
    showShorts: Boolean,
    onShorts: () -> Unit,
    onSelect: (Kiosk) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shortsLabel = stringResource(Res.string.video_shorts)
    val labels = remember(kiosks) { kiosks.orEmpty().map { it.name } }
    val selected = kiosks?.indexOfFirst { it.id == selectedId } ?: -1
    val leading: @Composable () -> Unit = {
        if (showShorts) ShortsChip(label = shortsLabel, onClick = onShorts)
        if (kiosks == null) {
            repeat(CHIP_PLACEHOLDERS) {
                CosmoShimmer(Modifier.size(CHIP_PLACEHOLDER_WIDTH, ControlMetrics.CHIP_HEIGHT_DP.dp), RoundedCornerShape(CHIP_PLACEHOLDER_RADIUS))
            }
        }
    }
    VideoChips(
        labels = labels,
        selected = selected,
        onSelect = { index -> kiosks?.getOrNull(index)?.let(onSelect) },
        modifier = modifier,
        leading = if (showShorts || kiosks == null) leading else null,
    )
}

/** The chip's wash while it is pressed and while a mouse is over it, as `CosmoChip`'s state layer. */
private const val PRESSED_LAYER = 0.12f
private const val HOVERED_LAYER = 0.08f

/**
 * The Shorts chip: a chip of the row (36 dp tall, the rest recipe of `CosmoChip`: a faint ink wash and a 1 dp outline, a rounded
 * square of 0.22 of its height) with the Shorts icon before its label. `CosmoChip` takes a text only, so this one is drawn here; it
 * is a button, not a choice (it opens the Shorts pager), so it has no selected form. It keeps a 48 dp touch target, plays the tap
 * haptic, and shows the focus ring for keyboard focus.
 */
@Composable
internal fun ShortsChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val hovered = interaction.collectIsHoveredAsState()
    val rest = remember { ControlMorph.chip(ControlMetrics.CHIP_HEIGHT_DP, selected = false, pressed = false) }
    val shape = remember(rest) { SuperellipseShape(rest.radiusDp.dp, rest.exponent) }
    val ink = Color(palette.textPrimary)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(ControlMetrics.CHIP_HEIGHT_DP.dp)
            .cosmoFocusRing(interaction, rest.radiusDp.dp)
            .clip(shape)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                // the 1 dp ring is the inner half of a 2 dp stroke on the edge, the rest clipped away
                val ring = Stroke(width = 2.dp.toPx())
                onDrawBehind {
                    val wash = colors.live(CoverField.TEXT_PRIMARY)
                    drawRect(wash.copy(alpha = ControlMetrics.CHIP_REST_FILL))
                    val layer = if (pressed.value) PRESSED_LAYER else if (hovered.value) HOVERED_LAYER else 0f
                    if (layer > 0f) drawRect(wash.copy(alpha = layer))
                    drawOutline(outline, wash.copy(alpha = ControlMetrics.OUTLINE), style = ring)
                }
            }
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button) {
                haptics.play(Haptic.Tap)
                onClick()
            }
            .padding(horizontal = ControlMetrics.CHIP_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ControlMetrics.CHIP_GAP_DP.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(VideoIcons.Shorts, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.CHIP_ICON_DP.dp))
        Text(
            text = label,
            color = ink,
            style = type.forText(type.label.copy(fontSize = ControlMetrics.chipFontSp(false).sp, fontWeight = FontWeight(CHIP_WEIGHT)), label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The weight of a chip label (`.chip2`), as `CosmoChip`'s. */
private const val CHIP_WEIGHT = 620

// ---- A shelf ---------------------------------------------------------------------------------------------------------------

/** Room above a shelf, as part two's shelves and `VideoShelf` have it (`.shelf2`). */
private val SHELF_TOP = 22.dp

/**
 * A shelf of cards (spec 5.0, 5.2): what `VideoShelf` draws (22 dp above, the heading, a row that scrolls sideways, 16 dp in from
 * each side, 12 dp apart, the platform's arrows and wheel on the desktop), with each card's title and picture taken through DeArrow
 * ([rememberBranded]) inside the row's own item, which `VideoShelf` has no place for. A tap calls [onOpen] with the original item;
 * a hold, a right click and the screen reader's action call [onMore]. [watched] gives a video's watched fraction by id,
 * [downloaded] says whether a video is downloaded, [cardWidth] is 240 (280 on a wide page).
 */
@Composable
internal fun HomeShelf(
    title: String,
    items: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    watched: Map<String, Float> = emptyMap(),
    cardWidth: Dp = VideoMetrics.SHELF_CARD_W_DP.dp,
    downloaded: (String) -> Boolean = { false },
) {
    val keys = remember(items) { uniqueKeys(items) }
    val rowState = rememberLazyListState()
    Column(modifier.padding(top = SHELF_TOP)) {
        CosmoSectionHeader(title = title)
        LocalShelfRowChrome.current.Wrap(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(VideoMetrics.SHELF_GAP_DP.dp),
            ) {
                items(count = items.size, key = { keys[it] }) { index ->
                    val item = items[index]
                    VideoShelfCard(
                        item = rememberBranded(item),
                        onClick = { onOpen(item) },
                        onMore = { onMore(item) },
                        watchedFraction = watched[item.id],
                        width = cardWidth,
                        downloaded = downloaded(item.id),
                    )
                }
            }
        }
    }
}

// ---- Placeholders ----------------------------------------------------------------------------------------------------------

private val SKELETON_TITLE_HEIGHT = 16.dp
private val SKELETON_META_HEIGHT = 13.dp
private val SKELETON_TEXT_GAP = 6.dp

/** A feed card's skeleton: a 16:9 picture, a 36 dp avatar and two lines of text, as the card it stands in for. */
@Composable
internal fun CardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        CosmoShimmer(Modifier.fillMaxWidth().aspectRatio(16f / 9f), ThumbnailShape)
        Row(Modifier.fillMaxWidth().padding(top = VideoMetrics.FEED_META_TOP_DP.dp)) {
            CosmoShimmer(Modifier.size(VideoMetrics.FEED_AVATAR_DP.dp), CircleShape)
            Column(Modifier.weight(1f).padding(start = VideoMetrics.FEED_GAP_DP.dp)) {
                CosmoShimmer(Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION).height(SKELETON_TITLE_HEIGHT))
                CosmoShimmer(Modifier.padding(top = SKELETON_TEXT_GAP).fillMaxWidth(SKELETON_META_FRACTION).height(SKELETON_META_HEIGHT))
            }
        }
    }
}

private const val SKELETON_TITLE_FRACTION = 0.92f
private const val SKELETON_META_FRACTION = 0.6f

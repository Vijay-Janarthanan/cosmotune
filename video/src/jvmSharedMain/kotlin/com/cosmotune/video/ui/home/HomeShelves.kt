package com.cosmotune.video.ui.home

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.Kiosk
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_all
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.video.ui.blocks.VideoChips
import com.cosmotune.video.ui.blocks.VideoShelfCard
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.branding.rememberChannelAvatar
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.music.ui.components.LocalShelfRowChrome
import org.jetbrains.compose.resources.stringResource

/*
 * The parts Home is built of that the shared blocks do not give (plan task Y-17): the chip row (All, Shorts, the kiosks), a shelf whose
 * cards take DeArrow's title and picture, and the placeholders. The cards themselves, the chips and the sheet are the blocks of Y-13.
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
    val avatar = rememberChannelAvatar(item)
    return remember(item, title, picture, avatar) {
        if (title == item.title && picture == item.thumbnailUrl && avatar == item.channelAvatarUrl) item
        else item.copy(title = title, thumbnailUrl = picture, channelAvatarUrl = avatar)
    }
}

// ---- The chip row ----------------------------------------------------------------------------------------------------------

/** How many placeholder chips stand in while the kiosks are on the way. */
private const val CHIP_PLACEHOLDERS = 4
private val CHIP_PLACEHOLDER_WIDTH = 84.dp

/**
 * The chip row: "All" first (the mixed feed; chosen while [selectedId] is null), the Shorts chip when [showShorts] (a button, it opens
 * the pager and is never the chosen one), then a chip for each of [kiosks] with [selectedId] chosen; a tap on a kiosk calls [onSelect]. While
 * [kiosks] is null (not answered yet) placeholders stand after the others.
 */
@Composable
internal fun HomeChips(
    kiosks: List<Kiosk>?,
    selectedId: String?,
    showShorts: Boolean,
    onAll: () -> Unit,
    onShorts: () -> Unit,
    onSelect: (Kiosk) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allLabel = stringResource(Res.string.video_all)
    val shortsLabel = stringResource(Res.string.video_shorts)
    val lead = if (showShorts) 2 else 1
    val labels = remember(kiosks, showShorts, allLabel, shortsLabel) {
        buildList {
            add(allLabel)
            if (showShorts) add(shortsLabel)
            kiosks?.forEach { add(it.name) }
        }
    }
    val chosen = if (selectedId == null) 0 else (kiosks?.indexOfFirst { it.id == selectedId } ?: -1).let { if (it < 0) 0 else it + lead }
    VideoChips(
        labels = labels,
        selected = chosen,
        onSelect = { index ->
            when {
                index == 0 -> onAll()
                showShorts && index == 1 -> onShorts()
                else -> kiosks?.getOrNull(index - lead)?.let(onSelect)
            }
        },
        modifier = modifier,
        trailing = if (kiosks == null) {
            {
                repeat(CHIP_PLACEHOLDERS) {
                    CosmoShimmer(Modifier.size(CHIP_PLACEHOLDER_WIDTH, ControlMetrics.CHIP_HEIGHT_DP.dp), CircleShape)
                }
            }
        } else {
            null
        },
    )
}

/** Lays the content out [by] wider on each side than the parent allows and places it back: a full-width shelf inside a grid's padding. */
internal fun Modifier.bleedBy(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

// ---- A shelf ---------------------------------------------------------------------------------------------------------------

/** Room above a shelf, as part two's shelves and `VideoShelf` have it (`.shelf2`). */
private val SHELF_TOP = 12.dp

/** Room under a shelf, before the feed's first card. */
private val SHELF_BOTTOM = 12.dp

/**
 * A shelf of cards (spec 5.0, 5.2): what `VideoShelf` draws (the heading, a row that scrolls sideways, 16 dp in from each side, 12 dp
 * apart, the platform's arrows and wheel on the desktop), with each card's title and picture taken through DeArrow
 * ([rememberBranded]) inside the row's own item, which `VideoShelf` has no place for. A tap calls [onOpen] with the original item;
 * a hold, a right click and the screen reader's action call [onMore]. [watched] gives a video's watched fraction by id,
 * [downloaded] says whether a video is downloaded, [cardWidth] is 200 dp: a compact shelf above the feed.
 */
@Composable
internal fun HomeShelf(
    title: String,
    items: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    watched: Map<String, Float> = emptyMap(),
    cardWidth: Dp = SHELF_CARD_WIDTH,
    downloaded: (String) -> Boolean = { false },
) {
    val keys = remember(items) { uniqueKeys(items) }
    val rowState = rememberLazyListState()
    Column(modifier.padding(top = SHELF_TOP, bottom = SHELF_BOTTOM)) {
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

/** The compact shelf's card: 200 dp wide (a feed card is the page's width). */
private val SHELF_CARD_WIDTH = 200.dp

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

package com.cosmotune.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.ui.components.LocalShelfRowChrome
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.playingShelfItemMatcher
import com.cosmotune.music.ui.components.rememberShelfFlingBehavior
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.music.ui.screens.localizeCardSubtitle
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle

/*
 * Cards and shelves (DESIGN_SYSTEM.md 4.7): a square cover, or a circle for an artist, with two lines of title and a line of meta
 * under it, and a row of them under a heading.
 */

/**
 * A card (DESIGN_SYSTEM.md 4.7): the cover [width] square, 12 dp corners, or a circle when [circle]; 8 dp under it the title on up to
 * two lines (one, centred, under a circle) and [subtitle] on one. No shadow, no container. A [playing] card shows the equaliser over
 * the corner of its cover and its title in the accent. Hold (or right click) calls [onLongPress].
 */
@Composable
fun CosmoCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = BlockMetrics.SHELF_CARD_DP.dp,
    circle: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    playing: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val align = if (circle) TextAlign.Center else TextAlign.Start
    Column(
        modifier
            .width(width)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
        horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box {
            if (circle) CircleCover(imageUrl, width, shadow = false)
            else CoverImage(imageUrl, width, CosmoRadii.rMd, shadow = false)
            if (playing) {
                // White bars on a dark plate: the bars sit on a picture, whose colours the palette does not know.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(PLAYING_PLATE)
                        .clip(RoundedCornerShape(CosmoRadii.rSm))
                        .drawBehind { drawRect(Color.Black.copy(alpha = PLAYING_PLATE_ALPHA)) },
                    contentAlignment = Alignment.Center,
                ) { CosmoEqualiser(playing = true, color = Color.White) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = type.forText(type.rowTitle, title),
            color = Color(if (playing) palette.accentStrong else palette.textPrimary),
            maxLines = if (circle) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = type.forText(type.meta, subtitle),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
            )
        }
    }
}

private val PLAYING_PLATE = 28.dp
private const val PLAYING_PLATE_ALPHA = 0.52f

/**
 * A shelf (DESIGN_SYSTEM.md 4.7): 32 dp of room above, the heading ([title] and [subtitle], "Show all" when [onShowAll] is set), and a
 * row of cards under it, 16 dp in from each side and 12 dp apart, their tops on one line. Every card is 148 dp, or [cardWidth];
 * [leadFirst] makes the first one the big one (168 dp). [circles] draws artists, at 112 dp. The card of the song that is
 * [currentSong] shows as playing while [isPlaying]. Titles and strap lines the feed sends in English are shown in the language of
 * the app, as the old shelves did.
 */
@Composable
fun CosmoShelf(
    title: String,
    items: List<ShelfItem>,
    onItemClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
    leadFirst: Boolean = false,
    cardWidth: Dp? = null,
    circles: Boolean = false,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    Column(modifier.padding(top = CosmoSpacing.s7)) {
        CosmoSectionHeader(
            title = localizeShelfTitle(title),
            subtitle = localizeShelfSubtitle(subtitle.orEmpty()).takeIf { it.isNotBlank() },
            onShowAll = onShowAll,
        )
        // The row is wrapped in the platform's shelf chrome (the desktop's hover arrows, drag and wheel) exactly as the old
        // `ShelfRow` does; `ShelfRow` itself has no `verticalAlignment`, and the lead card is taller than the others.
        val rowState = rememberLazyListState()
        // The card that was played, not every namesake: see [playingShelfItemMatcher].
        val isCurrentItem = playingShelfItemMatcher(items, currentSong)
        LocalShelfRowChrome.current.Wrap(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(CosmoSpacing.gutter),
                verticalAlignment = Alignment.Top,
                // A fling settles on a card's leading edge, as every shelf does (BC-32).
                flingBehavior = rememberShelfFlingBehavior(rowState),
            ) {
                itemsIndexed(items, key = { index, item -> shelfKey(item, index) }) { index, item ->
                    val width = cardWidth ?: when {
                        leadFirst && index == 0 -> BlockMetrics.LEAD_CARD_DP.dp
                        circles -> BlockMetrics.SMALL_CARD_DP.dp
                        else -> BlockMetrics.SHELF_CARD_DP.dp
                    }
                    CosmoCard(
                        title = item.title,
                        imageUrl = item.thumbnailUrl,
                        onClick = { onItemClick(item) },
                        subtitle = item.subtitle.takeIf { it.isNotBlank() }?.let { localizeCardSubtitle(it) },
                        width = width,
                        circle = circles,
                        onLongPress = onItemLongPress?.let { held -> { held(item) } },
                        playing = isPlaying && isCurrentItem(item),
                    )
                }
            }
        }
    }
}

/** A card's identity in its row: its browse or video id, else its title, and its place (a feed can repeat an item). */
internal fun shelfKey(item: ShelfItem, index: Int): String = "${item.browseId ?: item.videoId ?: item.title}#$index"

/** Whether [item] is the song that is [song]: the same video, or the same title and artist (the old shelves' rule). */
internal fun ShelfItem.isSong(song: Song?): Boolean {
    song ?: return false
    if (videoId == null) return false
    return videoId == song.videoId || (title == song.title && subtitle == song.artist)
}

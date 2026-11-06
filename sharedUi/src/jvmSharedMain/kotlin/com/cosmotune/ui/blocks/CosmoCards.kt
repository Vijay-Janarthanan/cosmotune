package com.cosmotune.ui.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.ui.orbit.OrbitPortrait
import com.cosmotune.ui.orbit.OrbitRecord
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitShelfRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.playingShelfItemMatcher
import com.cosmotune.music.ui.screens.localizeCardSubtitle
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle

/*
 * Cards and shelves in Orbit: an album or a playlist is a record (the cover as a disc, the title and a line under it), an artist a
 * round portrait with the name under it, and a shelf is a row of them under a section heading.
 */

/**
 * A card in Orbit: [OrbitRecord] (the cover as a disc [width] across, the title on up to two lines and [subtitle] on one), or, when
 * [circle], [OrbitPortrait] (an artist's round photo and the name, centred). No play moon: a tap opens the card, a hold or a right
 * click calls [onLongPress]. A [playing] card has the peach orbit round its disc, its moon circling, clear of the cover.
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
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(modifier.width(width).semantics(mergeDescendants = true) { role = Role.Button }) {
        if (circle) {
            OrbitPortrait(title, imageUrl, width, fonts, look, onClick = onClick, onLongClick = onLongPress)
        } else {
            OrbitRecord(title, subtitle.orEmpty(), imageUrl, fonts, look, onClick = onClick, onPlay = null, onLongClick = onLongPress, size = width)
        }
        if (playing) {
            // The orbit stands just outside the disc, so nothing is drawn on the cover; the card keeps its size.
            Box(Modifier.align(Alignment.TopCenter).size(width).wrapContentSize(unbounded = true)) {
                OrbitRing(width + 12.dp, look.accent, progress = null, spinning = true, stroke = 1.5.dp, moon = 3.dp)
            }
        }
    }
}

/**
 * A shelf in Orbit: 32 dp of room above, the section heading ([CosmoSectionHeader]: [title] over [subtitle], "Show all" when
 * [onShowAll] is set), and a row of [CosmoCard]s under it in the platform's shelf chrome ([OrbitShelfRow]), 18 dp apart, settling on a
 * card's edge. Every card is 148 dp, or [cardWidth]; [leadFirst] makes the first one the big one (168 dp). [circles] draws artists,
 * at 112 dp. The card of the song that is [currentSong] shows as playing while [isPlaying]. Titles and strap lines the feed sends in
 * English are shown in the language of the app.
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
        // The card that was played, not every namesake: see [playingShelfItemMatcher].
        val isCurrentItem = playingShelfItemMatcher(items, currentSong)
        OrbitShelfRow(CosmoSpacing.margin, 18.dp) {
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

/** A card's identity in its row: its browse or video id, else its title, and its place (a feed can repeat an item). */
internal fun shelfKey(item: ShelfItem, index: Int): String = "${item.browseId ?: item.videoId ?: item.title}#$index"

/** Whether [item] is the song that is [song]: the same video, or the same title and artist (the old shelves' rule). */
internal fun ShelfItem.isSong(song: Song?): Boolean {
    song ?: return false
    if (videoId == null) return false
    return videoId == song.videoId || (title == song.title && subtitle == song.artist)
}

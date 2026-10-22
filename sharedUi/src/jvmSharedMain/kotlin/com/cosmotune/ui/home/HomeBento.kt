package com.cosmotune.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.Bento
import com.cosmotune.design.HomePlan
import com.cosmotune.ui.blocks.CosmoMixTile
import com.cosmotune.ui.blocks.isSong
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle

/*
 * The bento at the top of Home (`screens-v2.css` `.bento2`, `screens-v3.css` `.dh-bento`): the hero, and the mixes beside or under it.
 * Only the hero and the mixes are drawn; the stat and live-room tiles of the boards wait for the data they show.
 *
 * On the phone it is four columns of rows of 80 dp: the hero across all four and two rows, and the mixes (two columns, two rows
 * each) side by side under it. On a wide page it is twelve columns of rows of 104 dp: the hero seven columns and four rows, and the
 * mixes (five columns, two rows each) stacked at its side. The sizes are [Bento]'s.
 */

/** The room above the bento: `.bento2` pads 14 dp at the top, `.dh-bento` is 16 dp below the mood row. */
private val PHONE_TOP = 14.dp
private val WIDE_TOP = 16.dp

/**
 * The bento of a page [pageWidth] wide: [hero] (an item of [heroShelf]), then [mixes]. A tap on a mix calls [onOpenShelf] with the
 * place of its shelf in the feed; a hold opens the menu of the shelf's first item.
 */
@Composable
internal fun HomeBento(
    hero: ShelfItem,
    heroShelf: HomeShelf,
    mixes: List<HomePlan.Mix>,
    pageWidth: Dp,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onOpenShelf: (Int) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val grid = Bento.gridFor(pageWidth.value)
    val wide = grid === Bento.WIDE
    val heroWidth = grid.heroWidth(pageWidth.value).dp
    val heroHeight = grid.heroHeight().dp
    val mixWidth = grid.mixWidth(pageWidth.value).dp
    val mixHeight = grid.mixHeight().dp
    val gutter = grid.gutterDp.dp
    val margin = grid.marginDp.dp

    val tag = localizeShelfTitle(heroShelf.title)
    val heroTile: @Composable () -> Unit = {
        HomeHero(
            item = hero,
            tag = tag,
            playing = isPlaying && hero.isSong(currentSong),
            onClick = { onItemClick(hero, heroShelf.title) },
            onLongPress = onItemLongPress?.let { held -> { held(hero) } },
            metrics = if (wide) HeroMetrics.WIDE else HeroMetrics.PHONE,
            modifier = Modifier.width(heroWidth).height(heroHeight),
        )
    }
    val mixTile: @Composable (HomePlan.Mix) -> Unit = { mix ->
        key(mix.shelfIndex) {
            val shelf = mix.shelf
            val covers = remember(shelf) { shelf.items.take(MIX_COVERS).map { it.thumbnailUrl } }
            CosmoMixTile(
                title = localizeShelfTitle(shelf.title),
                subtitle = localizeShelfSubtitle(shelf.subtitle).ifBlank { HomePlan.fallbackSubtitle(shelf) },
                covers = covers,
                onClick = { onOpenShelf(mix.shelfIndex) },
                modifier = Modifier.width(mixWidth).height(mixHeight),
                onLongPress = onItemLongPress?.let { held -> { held(shelf.items.first()) } },
            )
        }
    }

    if (wide) {
        Row(
            modifier.fillMaxWidth().padding(start = margin, top = WIDE_TOP, end = margin),
            horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            heroTile()
            Column(verticalArrangement = Arrangement.spacedBy(gutter)) { mixes.forEach { mixTile(it) } }
        }
    } else {
        Column(
            modifier.fillMaxWidth().padding(start = margin, top = PHONE_TOP, end = margin),
            verticalArrangement = Arrangement.spacedBy(gutter),
        ) {
            heroTile()
            if (mixes.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(gutter)) { mixes.forEach { mixTile(it) } }
        }
    }
}

/** A mix fans its first three covers (`.fan`). */
private const val MIX_COVERS = 3

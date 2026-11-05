package com.cosmotune.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.browse.BrowseSplit
import com.cosmotune.design.HomePlan
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.kit.KitChipRow
import com.cosmotune.ui.kit.KitQuickTile
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingShelfItemMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_home
import com.cosmotune.music.sharedui.resources.cosmo_moods
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.music.ui.components.ShelfRow
import com.cosmotune.music.ui.components.SignInBanner
import com.cosmotune.music.ui.components.feedMoreSkeleton
import com.cosmotune.music.ui.components.feedSkeleton
import com.cosmotune.music.ui.components.recentlyPlayedSkeleton
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle
import org.jetbrains.compose.resources.stringResource

/**
 * A link of Home's quick-access grid that is not an item of the feed (Liked songs, Downloads): the host knows where it goes.
 */
class HomeQuickLink(val title: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Home (DESIGN_SYSTEM.md 6.1): one lazy column inside the pull to refresh, with no hero and nothing decorated. A light "Home"; the
 * sign-in prompt while signed out; the two-column quick-access grid ([quickLinks], then the first items of the feed); then every shelf
 * of the feed, square cards with two lines of title, or pages of rows for a long list of songs; and, a little way down, the moods and
 * the discovery controls.
 *
 * The parameters are [com.cosmotune.music.ui.screens.HomeScreen]'s, then [browse] (Explore's moods, which the chip row shows; null
 * hides the row) and [quickLinks]. [HomeSwitch] is the one caller. [title] and [leadHero] are the old screen's: the page names itself,
 * and it has no hero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CosmoHome(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem, String) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String?,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    recentlyPlayedLoading: Boolean = false,
    leadHero: Boolean = true,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    discoveryControls: (@Composable () -> Unit)? = null,
    browse: CosmoBrowse? = null,
    quickLinks: List<HomeQuickLink> = emptyList(),
) {
    val moods = remember(browse?.state) { (browse?.state as? UiState.Success)?.let { BrowseSplit.moods(it.data) }.orEmpty() }

    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The blocks fix their own 16 dp margin; an expanded window moves them in to the 28 dp of the desktop's pages.
            val extra = when {
                maxWidth >= EXPANDED_DP -> 12.dp
                else -> 0.dp
            }
            val quickColumns = when {
                maxWidth >= LARGE_DP -> 4
                maxWidth >= MEDIUM_DP -> 3
                else -> 2
            }
            val signIn = onSignIn

            // What stands a little way down the page: never the first thing on it (R4), and on it whatever the feed sends.
            fun LazyListScope.lower() {
                if (moods.isNotEmpty() && browse != null) {
                    item(key = "home:moods") { HomeMoodRow(moods, extra, browse.onOpen) }
                }
                if (discoveryControls != null) item(key = "home:discovery") { Column(Modifier.padding(horizontal = extra, vertical = 8.dp)) { discoveryControls() } }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "home:header") { CosmoPageHeader(title = stringResource(Res.string.cosmo_home), modifier = Modifier.padding(horizontal = extra)) }
                if (!signedIn && signIn != null) {
                    item(key = "home:sign-in") {
                        SignInBanner(onSignIn = signIn, modifier = Modifier.padding(horizontal = extra).padding(top = 8.dp))
                    }
                }
                when (state) {
                    is UiState.Loading -> {
                        if (quickLinks.isNotEmpty()) item(key = "home:quick") { HomeQuickGrid(quickLinks, emptyList(), quickColumns, extra, onItemClick, onItemLongPress) }
                        if (recentlyPlayedLoading) recentlyPlayedSkeleton(listLayout = true)
                        feedSkeleton(firstIsHero = false)
                        lower()
                    }
                    is UiState.Error -> {
                        if (quickLinks.isNotEmpty()) item(key = "home:quick") { HomeQuickGrid(quickLinks, emptyList(), quickColumns, extra, onItemClick, onItemLongPress) }
                        item(key = "home:error") {
                            MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                        }
                        lower()
                    }
                    is UiState.Success -> {
                        val quick = HomePlan.quickItems(state.data, HomePlan.QUICK_MAX - quickLinks.size)
                        if (quickLinks.isNotEmpty() || quick.isNotEmpty()) {
                            item(key = "home:quick") {
                                val titled = remember(state.data, quick) { quick.map { item -> item to (state.data.firstOrNull { item in it.items }?.title.orEmpty()) } }
                                HomeQuickGrid(quickLinks, titled, quickColumns, extra, onItemClick, onItemLongPress)
                            }
                        }
                        if (recentlyPlayedLoading) recentlyPlayedSkeleton(listLayout = true)
                        state.data.forEachIndexed { index, shelf ->
                            if (index == LOWER_AFTER_SHELVES) lower()
                            if (shelf.items.isNotEmpty()) item(key = shelf.title + index) {
                                if (!HomePlan.isRecents(state.data, index) && HomePlan.isSongPages(shelf)) {
                                    HomeSongPages(
                                        shelf = shelf,
                                        onItemClick = { onItemClick(it, shelf.title) },
                                        onItemLongPress = onItemLongPress,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.padding(horizontal = extra),
                                    )
                                } else {
                                    // CosmoShelf puts the feed's English titles and strap lines into the app's language itself.
                                    CosmoShelf(
                                        title = shelf.title,
                                        subtitle = shelf.subtitle,
                                        items = shelf.items,
                                        onItemClick = { onItemClick(it, shelf.title) },
                                        modifier = Modifier.padding(horizontal = extra),
                                        onItemLongPress = onItemLongPress,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                    )
                                }
                            }
                        }
                        if (state.data.size <= LOWER_AFTER_SHELVES) lower()
                        if (loadingMore) feedMoreSkeleton()
                    }
                }
            }
        }
    }

    if (onLoadMore != null && state is UiState.Success) {
        // Copied from HomeScreen's load-more effect (HomeScreen.kt, the `onLoadMore != null && state is UiState.Success` block at the end
        // of HomeScreen), with its comment: the feed pages the same way in both looks.
        val loadMore by rememberUpdatedState(onLoadMore)
        // Re-checked on every layout change, rather than on the rising edge of
        // "the tail is in view". A page that appends only a shelf or two leaves
        // the list still near its end, so an edge-triggered effect would never
        // fire a second time: the feed dead-ended at the bottom with no
        // skeleton and no request in flight to explain it. Restarting on
        // [loadingMore] re-checks the moment a page settles, so the next one is
        // asked for while the tail is still on screen to show it loading.
        LaunchedEffect(listState, loadingMore) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }
}

/** How many shelves stand above the moods and the discovery controls. */
private const val LOWER_AFTER_SHELVES = 3

/** The window size classes of DESIGN_SYSTEM.md 4.17. */
private val MEDIUM_DP = 600.dp
private val EXPANDED_DP = 840.dp
private val LARGE_DP = 1200.dp

// ---- the quick-access grid ------------------------------------------------------------------------------------------------------

/** The shortcut grid (DESIGN_SYSTEM.md 4.9): [links] first, then [items] with the shelf each came from; a short last row keeps its place. */
@Composable
private fun HomeQuickGrid(
    links: List<HomeQuickLink>,
    items: List<Pair<ShelfItem, String>>,
    columns: Int,
    extra: Dp,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    val tiles: List<Any> = links + items
    Column(
        Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin + extra, end = CosmoSpacing.margin + extra, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in tiles.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tile in row) {
                    when (tile) {
                        is HomeQuickLink -> KitQuickTile(tile.title, null, tile.onClick, Modifier.weight(1f), icon = tile.icon)
                        is Pair<*, *> -> {
                            val item = tile.first as ShelfItem
                            val shelf = tile.second as String
                            KitQuickTile(item.title, item.thumbnailUrl, { onItemClick(item, shelf) }, Modifier.weight(1f), onLongClick = onItemLongPress?.let { held -> { held(item) } })
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- the moods ------------------------------------------------------------------------------------------------------------------

/** The moods as chips, none chosen, under their heading; a tap opens that mood's page. */
@Composable
private fun HomeMoodRow(moods: List<MoodGenre>, extra: Dp, onOpen: (MoodGenre) -> Unit) {
    Column(Modifier.padding(top = CosmoSpacing.s7)) {
        CosmoSectionHeader(title = stringResource(Res.string.cosmo_moods), modifier = Modifier.padding(horizontal = extra))
        KitChipRow(
            labels = moods.map { it.title },
            selectedIndex = -1,
            onSelect = { onOpen(moods[it]) },
            contentPadding = PaddingValues(horizontal = CosmoSpacing.margin + extra),
        )
    }
}

// ---- pages of songs -------------------------------------------------------------------------------------------------------------

private const val SONGS_PER_PAGE = 4

/** Each page is as wide as the row less 48 dp, so the next page shows beside it... */
private val SONG_PAGE_INSET = 48.dp

/** ...but never wider than a song row has any use for. */
private val SONG_PAGE_MAX = 400.dp

/**
 * A long list of songs (the feed's "Quick picks"): pages of four rows side by side in a row, the next page showing beside the one in
 * view (DESIGN_SYSTEM.md 6.1).
 */
@Composable
private fun HomeSongPages(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = CosmoSpacing.s7)) {
        CosmoSectionHeader(
            title = localizeShelfTitle(shelf.title),
            subtitle = localizeShelfSubtitle(shelf.subtitle).takeIf { it.isNotBlank() },
        )
        BoxWithConstraints {
            val pageWidth = minOf(maxWidth - SONG_PAGE_INSET, SONG_PAGE_MAX)
            val pages = remember(shelf.items) { shelf.items.chunked(SONGS_PER_PAGE) }
            val isCurrentItem = playingShelfItemMatcher(shelf.items, currentSong)
            ShelfRow(contentPadding = PaddingValues(horizontal = CosmoSpacing.margin), horizontalArrangement = Arrangement.spacedBy(CosmoSpacing.gutter)) {
                itemsIndexed(pages, key = { index, _ -> "song-page:$index" }) { _, page ->
                    Column(Modifier.width(pageWidth)) {
                        page.forEach { item ->
                            val current = isCurrentItem(item)
                            CosmoListRow(
                                title = item.title,
                                onClick = { onItemClick(item) },
                                subtitle = item.subtitle.takeIf { it.isNotBlank() },
                                leading = CosmoRowLeading.Cover(item.thumbnailUrl),
                                isCurrent = current,
                                isPlaying = current && isPlaying,
                                onLongPress = onItemLongPress?.let { held -> { held(item) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

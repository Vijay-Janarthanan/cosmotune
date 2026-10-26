package com.cosmotune.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.browse.BrowseSplit
import com.cosmotune.design.Bento
import com.cosmotune.design.BlockMetrics
import com.cosmotune.design.HomeGreeting
import com.cosmotune.design.HomePlan
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoMoodChip
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.blocks.isSong
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingShelfItemMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_home_afternoon
import com.cosmotune.music.sharedui.resources.cosmo_home_evening
import com.cosmotune.music.sharedui.resources.cosmo_home_late
import com.cosmotune.music.sharedui.resources.cosmo_home_morning
import com.cosmotune.music.sharedui.resources.cosmo_moods
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.music.ui.components.ShelfRow
import com.cosmotune.music.ui.components.ShimmerBox
import com.cosmotune.music.ui.components.SignInBanner
import com.cosmotune.music.ui.components.feedMoreSkeleton
import com.cosmotune.music.ui.components.feedSkeleton
import com.cosmotune.music.ui.components.recentlyPlayedSkeleton
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/**
 * Home in the new look (`V2.home`, `v2/v2.js:49-65`, `screens-v2.css:18-67`; the wide page `V3.deskHome`, `screens-v3.css:298-305`).
 *
 * One lazy column inside the pull to refresh: the time-of-day heading, the sign-in prompt while signed out, the mood chips, the
 * bento (the hero and up to two mixes), and then every shelf of the feed. A page 840 dp wide or wider gets the desktop's layout:
 * margins of 28, the bento on twelve columns, the heading in `display(DISPLAY_L)`.
 *
 * The parameters are [com.cosmotune.music.ui.screens.HomeScreen]'s, then [browse] (Explore's moods, which the chip row shows; null
 * hides the row). [HomeSwitch] is the one caller. [leadHero] only chooses the loading skeleton here: the new Home always has a hero.
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
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    // Explore's moods: still loading (skeleton chips), arrived (chips), or none to show (an error, an empty list or no browse).
    val browseState = browse?.state
    val moods = remember(browseState) { (browseState as? UiState.Success)?.let { BrowseSplit.moods(it.data) }.orEmpty() }
    val moodsLoading = browseState is UiState.Loading
    val showMoods = browse != null && (moodsLoading || moods.isNotEmpty())
    val plan = remember(state) { (state as? UiState.Success)?.data?.let { HomePlan.of(it) } }

    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageWidth = maxWidth
            val grid = Bento.gridFor(pageWidth.value)
            val wide = grid === Bento.WIDE
            val inset = grid.marginDp.dp
            // The blocks that fix their own 16 dp margin (the heading, the shelves, the prompt) are moved in by what the page wants more.
            val extra = (inset - CosmoSpacing.margin).coerceAtLeast(0.dp)
            val signIn = onSignIn
            val showBanner = !signedIn && signIn != null
            val showBento = plan?.hero != null
            val firstShelfItem = HomePlan.firstShelfItemIndex(
                banner = showBanner,
                moods = showMoods,
                bento = showBento,
                recentsSkeleton = state is UiState.Success && recentlyPlayedLoading,
            ) + if (discoveryControls != null) 1 else 0
            val openShelf: (Int) -> Unit = { shelfIndex ->
                scope.launch {
                    val target = firstShelfItem + shelfIndex
                    if (reduced) listState.scrollToItem(target) else listState.animateScrollToItem(target)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // The heading sits higher than the other tabs' first row: pull the whole feed up by [HOME_TITLE_LIFT] with it
                // (as HomeScreen does).
                contentPadding = if (title != null) {
                    PaddingValues(
                        top = (contentPadding.calculateTopPadding() - HOME_TITLE_LIFT).coerceAtLeast(0.dp),
                        bottom = contentPadding.calculateBottomPadding(),
                    )
                } else {
                    contentPadding
                },
            ) {
                item(key = "home:header") { HomeHeader(overline = title, wide = wide, extra = extra) }
                if (discoveryControls != null) item(key = "home:discovery") { discoveryControls() }
                if (showBanner) {
                    item(key = "home:sign-in") {
                        SignInBanner(onSignIn = signIn, modifier = Modifier.padding(horizontal = extra).padding(bottom = 8.dp))
                    }
                }
                if (showMoods) {
                    item(key = "home:moods") {
                        HomeMoodRow(moods = if (moodsLoading) null else moods, inset = inset, onOpen = browse.onOpen)
                    }
                }
                when (state) {
                    is UiState.Loading -> {
                        if (recentlyPlayedLoading) {
                            recentlyPlayedSkeleton(listLayout = true)
                            // Recents owns the leading place while its request is pending, so the feed behind it starts with ordinary
                            // shelf placeholders rather than another hero.
                            feedSkeleton(firstIsHero = false)
                        } else {
                            feedSkeleton(firstIsHero = leadHero)
                        }
                    }
                    is UiState.Error -> item(key = "home:error") {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        val hero = plan?.hero
                        if (plan != null && hero != null) {
                            item(key = "home:bento") {
                                HomeBento(
                                    hero = hero,
                                    heroShelf = state.data[plan.heroShelfIndex],
                                    mixes = plan.mixes,
                                    pageWidth = pageWidth,
                                    onItemClick = onItemClick,
                                    onItemLongPress = onItemLongPress,
                                    onOpenShelf = openShelf,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                )
                            }
                        }
                        if (recentlyPlayedLoading) recentlyPlayedSkeleton(listLayout = true)
                        state.data.forEachIndexed { index, shelf ->
                            item(key = shelf.title + index) {
                                if (HomePlan.isRecents(state.data, index)) {
                                    HomeRecents(
                                        shelf = shelf,
                                        onItemClick = { onItemClick(it, shelf.title) },
                                        onItemLongPress = onItemLongPress,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.padding(horizontal = extra),
                                    )
                                } else {
                                    // CosmoShelf puts the feed's English titles and strap lines into the app's language itself.
                                    val displayedItems = HomePlan.shelfItems(state.data, plan, index)
                                    if (displayedItems.isNotEmpty()) {
                                    CosmoShelf(
                                        title = shelf.title,
                                        subtitle = shelf.subtitle,
                                        items = displayedItems,
                                        onItemClick = { onItemClick(it, shelf.title) },
                                        modifier = Modifier.padding(horizontal = extra),
                                        onItemLongPress = onItemLongPress,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                    )
                                    }
                                }
                            }
                        }
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

/** How far the Listen now heading and feed sit above the shared top inset (`HomeScreen.HOME_TITLE_LIFT`). */
private val HOME_TITLE_LIFT = 28.dp

// ---- the heading ----------------------------------------------------------------------------------------------------------------

/** The weekday and the period of the day, which is all the heading depends on: it is only replaced when one of them changes. */
private data class DayPart(val period: HomeGreeting.Period, val dayOfWeek: Int)

private fun dayPartNow(): DayPart {
    val now = java.time.LocalDateTime.now()
    return DayPart(HomeGreeting.period(now.hour), now.dayOfWeek.value)
}

/** How often the heading looks at the clock again: a page left open through midnight turns into "Late Wednesday". */
private const val DAY_PART_REFRESH_MS = 60_000L

/** "Tuesday morning", "Tuesday afternoon", "Tuesday evening" or "Late Tuesday" (`cosmo_home_*`), the weekday in the language of the device. */
@Composable
private fun rememberGreeting(): String {
    val now by produceState(initialValue = dayPartNow()) {
        while (true) {
            delay(DAY_PART_REFRESH_MS)
            value = dayPartNow()
        }
    }
    val locale = Locale.getDefault()
    val day = java.time.DayOfWeek.of(now.dayOfWeek).getDisplayName(java.time.format.TextStyle.FULL, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return when (now.period) {
        HomeGreeting.Period.MORNING -> stringResource(Res.string.cosmo_home_morning, day)
        HomeGreeting.Period.AFTERNOON -> stringResource(Res.string.cosmo_home_afternoon, day)
        HomeGreeting.Period.EVENING -> stringResource(Res.string.cosmo_home_evening, day)
        HomeGreeting.Period.LATE -> stringResource(Res.string.cosmo_home_late, day)
    }
}

/**
 * The page heading: the greeting, with [overline] (the phone's "Listen now") above it. On a wide page the title is `display(DISPLAY_L)`
 * with 22 dp above it (`.dh-head`; the header's own 14 dp and 8 more). There is no strapline: the board's mood sentence needs the recommender.
 */
@Composable
private fun HomeHeader(overline: String?, wide: Boolean, extra: Dp) {
    CosmoPageHeader(
        title = rememberGreeting(),
        modifier = Modifier.padding(start = extra, end = extra, top = if (wide) WIDE_HEAD_TOP else 0.dp),
        overline = overline,
        large = wide,
    )
}

/** `.dh-head { margin-top: 22px }` less the 14 dp the page header already keeps above its title. */
private val WIDE_HEAD_TOP = 8.dp

// ---- the mood chips -------------------------------------------------------------------------------------------------------------

/** How many placeholder chips stand in while Explore's moods are on the way. */
private const val MOOD_SKELETONS = 4
private val MOOD_SKELETON_WIDTH = 96.dp
private val MOOD_SKELETON_RADIUS = 13.dp

/**
 * The mood row (`.moods2`: gap 8, padding 16 16 4, scrolling sideways): a chip per mood, none selected, a tap opens that mood.
 * [moods] null shows four placeholders of the chip's height. The row is named for a screen reader.
 */
@Composable
private fun HomeMoodRow(moods: List<MoodGenre>?, inset: Dp, onOpen: (MoodGenre) -> Unit) {
    val label = stringResource(Res.string.cosmo_moods)
    ShelfRow(
        contentPadding = PaddingValues(start = inset, top = 16.dp, end = inset, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        if (moods == null) {
            items(MOOD_SKELETONS, key = { "mood-skeleton:$it" }) {
                // The chip reserves a 48 dp touch target around its 44 dp: the placeholder is centred in the same height.
                Column(Modifier.height(48.dp), verticalArrangement = Arrangement.Center) {
                    ShimmerBox(
                        modifier = Modifier.width(MOOD_SKELETON_WIDTH).height(BlockMetrics.MOOD_CHIP_HEIGHT_DP.dp),
                        shape = RoundedCornerShape(MOOD_SKELETON_RADIUS),
                    )
                }
            }
        } else {
            items(moods, key = { "${it.browseId}|${it.params}" }) { mood ->
                CosmoMoodChip(label = mood.title, selected = false, onClick = { onOpen(mood) })
            }
        }
    }
}

// ---- Recents --------------------------------------------------------------------------------------------------------------------

private const val RECENT_TRACKS_PER_PAGE = 4

/** Each page of Recents is as wide as the row less 48 dp, so the next page shows beside it... */
private val RECENTS_PAGE_INSET = 48.dp

/** ...but never wider than the old screen's cap (`HomeScreen`'s `trackColumnWidth`): a track row has no use for more room. */
private val RECENTS_PAGE_MAX = 400.dp

/**
 * Recents as the old screen drew it in list mode: pages of four rows (cover, title, artist) side by side in a row, the next page
 * showing beside the one in view. The heading has no list/grid switch: the new Home draws it as a list.
 */
@Composable
private fun HomeRecents(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 22.dp)) {
        CosmoSectionHeader(
            title = localizeShelfTitle(shelf.title),
            subtitle = localizeShelfSubtitle(shelf.subtitle).takeIf { it.isNotBlank() },
        )
        BoxWithConstraints {
            val pageWidth = minOf(maxWidth - RECENTS_PAGE_INSET, RECENTS_PAGE_MAX)
            val pages = remember(shelf.items) { shelf.items.chunked(RECENT_TRACKS_PER_PAGE) }
            val isCurrentItem = playingShelfItemMatcher(shelf.items, currentSong)
            ShelfRow(
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(CosmoSpacing.gutter),
            ) {
                itemsIndexed(pages, key = { index, _ -> "recents-page:$index" }) { _, page ->
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

package com.cosmotune.video.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.feed.SubscriptionFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.home.HomePlanner
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_list_error
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.resources.video_shelf_continue
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoCard
import com.cosmotune.video.ui.blocks.VideoCardBody
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.blocks.videoSolidGround
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shorts.ShortsShelf
import com.cosmotune.video.ui.shorts.openShort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil

/**
 * Video's Home (plan task Y-17, spec 5.2), laid out as one continuous feed the way the video apps do it: a chip row that stays at the
 * top (All, Shorts, then the kiosks the source offers), and under it a single vertical list of video cards, the Shorts shelf standing
 * in it after the fourth card. There is no greeting and no stack of carousels. "Continue watching" is a compact shelf above the
 * feed, only while some video is part-watched; the channels you follow are the Subscriptions page's.
 *
 * What the feed holds: on All, the account's own home when a Google account is signed in and gives videos, else one video in turn from
 * the newest of your subscriptions, the videos related to what you watched last, and the first kiosk ([HomePlanner.stream]); on a
 * kiosk's chip, that kiosk's cards. The kiosk's later pages (the account's, when it is the feed) load at the end of the list. A
 * first launch with no history and no subscriptions shows the kiosk alone. [HomeModel] holds the kiosk and the related lists.
 *
 * One lazy column on a phone: the cards run edge to edge, 16:9, with the channel's picture, the title in two lines, the meta line
 * and the "more" button under each. A [wide] page (840 dp and up, always the desktop) is a lazy grid of the same cards, as many
 * columns as fit 320 dp (two to five), 12 dp-rounded pictures, with the chip row above the grid. [contentPadding] is the shell's room
 * for its bars.
 *
 * Every card tap goes through the action handler (`actions.play(item, list)`: a video opens its watch page, a Short the pager with
 * the list's Shorts); "more", a hold and a right click open the video sheet. Titles and pictures are DeArrow's when on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoHome(contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val actions = rememberVideoActions()
    val scope = rememberCoroutineScope()

    val settings by graph.settings.settings.collectAsState()
    val hideShorts = settings.hideShorts
    val history by graph.library.history.collectAsState()
    val feedItems by remember(graph) { graph.feed.state.map { it.items }.distinctUntilChanged() }.collectAsState(graph.feed.state.value.items)
    val followsChannels by remember(graph) { graph.library.subscriptions.map { it.isNotEmpty() }.distinctUntilChanged() }
        .collectAsState(graph.library.subscriptions.value.isNotEmpty())
    val downloaded by rememberDownloadedIds()

    // The last three videos watched, whose related lists the feed draws on.
    val recent = remember(history) { HomePlanner.recentWatches(history) }
    val recentIds = remember(recent) { recent.map { it.item.id } }

    // The kiosks, the selected kiosk's first page and the related lists: what the cache holds is there in the first frame (the list
    // is back as it was when Home returns from a video), the rest comes from the source.
    val regionKey = "${settings.region.orEmpty()}|${settings.contentLanguage.orEmpty()}"
    val model = remember(graph) { HomeModel(graph.source, scope).also { it.restore(regionKey, settings.homeKiosk, recentIds) } }
    LaunchedEffect(model, regionKey) { model.start(regionKey, graph.settings.settings.value.homeKiosk) }

    // The account's own home, while a Google account is signed in.
    val signedIn = graph.account.state.collectAsState().value is AccountState.SignedIn
    val accountFeed = remember(graph, signedIn) { if (signedIn) AccountHomeFeed(graph.account, scope) else null }
    LaunchedEffect(accountFeed) { accountFeed?.start() }

    // The feed catches up when Home opens (a refresh within 10 minutes of the last is skipped by the feed itself).
    LaunchedEffect(graph, followsChannels) { if (followsChannels) refreshFeed(graph.feed) }

    // Continue watching; the related lists of the last three watches are fetched once per launch.
    val continueEntries = remember(history) { HomePlanner.continueWatching(history) }
    val continueItems = remember(continueEntries) { continueEntries.map { it.item } }
    val continueWatched = remember(continueEntries) {
        buildMap { for (entry in continueEntries) entry.item.watchedFraction(entry.positionMs, entry.durationMs)?.let { put(entry.item.id, it) } }
    }
    LaunchedEffect(model, recentIds) { model.loadRelated(recentIds) }

    val related = model.related
    val accountItems = accountFeed?.items.orEmpty()
    val subscriptionsShelf = remember(feedItems) { HomePlanner.subscriptionsShelf(feedItems) }
    val forYou = remember(recent, related, history) { HomePlanner.forYou(recent, related, history) }
    val kioskVideos = remember(model.items) { model.items.filterNot(ShortsFilter::isShort) }
    val shorts = remember(feedItems, related, recentIds, model.items, accountItems, hideShorts) {
        if (hideShorts) {
            emptyList()
        } else {
            val fromRelated = recentIds.flatMap { id -> related[id].orEmpty().mapNotNull { (it as? ListItem.Video)?.item } }
            HomePlanner.shorts(feedItems, fromRelated + accountItems.filter(ShortsFilter::isShort), model.items)
        }
    }

    // All is the mixed feed; a kiosk's chip is that kiosk alone.
    var all by rememberSaveable { mutableStateOf(true) }
    val stream = remember(all, accountItems, subscriptionsShelf, forYou, kioskVideos) {
        if (all) HomePlanner.stream(accountItems, subscriptionsShelf, forYou, kioskVideos) else kioskVideos
    }
    val accountIsFeed = all && accountItems.isNotEmpty()
    val phase = when {
        stream.isNotEmpty() -> KioskPhase.READY
        all && accountFeed != null && !accountFeed.loaded -> KioskPhase.LOADING
        else -> model.phase
    }
    val loadingMore = if (accountIsFeed) accountFeed?.loadingMore == true else model.loadingMore
    val loadMore: () -> Unit = { if (accountIsFeed) accountFeed?.loadMore() else model.loadMore() }

    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    val titleContinue = stringResource(Res.string.video_shelf_continue)
    val titleShorts = stringResource(Res.string.video_shorts)
    val titleError = stringResource(Res.string.video_list_error)
    val titleRetry = stringResource(Res.string.video_retry)
    val openShorts = { navigator.push(VideoRoute.Shorts(ShortsOrigin.Home)) }

    val chips: @Composable (Modifier) -> Unit = { modifier ->
        HomeChips(
            kiosks = model.kiosks,
            selectedId = if (all) null else model.selectedId,
            showShorts = !hideShorts,
            onAll = { all = true },
            onShorts = openShorts,
            onSelect = { kiosk ->
                all = false
                graph.settings.update { it.copy(homeKiosk = kiosk.id) }
                model.select(kiosk.id)
            },
            modifier = modifier,
        )
    }
    val continueShelf: @Composable (Modifier) -> Unit = { modifier ->
        HomeShelf(
            title = titleContinue, items = continueItems, modifier = modifier,
            onOpen = { actions.play(it, continueItems) }, onMore = { sheet = HomeSheet(it, continueItems) },
            watched = continueWatched, downloaded = { it in downloaded },
        )
    }
    val shortsShelf: @Composable (Modifier) -> Unit = { modifier ->
        ShortsShelf(
            title = titleShorts, items = shorts, modifier = modifier,
            onOpen = { navigator.openShort(it, ShortsOrigin.Home, shorts) }, onSeeAll = openShorts,
        )
    }
    val feed = HomeFeed(
        items = stream,
        phase = phase,
        loadingMore = loadingMore,
        hasContinue = all && continueItems.isNotEmpty(),
        hasShorts = shorts.isNotEmpty(),
    )
    val onOpen: (VideoItem) -> Unit = { actions.play(it, stream) }
    val onMore: (VideoItem) -> Unit = { sheet = HomeSheet(it, stream) }
    val isDownloaded: (VideoItem) -> Boolean = { it.id in downloaded }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (wide) {
            val columns = homeColumns(maxWidth)
            HomeFeedWide(feed, columns, contentPadding, chips, continueShelf, shortsShelf, onOpen, onMore, isDownloaded, titleError, titleRetry, model::retry, loadMore)
        } else {
            HomeFeedPhone(feed, contentPadding, chips, continueShelf, shortsShelf, onOpen, onMore, isDownloaded, titleError, titleRetry, model::retry, loadMore)
        }
    }

    sheet?.let { held ->
        VideoActionsSheet(
            item = held.item,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(held.item, action, VideoSheetContext.DEFAULT, list = held.list) },
            onDismiss = { sheet = null },
            downloaded = held.item.id in downloaded,
        )
    }
}

/** What the feed draws: the cards in order, the state of the load, and whether the two shelves stand in or above it. */
@Immutable
private class HomeFeed(
    val items: List<VideoItem>,
    val phase: KioskPhase,
    val loadingMore: Boolean,
    val hasContinue: Boolean,
    val hasShorts: Boolean,
)

/** A video whose menu is open, and the list its card stood in (a Short in it opens the pager with that list's Shorts). */
@Immutable
private class HomeSheet(val item: VideoItem, val list: List<VideoItem>)

/** The phone's feed: one lazy column, the chips sticking to the top, the cards edge to edge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeFeedPhone(
    feed: HomeFeed,
    contentPadding: PaddingValues,
    chips: @Composable (Modifier) -> Unit,
    continueShelf: @Composable (Modifier) -> Unit,
    shortsShelf: @Composable (Modifier) -> Unit,
    onOpen: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
    downloaded: (VideoItem) -> Boolean,
    errorText: String,
    retryText: String,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val keys = remember(feed.items) { uniqueKeys(feed.items) }
    val shortsAt = minOf(HomePlanner.SHORTS_AFTER - 1, feed.items.lastIndex)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        stickyHeader(key = "chips", contentType = "chips") { chips(Modifier.videoSolidGround()) }
        if (feed.hasContinue) item(key = "continue", contentType = "shelf") { continueShelf(Modifier) }
        when (feed.phase) {
            KioskPhase.LOADING -> items(count = SKELETON_CARDS, key = { "skeleton-$it" }, contentType = { "skeleton" }) {
                CardSkeleton(Modifier.padding(horizontal = VideoMetrics.MARGIN_DP.dp).padding(top = SKELETON_TOP, bottom = VideoMetrics.FEED_AFTER_DP.dp))
            }
            KioskPhase.FAILED -> item(key = "error", contentType = "error") {
                CosmoEmptyState(message = errorText, actionLabel = retryText, onAction = onRetry)
            }
            KioskPhase.READY -> {
                items(count = feed.items.size, key = { "feed:${keys[it]}" }, contentType = { "card" }) { index ->
                    val item = feed.items[index]
                    Column {
                        VideoCard(
                            item = rememberBranded(item),
                            onClick = { onOpen(item) },
                            onMore = { onMore(item) },
                            downloaded = downloaded(item),
                            modifier = if (index == 0) Modifier.padding(top = FIRST_CARD_TOP) else Modifier,
                        )
                        if (index == shortsAt && feed.hasShorts) shortsShelf(Modifier.padding(bottom = SHORTS_FEED_BOTTOM))
                    }
                }
                if (feed.loadingMore) {
                    item(key = "more", contentType = "skeleton") { CardSkeleton(Modifier.padding(horizontal = VideoMetrics.MARGIN_DP.dp)) }
                }
            }
        }
    }
    LaunchedEffect(listState, onLoadMore) {
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - LOAD_AHEAD_CARDS) onLoadMore()
        }
    }
}

/** The wide page's feed: the chips on top, then a lazy grid of [columns] columns that fills the page's width. */
@Composable
private fun HomeFeedWide(
    feed: HomeFeed,
    columns: Int,
    contentPadding: PaddingValues,
    chips: @Composable (Modifier) -> Unit,
    continueShelf: @Composable (Modifier) -> Unit,
    shortsShelf: @Composable (Modifier) -> Unit,
    onOpen: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
    downloaded: (VideoItem) -> Boolean,
    errorText: String,
    retryText: String,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val keys = remember(feed.items) { uniqueKeys(feed.items) }
    // The Shorts shelf stands after the fourth card, on a whole row so that no cell is left empty above it.
    val cut = if (feed.hasShorts) minOf(feed.items.size, ceil(HomePlanner.SHORTS_AFTER / columns.toDouble()).toInt() * columns) else feed.items.size
    val margin = VideoMetrics.DESKTOP_MARGIN_DP.dp
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        chips(Modifier.videoSolidGround().padding(horizontal = margin - VideoMetrics.MARGIN_DP.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = margin, end = margin, top = WIDE_FEED_TOP, bottom = contentPadding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(VideoMetrics.GRID_COL_GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(WIDE_ROW_GAP),
        ) {
            if (feed.hasContinue) {
                item(key = "continue", span = { GridItemSpan(maxLineSpan) }, contentType = "shelf") {
                    continueShelf(Modifier.bleedBy(margin).padding(horizontal = margin - VideoMetrics.MARGIN_DP.dp))
                }
            }
            when (feed.phase) {
                KioskPhase.LOADING -> items(count = columns * 2, key = { "skeleton-$it" }, contentType = { "skeleton" }) { CardSkeleton() }
                KioskPhase.FAILED -> item(key = "error", span = { GridItemSpan(maxLineSpan) }, contentType = "error") {
                    CosmoEmptyState(message = errorText, actionLabel = retryText, onAction = onRetry)
                }
                KioskPhase.READY -> {
                    items(count = cut, key = { "feed:${keys[it]}" }, contentType = { "card" }) { index -> WideCard(feed.items[index], onOpen, onMore, downloaded) }
                    if (feed.hasShorts) {
                        item(key = "shorts", span = { GridItemSpan(maxLineSpan) }, contentType = "shelf") {
                            shortsShelf(Modifier.bleedBy(margin).padding(horizontal = margin - VideoMetrics.MARGIN_DP.dp))
                        }
                    }
                    items(count = feed.items.size - cut, key = { "feed:${keys[cut + it]}" }, contentType = { "card" }) { index ->
                        WideCard(feed.items[cut + index], onOpen, onMore, downloaded)
                    }
                    if (feed.loadingMore) items(count = columns, key = { "more-$it" }, contentType = { "skeleton" }) { CardSkeleton() }
                }
            }
        }
    }
    LaunchedEffect(gridState, onLoadMore, columns) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - columns * 2) onLoadMore()
        }
    }
}

/** One card of the grid: the feed card of the phone without its margin, a picture rounded 12 dp, DeArrow's title and picture. */
@Composable
private fun WideCard(item: VideoItem, onOpen: (VideoItem) -> Unit, onMore: (VideoItem) -> Unit, downloaded: (VideoItem) -> Boolean) {
    val shown = rememberBranded(item)
    VideoCardBody(
        item = shown,
        onClick = { onOpen(item) },
        onMore = { onMore(item) },
        modifier = Modifier,
        watchedFraction = null,
        title = shown.title,
        downloaded = downloaded(item),
    )
}

/**
 * The columns of the wide feed: as many as fit [CARD_MIN_DP] (320) dp cards with the page's margins and gaps, from two to five, so
 * the grid always fills the width.
 */
internal fun homeColumns(width: Dp): Int {
    val usable = width.value - 2 * VideoMetrics.DESKTOP_MARGIN_DP + VideoMetrics.GRID_COL_GAP_DP
    return (usable / (CARD_MIN_DP + VideoMetrics.GRID_COL_GAP_DP)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
}

/** Asks the feed to catch up (not forced); the feed records the channels it could not load, so a failure here is nothing to show. */
private suspend fun refreshFeed(feed: SubscriptionFeed) {
    try {
        feed.refresh(force = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // the feed's own state says which channels failed
    }
}

/** Room under the Shorts shelf on a phone, before the next card. */
private val SHORTS_FEED_BOTTOM = 16.dp

/** Room above the first card, under the chip row. */
private val FIRST_CARD_TOP = 4.dp
private val SKELETON_TOP = 4.dp

/** The wide grid's room under the chips and between its rows (the video apps leave about 36). */
private val WIDE_FEED_TOP = 12.dp
private val WIDE_ROW_GAP = 32.dp

/** Three card skeletons stand in while the feed loads (spec 5.2). */
private const val SKELETON_CARDS = 3

/** The next page is asked for when one of the last three cards is on screen (spec 5.2). */
private const val LOAD_AHEAD_CARDS = 3

/** The narrowest a card of the wide grid is let be, in dp, and the fewest and most columns. */
private const val CARD_MIN_DP = 320f
private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 5

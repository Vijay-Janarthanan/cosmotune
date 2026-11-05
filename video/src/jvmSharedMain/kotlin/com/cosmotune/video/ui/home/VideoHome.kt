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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.cosmotune.video.resources.*
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.ui.chrome.CosmoPullIndicator
import com.cosmotune.ui.kit.KitPageTitle
import com.cosmotune.video.resources.video_tab_home
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * Video's Home (DESIGN_SYSTEM.md 6.2), laid out as one continuous feed the way the video apps do it: the large title "Home", a chip row
 * that stays at the top once the title has gone (All, Shorts, then the kiosks the source offers), and under it a single vertical list of
 * video cards, the Shorts shelf standing in it after the third card. There is no greeting and no stack of carousels. "Continue watching"
 * is a 280 dp shelf above the feed, only while some video is part-watched; the channels you follow are the Subscriptions page's.
 *
 * All blends available account recommendations, subscriptions, diverse recent watch interests and source-topic candidates
 * ([HomePlanner.stream]); a topic chip shows that topic alone. Account and topic continuations load independently, keep useful
 * cards on failure and offer explicit retries. Home feedback stays local and can be reset. A
 * first launch with no history and no subscriptions shows the kiosk alone. [HomeModel] holds the kiosk and the related lists.
 *
 * One lazy column on a phone: the page's width less its margins, 16:9 with 12 dp corners, with the channel's picture, the title in two
 * lines, the meta line and the "more" button under each; a pull refreshes it. A [wide] page (840 dp and up, always the desktop) is a lazy
 * grid of the same cards, as many columns as fit 320 dp (two to five), with the title and the chip row above the grid. [contentPadding]
 * is the shell's room for its bars.
 *
 * Every card tap goes through the action handler (`actions.play(item, list)`: a video opens its watch page, a Short the pager with
 * the list's Shorts); "more", a hold and a right click open the video sheet. Titles and pictures are DeArrow's when on.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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

    // A bounded window of diverse recent watch interests.
    val recent = remember(history, settings.homeHiddenVideos, settings.homeHiddenChannels) {
        HomePlanner.interestWatches(history).filter { it.item.id !in settings.homeHiddenVideos && it.item.channelId !in settings.homeHiddenChannels }
    }
    val recentIds = remember(recent) { recent.map { it.item.id } }

    // The kiosks, the selected kiosk's first page and the related lists: what the cache holds is there in the first frame (the list
    // is back as it was when Home returns from a video), the rest comes from the source.
    val regionKey = "${settings.region.orEmpty()}|${settings.contentLanguage.orEmpty()}"
    val model = remember(graph) { HomeModel(graph.source, scope).also { it.restore(regionKey, settings.homeKiosk, recentIds) } }
    LaunchedEffect(model, regionKey) {
        // Configure before starting any region-specific request; the process-wide
        // settings collector may otherwise still be applying the previous locale.
        val selected = graph.settings.settings.value
        val locale = Locale.getDefault()
        graph.source.setRegion(selected.region ?: locale.country, selected.contentLanguage ?: locale.language)
        model.start(regionKey, selected.homeKiosk)
    }

    // The account's own home, while a Google account is signed in.
    val accountState by graph.account.state.collectAsState()
    val signedIn = accountState is AccountState.SignedIn
    val accountFeed = remember(graph, accountState, regionKey, settings.homeAccountEnabled) { if (signedIn && settings.homeAccountEnabled) AccountHomeFeed(graph.account, scope) else null }
    LaunchedEffect(accountFeed) { accountFeed?.start() }
    DisposableEffect(accountFeed) { onDispose { accountFeed?.close() } }

    // The feed catches up when Home opens (a refresh within 10 minutes of the last is skipped by the feed itself).
    LaunchedEffect(graph, followsChannels) { if (followsChannels) refreshFeed(graph.feed) }

    // Continue watching; useful related lists are cached with bounded retry and concurrency.
    val continueEntries = remember(history, settings.homeHiddenVideos, settings.homeHiddenChannels) {
        HomePlanner.continueWatching(history).filter { it.item.id !in settings.homeHiddenVideos && it.item.channelId !in settings.homeHiddenChannels }
    }
    val continueItems = remember(continueEntries) { continueEntries.map { it.item } }
    val continueWatched = remember(continueEntries) {
        buildMap { for (entry in continueEntries) entry.item.watchedFraction(entry.positionMs, entry.durationMs)?.let { put(entry.item.id, it) } }
    }
    LaunchedEffect(model, recentIds, regionKey) { model.loadRelated(recentIds) }

    val related = model.related
    val accountItems = HomePlanner.feedback(accountFeed?.items.orEmpty(), settings.homeHiddenVideos, settings.homeHiddenChannels)
    val subscriptionsShelf = remember(feedItems, settings.homeHiddenVideos, settings.homeHiddenChannels) {
        HomePlanner.feedback(HomePlanner.subscriptionsShelf(feedItems), settings.homeHiddenVideos, settings.homeHiddenChannels)
    }
    val forYou = remember(recent, related, history, settings.homeHiddenVideos, settings.homeHiddenChannels) {
        HomePlanner.feedback(HomePlanner.forYou(recent, related, history), settings.homeHiddenVideos, settings.homeHiddenChannels)
    }
    val kioskVideos = remember(model.items, settings.homeHiddenVideos, settings.homeHiddenChannels) {
        HomePlanner.feedback(model.items.filterNot(ShortsFilter::isShort), settings.homeHiddenVideos, settings.homeHiddenChannels)
    }
    val shorts = remember(feedItems, related, recentIds, model.items, accountItems, hideShorts, settings.homeHiddenVideos, settings.homeHiddenChannels) {
        if (hideShorts) {
            emptyList()
        } else {
            val fromRelated = recentIds.flatMap { id -> related[id].orEmpty().mapNotNull { (it as? ListItem.Video)?.item } }
            HomePlanner.feedback(HomePlanner.shorts(feedItems, fromRelated + accountItems.filter(ShortsFilter::isShort), model.items), settings.homeHiddenVideos, settings.homeHiddenChannels)
        }
    }

    // All is the mixed feed; a kiosk's chip is that kiosk alone.
    var all by rememberSaveable { mutableStateOf(true) }
    val rankingTime = remember(regionKey) { System.currentTimeMillis() }
    val stream = remember(all, accountItems, subscriptionsShelf, forYou, kioskVideos, history, rankingTime) {
        if (all) HomePlanner.rankForHome(HomePlanner.stream(accountItems, subscriptionsShelf, forYou, kioskVideos), history, rankingTime) else kioskVideos
    }
    val phase = when {
        stream.isNotEmpty() -> KioskPhase.READY
        all && accountFeed != null && !accountFeed.loaded -> KioskPhase.LOADING
        else -> model.phase
    }
    val loadingMore = model.loadingMore || all && accountFeed?.loadingMore == true
    val loadMore: () -> Unit = { if (all) accountFeed?.loadMore(); model.loadMore() }

    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    var why by remember { mutableStateOf<String?>(null) }
    val titleContinue = stringResource(Res.string.video_shelf_continue)
    val titleShorts = stringResource(Res.string.video_shorts)
    val titleError = stringResource(Res.string.video_list_error)
    val titleRetry = stringResource(Res.string.video_retry)
    val openShorts = { navigator.push(VideoRoute.Shorts(ShortsOrigin.Home)) }

    val refreshAll: () -> Unit = {
        graph.account.invalidate(); accountFeed?.start(); model.retry()
        scope.launch { refreshFeed(graph.feed, force = true) }
    }
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
            leading = {
                HomeDiscoveryControls(settings.region, settings.contentLanguage, settings.homeHiddenVideos.size + settings.homeHiddenChannels.size,
                    onPreferences = { navigator.push(VideoRoute.Settings) },
                    onRefresh = refreshAll,
                    onReset = { graph.settings.update { it.copy(homeHiddenVideos = emptySet(), homeHiddenChannels = emptySet()) } })
            },
        )
    }
    val status: @Composable () -> Unit = {
        if (all && accountFeed?.sessionExpired == true) HomeNotice(stringResource(Res.string.video_home_account_expired), stringResource(Res.string.video_home_renew_signin), { scope.launch { graph.account.signInRequest() } })
        else if (all && accountFeed?.firstError == true) HomeNotice(stringResource(Res.string.video_home_account_failed), titleRetry, { accountFeed?.retry() })
        else if (all && accountFeed?.moreError == true) HomeNotice(stringResource(Res.string.video_home_account_next_failed), titleRetry, { accountFeed?.retry() })
        if (model.initialError && stream.isNotEmpty()) HomeNotice(stringResource(Res.string.video_home_kiosk_failed), titleRetry, model::retry)
        if (model.moreError) HomeNotice(stringResource(Res.string.video_home_kiosk_next_failed), titleRetry, model::retryMore)
        if (model.repeatedPage || all && accountFeed?.repeatedPage == true) HomeNotice(stringResource(Res.string.video_home_repeated))
    }
    val continueShelf: @Composable (Modifier) -> Unit = { modifier ->
        HomeShelf(
            title = titleContinue, items = continueItems, modifier = modifier,
            onOpen = { actions.play(it, continueItems) }, onMore = { sheet = HomeSheet(it, continueItems) },
            watched = continueWatched, downloaded = { it in downloaded },
            onSeeAll = { navigator.push(VideoRoute.History) },
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
        exhausted = phase == KioskPhase.READY && !model.hasMore && (!all || accountFeed == null || accountFeed.exhausted),
    )
    val onOpen: (VideoItem) -> Unit = { actions.play(it, stream) }
    val onMore: (VideoItem) -> Unit = { sheet = HomeSheet(it, stream) }
    val onChannel: (VideoItem) -> Unit = { item -> item.channelId?.let { navigator.push(VideoRoute.Channel(it)) } }
    val refreshing = phase == KioskPhase.LOADING
    val isDownloaded: (VideoItem) -> Boolean = { it.id in downloaded }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (wide) {
            val columns = homeColumns(maxWidth)
            HomeFeedWide(feed, columns, contentPadding, chips, status, continueShelf, shortsShelf, onOpen, onMore, onChannel, isDownloaded, titleError, titleRetry, model::retry, loadMore)
        } else {
            val pullState = rememberPullToRefreshState()
            PullToRefresh(refreshing = refreshing, onRefresh = refreshAll, state = pullState) {
                Box(Modifier.fillMaxSize()) {
                    HomeFeedPhone(feed, contentPadding, chips, status, continueShelf, shortsShelf, onOpen, onMore, onChannel, isDownloaded, titleError, titleRetry, model::retry, loadMore)
                    CosmoPullIndicator(
                        fraction = { pullState.distanceFraction },
                        refreshing = refreshing,
                        modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                    )
                }
            }
        }
    }

    sheet?.let { held ->
        VideoActionsSheet(
            item = held.item,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(held.item, action, VideoSheetContext.DEFAULT, list = held.list) },
            onDismiss = { sheet = null },
            downloaded = held.item.id in downloaded,
            extraActions = {
                com.cosmotune.ui.sheets.CosmoActionRow(icon = Icons.Outlined.Info, label = stringResource(Res.string.video_home_why), onClick = {
                    why = held.item.id; sheet = null
                })
                com.cosmotune.ui.sheets.CosmoActionRow(icon = Icons.Outlined.Close, label = stringResource(Res.string.video_home_not_interested), onClick = {
                    graph.settings.update { it.copy(homeHiddenVideos = (it.homeHiddenVideos + held.item.id).toList().takeLast(1000).toSet()) }; sheet = null
                })
                if (held.item.channelId != null) com.cosmotune.ui.sheets.CosmoActionRow(icon = Icons.Outlined.PersonOff, label = stringResource(Res.string.video_home_hide_channel), onClick = {
                    graph.settings.update { it.copy(homeHiddenChannels = (it.homeHiddenChannels + requireNotNull(held.item.channelId)).toList().takeLast(300).toSet()) }; sheet = null
                })
            },
        )
    }
    why?.let { id ->
        val message = when {
            accountItems.any { it.id == id } -> Res.string.video_home_reason_account
            subscriptionsShelf.any { it.id == id } -> Res.string.video_home_reason_subscription
            forYou.any { it.id == id } -> Res.string.video_home_reason_history
            else -> Res.string.video_home_reason_topic
        }
        AlertDialog(onDismissRequest = { why = null }, title = { Text(text = stringResource(Res.string.video_home_why)) },
            text = { Column { Text(text = stringResource(message)); Text(text = stringResource(Res.string.video_home_mixture)) } },
            confirmButton = { TextButton(onClick = { why = null }) { Text(text = stringResource(Res.string.video_home_done)) } })
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
    val exhausted: Boolean,
)

/** A video whose menu is open, and the list its card stood in (a Short in it opens the pager with that list's Shorts). */
@Immutable
private class HomeSheet(val item: VideoItem, val list: List<VideoItem>)

/** The phone's feed: one lazy column under the shell's bar, the title scrolling away, the chips then sticking under the bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeFeedPhone(
    feed: HomeFeed,
    contentPadding: PaddingValues,
    chips: @Composable (Modifier) -> Unit,
    status: @Composable () -> Unit,
    continueShelf: @Composable (Modifier) -> Unit,
    shortsShelf: @Composable (Modifier) -> Unit,
    onOpen: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
    onChannel: (VideoItem) -> Unit,
    downloaded: (VideoItem) -> Boolean,
    errorText: String,
    retryText: String,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val keys = remember(feed.items) { uniqueKeys(feed.items) }
    val shortsAt = minOf(HomePlanner.SHORTS_AFTER - 1, feed.items.lastIndex)
    // The list starts under the bar rather than scrolling beneath it: a sticky header pins to the top of the list, and it must pin below the bar.
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
        item(key = "title", contentType = "title") { KitPageTitle(stringResource(Res.string.video_tab_home)) }
        stickyHeader(key = "chips", contentType = "chips") { chips(Modifier.videoSolidGround()) }
        item(key = "source-status", contentType = "status") { status() }
        if (feed.hasContinue) item(key = "continue", contentType = "shelf") { continueShelf(Modifier) }
        when (feed.phase) {
            KioskPhase.LOADING -> items(count = SKELETON_CARDS, key = { "skeleton-$it" }, contentType = { "skeleton" }) {
                CardSkeleton(Modifier.padding(horizontal = VideoMetrics.MARGIN_DP.dp).padding(top = SKELETON_TOP, bottom = CARD_AFTER))
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
                            onAvatarClick = if (item.channelId != null) ({ onChannel(item) }) else null,
                        )
                        if (index == shortsAt && feed.hasShorts) shortsShelf(Modifier.padding(bottom = SHORTS_FEED_BOTTOM))
                    }
                }
                if (feed.loadingMore) {
                    item(key = "more", contentType = "skeleton") { CardSkeleton(Modifier.padding(horizontal = VideoMetrics.MARGIN_DP.dp)) }
                }
                if (feed.exhausted) item(key = "end", contentType = "status") { HomeNotice(stringResource(Res.string.video_home_exhausted)) }
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
    status: @Composable () -> Unit,
    continueShelf: @Composable (Modifier) -> Unit,
    shortsShelf: @Composable (Modifier) -> Unit,
    onOpen: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
    onChannel: (VideoItem) -> Unit,
    downloaded: (VideoItem) -> Boolean,
    errorText: String,
    retryText: String,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val keys = remember(feed.items) { uniqueKeys(feed.items) }
    // The Shorts shelf stands after the third card, on a whole row so that no cell is left empty above it.
    val cut = if (feed.hasShorts) minOf(feed.items.size, ceil(HomePlanner.SHORTS_AFTER / columns.toDouble()).toInt() * columns) else feed.items.size
    val margin = VideoMetrics.DESKTOP_MARGIN_DP.dp
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        KitPageTitle(stringResource(Res.string.video_tab_home), Modifier.padding(horizontal = margin - VideoMetrics.MARGIN_DP.dp))
        chips(Modifier.videoSolidGround().padding(horizontal = margin - VideoMetrics.MARGIN_DP.dp))
        Column(Modifier.padding(horizontal = margin - VideoMetrics.MARGIN_DP.dp)) { status() }
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
                    items(count = cut, key = { "feed:${keys[it]}" }, contentType = { "card" }) { index -> WideCard(feed.items[index], onOpen, onMore, onChannel, downloaded) }
                    if (feed.hasShorts) {
                        item(key = "shorts", span = { GridItemSpan(maxLineSpan) }, contentType = "shelf") {
                            shortsShelf(Modifier.bleedBy(margin).padding(horizontal = margin - VideoMetrics.MARGIN_DP.dp))
                        }
                    }
                    items(count = feed.items.size - cut, key = { "feed:${keys[cut + it]}" }, contentType = { "card" }) { index ->
                        WideCard(feed.items[cut + index], onOpen, onMore, onChannel, downloaded)
                    }
                    if (feed.loadingMore) items(count = columns, key = { "more-$it" }, contentType = { "skeleton" }) { CardSkeleton() }
                    if (feed.exhausted) item(key = "end", span = { GridItemSpan(maxLineSpan) }, contentType = "status") { HomeNotice(stringResource(Res.string.video_home_exhausted)) }
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

/** One card of the grid: the feed card of the phone without its margin, DeArrow's title and picture. */
@Composable
private fun WideCard(item: VideoItem, onOpen: (VideoItem) -> Unit, onMore: (VideoItem) -> Unit, onChannel: (VideoItem) -> Unit, downloaded: (VideoItem) -> Boolean) {
    val shown = rememberBranded(item)
    VideoCardBody(
        item = shown,
        onClick = { onOpen(item) },
        onMore = { onMore(item) },
        modifier = Modifier,
        watchedFraction = null,
        title = shown.title,
        downloaded = downloaded(item),
        onAvatarClick = if (item.channelId != null) ({ onChannel(item) }) else null,
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
private suspend fun refreshFeed(feed: SubscriptionFeed, force: Boolean = false) {
    try {
        feed.refresh(force = force)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // the feed's own state says which channels failed
    }
}

/** The room a card's skeleton keeps after it, as the card keeps. */
private val CARD_AFTER = 16.dp

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

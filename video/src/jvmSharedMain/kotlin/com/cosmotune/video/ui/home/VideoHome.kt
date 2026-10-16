package com.cosmotune.video.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.feed.SubscriptionFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.home.HomePlanner
import com.cosmotune.video.home.HomeShelfKind
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_list_error
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.resources.video_shelf_continue
import com.cosmotune.video.resources.video_shelf_for_you
import com.cosmotune.video.resources.video_shelf_subscriptions
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoCard
import com.cosmotune.video.ui.blocks.VideoCardBody
import com.cosmotune.video.ui.blocks.VideoGridSpec
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shorts.ShortsShelf
import com.cosmotune.video.ui.shorts.openShort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil

/**
 * Video's Home (plan task Y-17, spec 5.2): the greeting, a chip row (the Shorts chip, then the five kiosks), up to four shelves
 * (Continue watching, From your subscriptions, Up next for you and the Shorts, which stands after the first shelf), and then the
 * selected kiosk as cards that load more at the end. What goes in the shelves is [HomePlanner]'s; the kiosk, its pages and the
 * related lists are [HomeModel]'s. A shelf with nothing in it is not drawn, and the shelves come from the device (history, feed) or
 * from lists fetched on the side, so they never wait for the kiosk. A first launch, with no history and no subscriptions, shows only
 * the kiosk.
 *
 * One lazy column on the phone and on a [wide] page (840 dp and up, always the desktop): the wide page keeps the 28 dp margin, a
 * larger heading, 280 dp shelf cards, and lays the kiosk out in rows of cards in the columns of `VideoGridSpec` (16 dp between
 * columns, 28 between rows). [contentPadding] is the shell's room for its bars.
 *
 * Every card tap goes through the action handler (`actions.play(item, list)`: a video opens its watch page, a Short the pager with
 * the list's Shorts); "more", a hold and a right click open the video sheet. Titles and pictures are DeArrow's when on.
 */
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

    // The last three videos watched, whose related lists "Up next for you" and the Shorts shelf draw on.
    val recent = remember(history) { HomePlanner.recentWatches(history) }
    val recentIds = remember(recent) { recent.map { it.item.id } }

    // The kiosks, the selected kiosk's first page and the related lists: what the cache holds is there in the first frame (the list
    // is back as it was when Home returns from a video), the rest comes from the source.
    val regionKey = "${settings.region.orEmpty()}|${settings.contentLanguage.orEmpty()}"
    val model = remember(graph) { HomeModel(graph.source, scope).also { it.restore(regionKey, settings.homeKiosk, recentIds) } }
    LaunchedEffect(model, regionKey) { model.start(regionKey, graph.settings.settings.value.homeKiosk) }

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
    val subscriptionsShelf = remember(feedItems) { HomePlanner.subscriptionsShelf(feedItems) }
    val forYou = remember(recent, related, history) { HomePlanner.forYou(recent, related, history) }
    val kioskItems = remember(model.items, hideShorts) { ShortsFilter.filter(model.items, hideShorts) { it } }
    val shorts = remember(feedItems, related, recentIds, model.items, hideShorts) {
        if (hideShorts) {
            emptyList()
        } else {
            val fromRelated = recentIds.flatMap { id -> related[id].orEmpty().mapNotNull { (it as? ListItem.Video)?.item } }
            HomePlanner.shorts(feedItems, fromRelated, model.items)
        }
    }
    val order = remember(continueItems.isNotEmpty(), subscriptionsShelf.isNotEmpty(), forYou.isNotEmpty(), shorts.isNotEmpty()) {
        HomePlanner.shelfOrder(continueItems.isNotEmpty(), subscriptionsShelf.isNotEmpty(), forYou.isNotEmpty(), shorts.isNotEmpty())
    }

    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    val listState = rememberLazyListState()
    val titleContinue = stringResource(Res.string.video_shelf_continue)
    val titleSubscriptions = stringResource(Res.string.video_shelf_subscriptions)
    val titleForYou = stringResource(Res.string.video_shelf_for_you)
    val titleShorts = stringResource(Res.string.video_shorts)
    val titleError = stringResource(Res.string.video_list_error)
    val titleRetry = stringResource(Res.string.video_retry)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (wide) VideoGridSpec.of(maxWidth.value).columns else 1
        // The shelves and the heading bring a 16 dp margin of their own; a wide page wants 28.
        val extra = if (wide) WIDE_EXTRA else 0.dp
        val cardWidth = if (wide) WIDE_SHELF_CARD_DP.dp else VideoMetrics.SHELF_CARD_W_DP.dp
        val keys = remember(kioskItems) { uniqueKeys(kioskItems) }
        val rows = remember(kioskItems, columns, wide) { if (wide) kioskItems.chunked(columns) else emptyList() }
        val openShorts = { navigator.push(VideoRoute.Shorts(ShortsOrigin.Home)) }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "header", contentType = "header") { HomeHeader(wide = wide, extra = extra) }
            item(key = "chips", contentType = "chips") {
                HomeChips(
                    kiosks = model.kiosks,
                    selectedId = model.selectedId,
                    showShorts = !hideShorts,
                    onShorts = openShorts,
                    onSelect = { kiosk ->
                        graph.settings.update { it.copy(homeKiosk = kiosk.id) }
                        model.select(kiosk.id)
                    },
                    modifier = Modifier.padding(horizontal = extra),
                )
            }
            for (kind in order) {
                item(key = "shelf-${kind.name}", contentType = "shelf") {
                    val shelfModifier = Modifier.padding(horizontal = extra)
                    when (kind) {
                        HomeShelfKind.CONTINUE -> HomeShelf(
                            title = titleContinue, items = continueItems, modifier = shelfModifier, cardWidth = cardWidth,
                            onOpen = { actions.play(it, continueItems) }, onMore = { sheet = HomeSheet(it, continueItems) },
                            watched = continueWatched, downloaded = { it in downloaded },
                        )
                        HomeShelfKind.SUBSCRIPTIONS -> HomeShelf(
                            title = titleSubscriptions, items = subscriptionsShelf, modifier = shelfModifier, cardWidth = cardWidth,
                            onOpen = { actions.play(it, subscriptionsShelf) }, onMore = { sheet = HomeSheet(it, subscriptionsShelf) },
                            downloaded = { it in downloaded },
                        )
                        HomeShelfKind.FOR_YOU -> HomeShelf(
                            title = titleForYou, items = forYou, modifier = shelfModifier, cardWidth = cardWidth,
                            onOpen = { actions.play(it, forYou) }, onMore = { sheet = HomeSheet(it, forYou) },
                            downloaded = { it in downloaded },
                        )
                        HomeShelfKind.SHORTS -> ShortsShelf(
                            title = titleShorts, items = shorts, modifier = shelfModifier,
                            onOpen = { navigator.openShort(it, ShortsOrigin.Home, shorts) }, onSeeAll = openShorts,
                        )
                    }
                }
            }
            item(key = "kiosk-gap", contentType = "gap") { Spacer(Modifier.height(KIOSK_TOP)) }
            when (model.phase) {
                KioskPhase.LOADING -> if (wide) {
                    item(key = "kiosk-skeleton", contentType = "skeleton") { SkeletonRow(columns) }
                } else {
                    items(count = SKELETON_CARDS, key = { "kiosk-skeleton-$it" }, contentType = { "skeleton" }) {
                        CardSkeleton(Modifier.padding(horizontal = VideoMetrics.MARGIN_DP.dp).padding(bottom = VideoMetrics.FEED_AFTER_DP.dp))
                    }
                }
                KioskPhase.FAILED -> item(key = "kiosk-error", contentType = "error") {
                    CosmoEmptyState(message = titleError, actionLabel = titleRetry, onAction = model::retry)
                }
                KioskPhase.READY -> {
                    if (wide) {
                        items(count = rows.size, key = { "kiosk-row:${keys[it * columns]}" }, contentType = { "row" }) { rowIndex ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = VideoMetrics.DESKTOP_MARGIN_DP.dp).padding(bottom = VideoMetrics.GRID_ROW_GAP_DP.dp),
                                horizontalArrangement = Arrangement.spacedBy(VideoMetrics.GRID_COL_GAP_DP.dp),
                            ) {
                                val row = rows[rowIndex]
                                for (cell in 0 until columns) {
                                    val item = row.getOrNull(cell)
                                    if (item == null) {
                                        Spacer(Modifier.weight(1f))
                                    } else {
                                        Box(Modifier.weight(1f)) {
                                            val shown = rememberBranded(item)
                                            VideoCardBody(
                                                item = shown,
                                                onClick = { actions.play(item, kioskItems) },
                                                onMore = { sheet = HomeSheet(item, kioskItems) },
                                                modifier = Modifier,
                                                watchedFraction = null,
                                                title = shown.title,
                                                downloaded = item.id in downloaded,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        items(count = kioskItems.size, key = { "kiosk:${keys[it]}" }, contentType = { "card" }) { index ->
                            val item = kioskItems[index]
                            VideoCard(
                                item = rememberBranded(item),
                                onClick = { actions.play(item, kioskItems) },
                                onMore = { sheet = HomeSheet(item, kioskItems) },
                                downloaded = item.id in downloaded,
                            )
                        }
                    }
                    if (model.loadingMore) {
                        item(key = "kiosk-more", contentType = "skeleton") {
                            if (wide) SkeletonRow(columns) else CardSkeleton(Modifier.padding(horizontal = VideoMetrics.MARGIN_DP.dp))
                        }
                    }
                }
            }
        }

        // The next page of the kiosk is asked for three cards before the end (a wide page counts rows: enough of them to be three cards).
        val ahead = ceil(LOAD_AHEAD_CARDS / columns.toDouble()).toInt()
        LaunchedEffect(listState, model, ahead) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - ahead) model.loadMore()
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
        )
    }
}

/** A video whose menu is open, and the list its card stood in (a Short in it opens the pager with that list's Shorts). */
@Immutable
private class HomeSheet(val item: VideoItem, val list: List<VideoItem>)

/** A row of card skeletons for the wide page: one for each column, as the cards they stand in for. */
@Composable
private fun SkeletonRow(columns: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = VideoMetrics.DESKTOP_MARGIN_DP.dp).padding(bottom = VideoMetrics.GRID_ROW_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(VideoMetrics.GRID_COL_GAP_DP.dp),
    ) {
        repeat(columns) { CardSkeleton(Modifier.weight(1f)) }
    }
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

/** Room between the last shelf and the kiosk's first card: a shelf's own 22 dp above, as every shelf has it. */
private val KIOSK_TOP = 22.dp

/** Three card skeletons stand in while the kiosk loads (spec 5.2). */
private const val SKELETON_CARDS = 3

/** The next page is asked for when one of the last three cards is on screen (spec 5.2). */
private const val LOAD_AHEAD_CARDS = 3

/** A wide page's shelf cards are 280 dp wide (spec 5.2). */
private const val WIDE_SHELF_CARD_DP = 280

/** What a wide page wants more than the 16 dp margin of the heading, the chips and the shelves: 28 less 16. */
private val WIDE_EXTRA = (VideoMetrics.DESKTOP_MARGIN_DP - VideoMetrics.MARGIN_DP).dp

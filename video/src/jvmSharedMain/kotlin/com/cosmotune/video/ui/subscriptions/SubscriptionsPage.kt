package com.cosmotune.video.ui.subscriptions

import com.cosmotune.video.ui.blocks.VideoPageHeading
import com.cosmotune.video.resources.video_subscriptions_label
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.components.CosmoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.cosmotune.video.feed.StableFeed
import com.cosmotune.video.ui.shell.rememberKept
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.chrome.CosmoPullIndicator
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.account.AccountLists
import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.feed.FeedDay
import com.cosmotune.video.feed.FeedGroup
import com.cosmotune.video.feed.FeedMerge
import com.cosmotune.video.feed.SubscriptionFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_find_channels
import com.cosmotune.video.resources.video_import
import com.cosmotune.video.resources.video_no_subscriptions
import com.cosmotune.video.resources.video_no_subscriptions_text
import com.cosmotune.video.resources.video_older
import com.cosmotune.video.resources.video_open_channel_chip
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.resources.video_tab_subscriptions
import com.cosmotune.video.resources.video_this_month
import com.cosmotune.video.resources.video_this_week
import com.cosmotune.video.resources.video_today
import com.cosmotune.video.resources.video_yesterday
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.account.AccountExpiredNotice
import com.cosmotune.video.ui.account.AccountList
import com.cosmotune.video.ui.account.rememberAccountVideos
import com.cosmotune.video.ui.actions.VideoActionHandler
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.ChannelStrip
import com.cosmotune.video.ui.blocks.LocalVideoNowMs
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoCard
import com.cosmotune.video.ui.blocks.VideoCardBody
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.branding.rememberChannelAvatar
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.ui.shorts.ShortsShelf
import com.cosmotune.video.ui.shorts.openShort
import com.cosmotune.music.ui.LocalPullToRefreshEnabled
import com.cosmotune.music.ui.components.PullToRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.ZoneId
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitPageTitle
import com.cosmotune.video.ui.blocks.VideoChips
import com.cosmotune.video.resources.video_filter_empty
import com.cosmotune.video.resources.video_filter_unwatched
import com.cosmotune.video.resources.video_all
import com.cosmotune.video.resources.video_shelf_continue
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The Subscriptions tab (DESIGN_SYSTEM.md 6.2): the feed of the channels you follow.
 *
 * From the top: the large title; the channel strip ("All" and the channels, the newest uploads first, "View all" last); the chips (All, Today,
 * Continue watching, Unwatched); a Shorts shelf of the feed's Shorts, newest
 * first (hidden by "Hide Shorts" and below [SubscriptionsFeedPlan.SHELF_MIN_SHORTS] Shorts); the status line (when the feed was
 * updated, the channels that could not be loaded with "Try again", "Download all"); then the feed under the day overlines (Today,
 * Yesterday, This week, This month, Older) as feed cards, one column on the phone and, on a [wide] page, a grid of the columns of
 * [VideoMetrics.gridColumns] for each day. A Short in the feed keeps its card and opens the Shorts pager.
 *
 * A channel picked in the strip filters the feed and the shelf and brings an "Open channel" chip. With no subscription the page
 * is the empty state with "Import" and "Find channels".
 *
 * The feed refreshes when the page opens (it skips what was read lately) and when the list is pulled (every channel, forced); a
 * [wide] page (the desktop) is not pulled and its status line has a "Refresh" button instead. [contentPadding] is what the shell keeps
 * clear for its bars; it has no side margin, so the blocks bring their own 16 dp and a wide page lays its 28 dp out itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsPage(contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val actions = rememberVideoActions()
    val feed = graph.feed
    val scope = rememberCoroutineScope()

    val feedState by feed.state.collectAsState()
    val channels by graph.library.subscriptions.collectAsState()
    val history by graph.library.history.collectAsState()
    val hideShorts by remember(graph.settings) { graph.settings.settings.map { it.hideShorts }.distinctUntilChanged() }
        .collectAsState(initial = graph.settings.settings.value.hideShorts)
    val downloaded by rememberDownloadedIds()

    // The strip's choice is kept with the page (a rotation, a visit to a video and back); a channel you have since left is no choice.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedId = picked?.takeIf { id -> channels.any { it.channelId == id } }
    // Signed in, the account's own subscriptions feed is merged in (each video once, newest first): its channels show even before
    // they are followed on this device. Signed out, or when it cannot be read, the feed is this device's alone, as before.
    val onAccount = rememberAccountVideos(AccountList.SUBSCRIPTIONS)
    // The two feeds are merged by date and each grows: what is shown is kept (with the page, for the way back from a video) and new
    // uploads slip in by date without the shown ones changing places.
    val feedKeep = rememberKept("subscriptions-feed") { StableFeed(newestFirst = true) }
    val items = remember(feedState.items, onAccount.items) { feedKeep.update("feed", AccountLists.mergeFeed(onAccount.items, feedState.items, System.currentTimeMillis())) }
    var filter by rememberSaveable { mutableStateOf(FeedFilter.ALL) }
    val watchedNow = remember(history) { history.associateBy { it.item.id } }
    val clockNow = LocalVideoNowMs.current
    val shown = remember(items, selectedId, filter, watchedNow, clockNow) {
        SubscriptionsFeedPlan.filtered(SubscriptionsFeedPlan.forChannel(items, selectedId), filter, watchedNow, FeedDay.startOfToday(clockNow ?: System.currentTimeMillis()))
    }
    val shelf = remember(shown, hideShorts) { SubscriptionsFeedPlan.shelf(shown, hideShorts) }
    val strip = remember(channels, items) { SubscriptionsFeedPlan.stripOrder(channels, items) }
    val clock = LocalVideoNowMs.current
    val groups = remember(shown, clock) { SubscriptionsFeedPlan.groups(shown, clock ?: System.currentTimeMillis()) }
    val watched = remember(history) { history.associateBy { it.item.id } }

    // On open: what is due. A pull, "Try again" and "Refresh" read every channel (one at a time: a second pull waits for the first).
    LaunchedEffect(feed) { feed.refreshQuietly(force = false) }
    var forced by remember { mutableStateOf(false) }
    val refresh: () -> Unit = remember(feed, scope) {
        {
            if (!forced) {
                forced = true
                onAccount.reload()
                scope.launch {
                    try {
                        feed.refreshQuietly(force = true)
                    } finally {
                        forced = false
                    }
                }
            }
        }
    }
    val refreshing = feedState.refreshing || forced

    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }
    val title = stringResource(Res.string.video_tab_subscriptions)
    val downloadAll: (() -> Unit)? = remember(shown, title, actions) {
        if (shown.isEmpty()) null else ({ actions.downloadBatch(DownloadBatchInfo("feed", BatchKind.FEED, title), shown, latestPicker = false) })
    }
    val shortsOrigin = if (selectedId == null) ShortsOrigin.Subscriptions else ShortsOrigin.Channel(selectedId)
    val model = FeedModel(strip, selectedId, shelf, feedState.updatedAtMs, feedState.failedChannels.size, filter)
    // A wide page (the desktop, a tablet) is not pulled: a wheel scrolling up at the top would start it. Its status line has "Refresh" instead.
    val pullEnabled = LocalPullToRefreshEnabled.current && !wide

    val pullState = rememberPullToRefreshState()
    CompositionLocalProvider(LocalPullToRefreshEnabled provides pullEnabled) {
        PullToRefresh(refreshing = refreshing, onRefresh = refresh, state = pullState) {
            Box(Modifier.fillMaxSize()) {
                // No channel followed here is an empty page only when the account has no feed either.
                val noFeed = if (channels.isEmpty()) onAccount.items.isEmpty() else false
                if (noFeed) {
                    NoSubscriptions(
                        contentPadding = contentPadding,
                        onImport = { navigator.push(VideoRoute.Import) },
                        onFind = { navigator.push(VideoRoute.Search("")) },
                    )
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val columns = if (wide) VideoMetrics.gridColumns(maxWidth.value) else 1
                        val rows = remember(groups, columns) { if (wide) groups.map { (group, list) -> group to list.chunked(columns) } else emptyList() }
                        val listState = rememberLazyListState()
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                            item(key = KEY_TITLE, contentType = "title") { VideoPageHeading(title, stringResource(Res.string.video_subscriptions_label), Modifier.padding(horizontal = if (wide) WIDE_INSET else 0.dp)) }
                            if (onAccount.expired) {
                                item(key = KEY_ACCOUNT, contentType = "account") {
                                    AccountExpiredNotice(Modifier.padding(horizontal = if (wide) WIDE_INSET else 0.dp))
                                }
                            }
                            feedHead(
                                model = model,
                                inset = if (wide) WIDE_INSET else 0.dp,
                                onSelect = { picked = it },
                                onFilter = { filter = it },
                                onOpenShort = { navigator.openShort(it, shortsOrigin, shelf) },
                                onSeeAllShorts = { navigator.push(VideoRoute.Shorts(shortsOrigin)) },
                                onRetry = refresh,
                                onRefresh = if (pullEnabled) null else refresh,
                                onDownloadAll = downloadAll,
                                onOpenChannel = { id -> navigator.push(VideoRoute.Channel(id)) },
                                onViewAll = { navigator.push(VideoRoute.Channels) },
                            )
                            if (shown.isEmpty() && filter != FeedFilter.ALL) {
                                item(key = KEY_FILTER_EMPTY, contentType = "empty") {
                                    KitEmptyState(title = stringResource(Res.string.video_filter_empty), message = "", icon = VideoIcons.Subscriptions)
                                }
                            }
                            if (wide) {
                                for ((group, groupRows) in rows) {
                                    groupHeader(group, WIDE_INSET)
                                    items(groupRows, key = { "feed:row:${it.first().id}" }, contentType = { "row" }) { row ->
                                        FeedGridRow(
                                            row = row,
                                            columns = columns,
                                            downloaded = downloaded,
                                            watched = watched,
                                            onOpen = { openCard(it, shown, navigator, actions, shortsOrigin) },
                                            onMore = { sheetFor = it },
                                        )
                                    }
                                }
                            } else {
                                for ((group, list) in groups) {
                                    groupHeader(group, 0.dp)
                                    items(list, key = { it.id }, contentType = { "card" }) { item ->
                                        FeedCard(
                                            item = item,
                                            wide = false,
                                            downloaded = item.id in downloaded,
                                            watchedFraction = watched.watchedFractionOf(item),
                                            onClick = { openCard(item, shown, navigator, actions, shortsOrigin) },
                                            onMore = { sheetFor = item },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // The blob under the top bar, as Music's pages have it (the shell's bar hangs none for Video's pages).
                CosmoPullIndicator(
                    fraction = { pullState.distanceFraction },
                    refreshing = refreshing,
                    modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                )
            }
        }
    }

    sheetFor?.let { item ->
        VideoActionsSheet(
            item = item,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(item, action, VideoSheetContext.DEFAULT, list = shown) },
            onDismiss = { sheetFor = null },
            downloaded = item.id in downloaded,
        )
    }
}

/** The 12 dp a page of the wide kind adds to the phone blocks' own 16 dp to stand them on its 28 dp line. */
private val WIDE_INSET = (VideoMetrics.DESKTOP_MARGIN_DP - VideoMetrics.MARGIN_DP).dp

/** What the head of the list shows, worked out once per change of the feed. */
@Immutable
private class FeedModel(
    val strip: List<Subscription>,
    val selectedId: String?,
    val shelf: List<VideoItem>,
    val updatedAtMs: Long?,
    val failedCount: Int,
    val filter: FeedFilter,
)

/**
 * The items above the day groups: the strip, the chips, the Shorts shelf when there is one, the status line and, with a channel picked, its
 * "Open channel" chip. Each is one item with its own key.
 */
private fun LazyListScope.feedHead(
    model: FeedModel,
    inset: Dp,
    onSelect: (String?) -> Unit,
    onFilter: (FeedFilter) -> Unit,
    onOpenShort: (VideoItem) -> Unit,
    onSeeAllShorts: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: (() -> Unit)?,
    onDownloadAll: (() -> Unit)?,
    onOpenChannel: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    item(key = KEY_STRIP, contentType = "strip") {
        ChannelStrip(channels = model.strip, selectedId = model.selectedId, onSelect = onSelect, modifier = Modifier.padding(horizontal = inset), onViewAll = onViewAll)
    }
    item(key = KEY_FILTERS, contentType = "chips") {
        VideoChips(
            labels = FeedFilter.entries.map { stringResource(it.label()) },
            selected = model.filter.ordinal,
            onSelect = { onFilter(FeedFilter.entries[it]) },
            modifier = Modifier.padding(horizontal = inset),
        )
    }
    if (model.shelf.isNotEmpty()) {
        item(key = KEY_SHORTS, contentType = "shorts") {
            ShortsShelf(
                title = stringResource(Res.string.video_shorts),
                items = model.shelf,
                onOpen = onOpenShort,
                onSeeAll = onSeeAllShorts,
                modifier = Modifier.padding(horizontal = inset),
            )
        }
    }
    item(key = KEY_STATUS, contentType = "status") {
        FeedStatusLine(
            updatedAtMs = model.updatedAtMs,
            failedCount = model.failedCount,
            onRetry = onRetry,
            modifier = Modifier.padding(horizontal = inset),
            onRefresh = onRefresh,
            onDownloadAll = onDownloadAll,
        )
    }
    val picked = model.selectedId
    if (picked != null) {
        item(key = KEY_OPEN_CHANNEL, contentType = "open") {
            OpenChannelChip(onOpen = { onOpenChannel(picked) }, modifier = Modifier.padding(horizontal = inset).padding(start = CosmoSpacing.margin, top = 4.dp))
        }
    }
}

/**
 * The "Open channel" chip of a picked channel. It is a chip of the look (a rest chip, nothing chosen), but what it does is open a page, so
 * for a screen reader it is a button: the chip block is a toggle (`Role.Checkbox`), and a wrapper replaces that with the button's role and click.
 */
@Composable
private fun OpenChannelChip(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.video_open_channel_chip)
    OrbitSelectChip(
        title = label,
        selected = false,
        fonts = orbitFonts(),
        look = orbitLook(),
        onClick = onOpen,
        icon = CosmoIcons.ChevronRight,
        modifier = modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = label
            onClick(label = label) {
                onOpen()
                true
            }
        },
    )
}

private const val KEY_TITLE = "feed:title"
private const val KEY_FILTERS = "feed:filters"
private const val KEY_FILTER_EMPTY = "feed:filter-empty"
private const val KEY_STRIP = "feed:strip"
private const val KEY_SHORTS = "feed:shorts"
private const val KEY_STATUS = "feed:status"
private const val KEY_OPEN_CHANNEL = "feed:open-channel"
private const val KEY_ACCOUNT = "feed:account"

/** The day overline of a group, one item, keyed by the group. */
private fun LazyListScope.groupHeader(group: FeedGroup, inset: Dp) {
    item(key = "feed:group:${group.name}", contentType = "overline") {
        OrbitLabel(stringResource(group.label()), orbitFonts(), orbitLook().dim, Modifier.padding(horizontal = inset).padding(start = CosmoSpacing.margin + 4.dp, top = 18.dp, bottom = 10.dp))
    }
}

/** A tap on a feed card: a Short opens the pager (with the Shorts of [shown], origin [shortsOrigin]), any other video its watch page and plays. */
private fun openCard(item: VideoItem, shown: List<VideoItem>, navigator: VideoNavigator, actions: VideoActionHandler, shortsOrigin: ShortsOrigin) {
    if (item.isShort) navigator.openShort(item, shortsOrigin, shown) else actions.play(item, shown)
}

/** The watched bar of a feed item from the history, or null (no bar). */
private fun Map<String, HistoryEntry>.watchedFractionOf(item: VideoItem): Float? {
    val entry = this[item.id] ?: return null
    return item.watchedFraction(entry.positionMs, entry.durationMs)
}

/**
 * One feed card with the settings' DeArrow title and picture. On the phone the full-width [VideoCard] (its own 16 dp margin); on a wide
 * page the card body alone, for a grid cell.
 */
@Composable
private fun FeedCard(
    item: VideoItem,
    wide: Boolean,
    downloaded: Boolean,
    watchedFraction: Float?,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    val avatar = rememberChannelAvatar(item)
    val shown = remember(item, picture, avatar) { if (picture == item.thumbnailUrl && avatar == item.channelAvatarUrl) item else item.copy(thumbnailUrl = picture, channelAvatarUrl = avatar) }
    if (wide) {
        VideoCardBody(shown, onClick, onMore, modifier, watchedFraction, title, downloaded)
    } else {
        VideoCard(shown, onClick, onMore, modifier, watchedFraction, title, downloaded)
    }
}

/**
 * One line of a day's grid on a wide page: [columns] equal cells, 16 dp apart, on the 28 dp line, 28 dp under it before the next
 * line (the numbers of [VideoMetrics]). A last line with fewer cards leaves the others' room empty.
 */
@Composable
private fun FeedGridRow(
    row: List<VideoItem>,
    columns: Int,
    downloaded: Set<String>,
    watched: Map<String, HistoryEntry>,
    onOpen: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = VideoMetrics.DESKTOP_MARGIN_DP.dp, end = VideoMetrics.DESKTOP_MARGIN_DP.dp, bottom = VideoMetrics.GRID_ROW_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(VideoMetrics.GRID_COL_GAP_DP.dp),
    ) {
        for (cell in 0 until columns) {
            val item = row.getOrNull(cell)
            Box(Modifier.weight(1f)) {
                if (item != null) {
                    FeedCard(
                        item = item,
                        wide = true,
                        downloaded = item.id in downloaded,
                        watchedFraction = watched.watchedFractionOf(item),
                        onClick = { onOpen(item) },
                        onMore = { onMore(item) },
                    )
                }
            }
        }
    }
}

/**
 * The empty state (spec 5.7): "No subscriptions yet" with "Import" and "Find channels". The state block takes one action; the second
 * button stands under it, drawn up into the room the block keeps below its own button.
 */
@Composable
private fun NoSubscriptions(contentPadding: PaddingValues, onImport: () -> Unit, onFind: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CosmoEmptyState(
            message = stringResource(Res.string.video_no_subscriptions_text),
            title = stringResource(Res.string.video_no_subscriptions),
            icon = VideoIcons.Subscriptions,
            actionLabel = stringResource(Res.string.video_import),
            onAction = onImport,
        )
        KitButton(
            text = stringResource(Res.string.video_find_channels),
            onClick = onFind,
            modifier = Modifier.offset(y = EMPTY_SECOND_BUTTON_LIFT),
            kind = KitButtonKind.TONAL,
        )
    }
}

/** How far the second button of the empty state is drawn up: the block keeps 48 dp under its own button, which leaves a 16 dp gap. */
private val EMPTY_SECOND_BUTTON_LIFT = (-32).dp

private fun FeedFilter.label(): StringResource = when (this) {
    FeedFilter.ALL -> Res.string.video_all
    FeedFilter.TODAY -> Res.string.video_today
    FeedFilter.CONTINUE -> Res.string.video_shelf_continue
    FeedFilter.UNWATCHED -> Res.string.video_filter_unwatched
}

private fun FeedGroup.label(): StringResource = when (this) {
    FeedGroup.TODAY -> Res.string.video_today
    FeedGroup.YESTERDAY -> Res.string.video_yesterday
    FeedGroup.THIS_WEEK -> Res.string.video_this_week
    FeedGroup.THIS_MONTH -> Res.string.video_this_month
    FeedGroup.OLDER -> Res.string.video_older
}

/**
 * Reads the feed, and ends quietly when it cannot: a channel that fails is counted in the feed's state, and anything else that goes
 * wrong in the source must not take the page down. A cancelled refresh (the page left) is not swallowed.
 */
internal suspend fun SubscriptionFeed.refreshQuietly(force: Boolean) {
    try {
        refresh(force)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // the feed's state tells the page what failed
    }
}

/** The chips of the feed: everything, today's uploads, what you are part-way through, what you have not started. */
internal enum class FeedFilter { ALL, TODAY, CONTINUE, UNWATCHED }

/**
 * What the page decides about the feed that needs no window: which items a picked channel leaves, whether the Shorts shelf shows
 * and with what, how the strip is ordered, how the items are grouped under the day overlines. Pure.
 */
internal object SubscriptionsFeedPlan {
    /** The Shorts shelf shows from this many Shorts (spec 5.7 / the brief: hidden below 3). */
    const val SHELF_MIN_SHORTS = 3

    /**
     * The feed's items once each (a lazy list refuses two items with one key), those of [channelId] alone when one is picked. The
     * order is the feed's (newest upload first).
     */
    fun forChannel(items: List<VideoItem>, channelId: String?): List<VideoItem> {
        val once = items.distinctBy { it.id }
        return if (channelId == null) once else once.filter { it.channelId == channelId }
    }

    /**
     * [items] the chip [filter] leaves: uploaded since [startOfToday]; with a watched bar and not yet watched to the end ([watched] is
     * the history by video id); or never opened.
     */
    fun filtered(items: List<VideoItem>, filter: FeedFilter, watched: Map<String, HistoryEntry>, startOfToday: Long): List<VideoItem> = when (filter) {
        FeedFilter.ALL -> items
        FeedFilter.TODAY -> items.filter { (it.uploadedAtMs ?: Long.MIN_VALUE) >= startOfToday }
        FeedFilter.CONTINUE -> items.filter { item ->
            val entry = watched[item.id]
            val fraction = entry?.let { item.watchedFraction(it.positionMs, it.durationMs) }
            fraction != null && fraction < VideoMetrics.WATCHED_DONE
        }
        FeedFilter.UNWATCHED -> items.filter { it.id !in watched }
    }

    /**
     * The Shorts for the shelf, newest first: the items marked as Shorts, or nothing when "Hide Shorts" is on or there are fewer than
     * [SHELF_MIN_SHORTS]. An item without an upload time comes last.
     */
    fun shelf(items: List<VideoItem>, hideShorts: Boolean): List<VideoItem> {
        if (hideShorts) return emptyList()
        val shorts = items.filter { it.isShort }
        if (shorts.size < SHELF_MIN_SHORTS) return emptyList()
        return shorts.sortedByDescending { it.uploadedAtMs ?: Long.MIN_VALUE }
    }

    /**
     * The channels of the strip, the ones with the newest upload first: a channel's newest upload is the later of the newest item
     * of [items] that is not a premiere still to come, and the time the subscription itself recorded. Channels with no known upload
     * come last, by name (case ignored), as do ties.
     */
    fun stripOrder(channels: List<Subscription>, items: List<VideoItem>): List<Subscription> {
        val newest = HashMap<String, Long>()
        for (item in items) {
            val id = item.channelId ?: continue
            val at = item.uploadedAtMs ?: continue
            if (item.isUpcoming) continue
            val known = newest[id]
            if (known == null || at > known) newest[id] = at
        }
        fun newestOf(channel: Subscription): Long? {
            val fromFeed = newest[channel.channelId]
            val recorded = channel.lastUploadMs
            return if (fromFeed != null && recorded != null) maxOf(fromFeed, recorded) else fromFeed ?: recorded
        }
        return channels.sortedWith(
            compareByDescending<Subscription> { newestOf(it) != null }
                .thenByDescending { newestOf(it) ?: 0L }
                .thenBy { it.name.lowercase() },
        )
    }

    /** [items] under the day overlines as of [nowMs] in [zone] (the device's by default): [FeedMerge.groups] from the local midnight. */
    fun groups(items: List<VideoItem>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<FeedGroup, List<VideoItem>>> =
        FeedMerge.groups(items, FeedDay.startOfToday(nowMs, zone))
}

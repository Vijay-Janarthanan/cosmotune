package com.cosmotune.video.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.ui.blocks.rememberSubscribeToggle
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channel_hide_watched
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.ui.watch.rememberWatchedFraction
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.actions.ActionToastKind
import com.cosmotune.video.ui.actions.VideoActionHandler
import com.cosmotune.video.ui.actions.VideoActionsState
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberShareVideo
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.PlaylistRow
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoGrid
import com.cosmotune.video.ui.blocks.VideoGridSpec
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shorts.ShortsGrid
import com.cosmotune.video.ui.shorts.ShortsShelf
import com.cosmotune.video.ui.shorts.openShort
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** A channel's page is a grid from this width. */
private val GRID_MIN = 600.dp

/** How many Shorts the Videos tab's shelf shows (spec 5.5: the first 10 of the Shorts tab). */
internal const val SHORTS_SHELF_COUNT = 10

/** "Download the latest" offers 5, 10, 25 or 50 uploads (spec 5.5), so the tab is read until it holds this many. */
internal const val DOWNLOAD_LATEST_MAX = 50

/**
 * A channel's page (DESIGN_SYSTEM.md 6.2), opened on [route]'s tab. Phone: one list, from the top: the banner edge to edge, 16:5 (fading into the
 * page over its lower 40 %), the head (avatar over the banner, name, "@handle · subscribers · videos", a line of the description), the
 * actions (Subscribe, Share, Download the latest), the tab chips (Videos, Shorts unless hidden, Live, Playlists, About, for the tabs the
 * channel has), then the tab: on Videos a shelf of the channel's first Shorts above the video rows, on Shorts the Shorts grid, on Live the
 * rows, on Playlists the playlist rows, on About its text. A tab loads its pages as the end comes into view. A page of 600 dp and up (the
 * desktop, a tablet): the banner is 180 dp inside the page margin, the avatar 96, and the cards stand in a 16:9 grid.
 *
 * The data is a [ChannelModel], kept for ten minutes after the page is left so that coming back finds the lists as they were.
 */
@Composable
fun ChannelPage(route: VideoRoute.Channel, contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val model = remember(graph.source, route.channelId) { ChannelModels.obtain(graph.source, route.channelId) }
    LaunchedEffect(model) { model.start() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The grid starts at 600 dp here (DESIGN_SYSTEM.md 6.2), below the shell's 840.
        val grid = wide || maxWidth >= GRID_MIN
        // The colour card stands under the bar on every width.
        val padding = contentPadding
        when (val header = model.header) {
            is Loaded.Loading -> ChannelLoading(padding, grid, maxWidth.value)
            is Loaded.Failed -> ChannelFailed(contentPadding, onRetry = model::retry)
            is Loaded.Ready -> ChannelReady(route, model, header.value, padding, grid, maxWidth.value)
        }
    }
}

// ---- the page while the header loads, and when it failed --------------------------------------------------------------------------

@Composable
private fun ChannelLoading(contentPadding: PaddingValues, wide: Boolean, widthDp: Float) {
    PageHost(wide, widthDp, contentPadding, rememberLazyListState(), rememberLazyGridState()) { line ->
        full("skeleton-head", "head") { ChannelHeaderSkeleton(wide, widthDp, line.own) }
        cardSkeletons("channel")
    }
}

@Composable
private fun ChannelFailed(contentPadding: PaddingValues, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
        ListFailed(onRetry)
    }
}

// ---- the page ---------------------------------------------------------------------------------------------------------------------

/** The video whose sheet is open and the list it was in (a Short opens the pager with that list's Shorts). */
private class MenuTarget(val item: VideoItem, val list: List<VideoItem>)

@Composable
private fun ChannelReady(
    route: VideoRoute.Channel,
    model: ChannelModel,
    page: ChannelPage,
    contentPadding: PaddingValues,
    wide: Boolean,
    widthDp: Float,
) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val actions = rememberVideoActions()
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState()
    val subscriptions by graph.library.subscriptions.collectAsState()
    val history by graph.library.history.collectAsState()
    val watchedIds = remember(history) { history.filter { it.isWatched }.mapTo(HashSet()) { it.item.id } }
    val downloaded = rememberDownloadedIds()
    val sharer = rememberShareVideo(onCopied = { VideoActionsState.shared.showToast(ActionToastKind.LINK_COPIED) })
    val channel = page.channel
    val hideShorts = settings.hideShorts

    // The tabs the source does not list (Podcasts, Courses, Releases, Posts) and the search inside the channel.
    val extras = remember(graph, channel.id) { ChannelExtrasModel(graph.channelExtras, channel.id, scope) }
    LaunchedEffect(extras) { extras.start() }
    val sections = remember(page.tabs, hideShorts, extras.tabs) { ChannelExtrasModel.withExtras(ChannelSections.of(page.tabs, hideShorts), extras.tabs.keys) }
    var wanted by rememberSaveable { mutableStateOf(ChannelSections.sectionOf(route.tab).name) }
    val section = ChannelSections.resolve(ChannelSections.parse(wanted, ChannelSections.sectionOf(route.tab)), sections)
    val shortsOffered = ChannelSection.SHORTS in sections

    // The tab on show loads; the Videos tab also reads the Shorts tab, for its shelf.
    LaunchedEffect(model, section, shortsOffered) {
        ChannelSections.tabOf(section)?.let { model.tab(it).start() }
        if (section == ChannelSection.VIDEOS && shortsOffered) model.tab(ChannelTab.SHORTS).start()
    }

    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val subscribeToggle = rememberSubscribeToggle()
    val subscribed = subscriptions.any { it.channelId == channel.id || it.channelId == route.channelId }
    val ui = ChannelUi(
        model = model,
        page = page,
        wide = wide,
        widthDp = widthDp,
        sections = sections,
        section = section,
        shortsOffered = shortsOffered,
        hideShorts = hideShorts,
        subscribed = subscribed,
        preparing = preparing,
        downloaded = downloaded,
        actions = actions,
        onSelect = { wanted = it.name },
        onSubscribe = { subscribeToggle(channel, subscribed) },
        onShare = { sharer.share(channel.name, ShareLinks.channel(channel.id)) },
        onDownload = {
            if (!preparing) {
                preparing = true
                scope.launch {
                    try {
                        val uploads = model.uploads(ChannelSections.uploadsTab(section), DOWNLOAD_LATEST_MAX)
                        val wantedUploads = ShortsFilter.filter(uploads, hideShorts) { it }
                        actions.downloadBatch(DownloadBatchInfo(channel.id, BatchKind.CHANNEL_LATEST, channel.name), wantedUploads, latestPicker = true)
                    } finally {
                        preparing = false
                    }
                }
            }
        },
        onMore = { item, list -> menu = MenuTarget(item, list) },
        onOpenShort = { item, list -> navigator.openShort(item, ShortsOrigin.Channel(channel.id), list) },
        onOpenPlaylist = { navigator.push(VideoRoute.Playlist(it)) },
        extras = extras,
        watchedIds = watchedIds,
        hideWatched = settings.channelHideWatched,
        onHideWatched = { graph.settings.update { it.copy(channelHideWatched = !it.channelHideWatched) } },
        bell = if (subscribed) channel.id in settings.bellChannels else null,
        onBell = {
            graph.settings.update {
                it.copy(bellChannels = if (channel.id in it.bellChannels) it.bellChannels - channel.id else it.bellChannels + channel.id)
            }
        },
    )

    if (section == ChannelSection.SHORTS) {
        ShortsTab(ui, contentPadding)
    } else {
        PageHost(wide, widthDp, contentPadding, rememberLazyListState(), rememberLazyGridState()) { line -> channelItems(ui, line) }
    }

    menu?.let { target ->
        VideoActionsSheet(
            item = target.item,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(target.item, action, VideoSheetContext.DEFAULT, list = target.list) },
            onDismiss = { menu = null },
            downloaded = downloaded.value.contains(target.item.id),
        )
    }
}

/** Everything the page's items need, in one place (rebuilt whenever one of them changes). */
private class ChannelUi(
    val model: ChannelModel,
    val page: ChannelPage,
    val wide: Boolean,
    val widthDp: Float,
    val sections: List<ChannelSection>,
    val section: ChannelSection,
    val shortsOffered: Boolean,
    val hideShorts: Boolean,
    val subscribed: Boolean,
    val preparing: Boolean,
    val downloaded: androidx.compose.runtime.State<Set<String>>,
    val actions: VideoActionHandler,
    val onSelect: (ChannelSection) -> Unit,
    val onSubscribe: () -> Unit,
    val onShare: () -> Unit,
    val onDownload: () -> Unit,
    val onMore: (VideoItem, List<VideoItem>) -> Unit,
    val onOpenShort: (VideoItem, List<VideoItem>) -> Unit,
    val onOpenPlaylist: (String) -> Unit,
    val extras: ChannelExtrasModel? = null,
    /** The videos watched to the end (the history's), for Hide watched. */
    val watchedIds: Set<String> = emptySet(),
    /** Whether the Videos and Live tabs leave out what was watched to the end. */
    val hideWatched: Boolean = false,
    val onHideWatched: () -> Unit = {},
    /** The bell beside Subscribe: on, off, or null while the channel is not followed (no bell then). */
    val bell: Boolean? = null,
    val onBell: () -> Unit = {},
)

/**
 * The page's list on the phone, its grid on a wide page: the items [build] writes go to a `LazyColumn` (padded by [contentPadding]) or a
 * `LazyVerticalGrid` (the page's 28 dp at the sides, [VideoGridSpec] for the columns), with the [PageLine] of whichever it is.
 */
@Composable
private fun PageHost(
    wide: Boolean,
    widthDp: Float,
    contentPadding: PaddingValues,
    listState: LazyListState,
    gridState: LazyGridState,
    build: PageItems.(PageLine) -> Unit,
) {
    if (wide) {
        VideoGrid(
            spec = VideoGridSpec.of(widthDp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = VideoMetrics.DESKTOP_MARGIN_DP.dp,
                end = VideoMetrics.DESKTOP_MARGIN_DP.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            GridPageItems(this).build(PageLine.WideGrid)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
            ListPageItems(this).build(PageLine.Phone)
        }
    }
}

// ---- the head: the same in the list and above the Shorts grid ------------------------------------------------------------------

@Composable
private fun HeaderParts(ui: ChannelUi, line: PageLine) {
    Column(Modifier.fillMaxWidth()) {
        // Play in the card's bite plays the latest upload (the first of the Videos tab, else of the tab on show).
        val uploads = ShortsFilter.filter(videosOf(ui.model.tab(ChannelSections.tabOf(ChannelSection.VIDEOS) ?: ChannelTab.VIDEOS).items), ui.hideShorts) { it }
        val latest = uploads.firstOrNull()
        ChannelCard(
            channel = ui.page.channel, bannerUrl = ui.page.bannerUrl, subscribed = ui.subscribed, wide = ui.wide, margin = line.own,
            preparing = ui.preparing, onSubscribe = ui.onSubscribe, onShare = ui.onShare, onDownload = ui.onDownload,
            onAbout = if (ChannelSection.ABOUT in ui.sections) ({ ui.onSelect(ChannelSection.ABOUT) }) else null,
            onPlayLatest = latest?.let { first -> { ui.actions.play(first, uploads) } },
            bell = ui.bell, onBell = ui.onBell,
        )
        ChannelTabChips(ui.sections, ui.section, ui.onSelect, line.fit(Modifier.padding(top = 16.dp, bottom = 8.dp)))
    }
}

// ---- the items --------------------------------------------------------------------------------------------------------------------

private fun PageItems.channelItems(ui: ChannelUi, line: PageLine) {
    // one item for the whole head: a wide grid puts its row gap (28 dp) between items, and the head's blocks have rhythm of their own
    full("header", "header") { HeaderParts(ui, line) }
    when (ui.section) {
        ChannelSection.VIDEOS, ChannelSection.LIVE -> uploadItems(ui, line)
        ChannelSection.PLAYLISTS -> playlistItems(ui, line)
        ChannelSection.ABOUT -> full("about", "about") { ChannelAbout(ui.page, line, ui.wide) }
        ChannelSection.SHORTS -> Unit // the Shorts grid is its own page (ShortsTab)
        ChannelSection.PODCASTS, ChannelSection.COURSES, ChannelSection.RELEASES -> {
            val tab = ChannelExtrasModel.tabOf(ui.section)
            val paged = tab?.let { ui.extras?.list(it) }
            if (paged != null) extraListItems(paged, "extra-${ui.section}", line, ui.wide, ui.onOpenPlaylist)
        }
        ChannelSection.POSTS -> ui.extras?.posts()?.let { paged ->
            postItems(paged, line, onPlay = { ui.actions.play(it, listOf(it)) }, onMore = { ui.onMore(it, listOf(it)) })
        }
        ChannelSection.SEARCH -> ui.extras?.let { extras -> channelSearchItems(extras, line, onPlay = { item, list -> ui.actions.play(item, list) }, onMore = ui.onMore) }
    }
}

/** The Videos and Live tabs: on Videos the Shorts shelf first, then a card for each video, then what the list's loading says. */
private fun PageItems.uploadItems(ui: ChannelUi, line: PageLine) {
    val tab = ChannelSections.tabOf(ui.section) ?: return
    val paged = ui.model.tab(tab)
    val all = ShortsFilter.filter(videosOf(paged.items), ui.hideShorts) { it }
    val watchedIds = ui.watchedIds
    val videos = if (ui.hideWatched) all.filter { it.id !in watchedIds } else all
    full("hide-watched", "chips") {
        com.cosmotune.ui.orbit.OrbitSelectChip(
            stringResource(Res.string.video_channel_hide_watched), ui.hideWatched, com.cosmotune.ui.orbit.orbitFonts(), com.cosmotune.ui.orbit.orbitLook(),
            onClick = ui.onHideWatched, modifier = line.fit(Modifier.padding(bottom = 8.dp)),
        )
    }
    if (ui.section == ChannelSection.VIDEOS && ui.shortsOffered) {
        val shorts = videosOf(ui.model.tab(ChannelTab.SHORTS).items)
        if (shorts.isNotEmpty()) {
            full("shorts-shelf", "shorts") {
                ShortsShelf(
                    title = stringResource(Res.string.video_shorts),
                    items = shorts.take(SHORTS_SHELF_COUNT),
                    onOpen = { ui.onOpenShort(it, shorts) },
                    onSeeAll = { ui.onSelect(ChannelSection.SHORTS) },
                    modifier = line.fit(Modifier),
                )
            }
        }
    }
    val keys = uniqueKeys(videos)
    cells(videos, key = { index, _ -> "v:${keys[index]}" }, contentType = "video") { item ->
        val onClick = { ui.actions.play(item, videos) }
        val onMore = { ui.onMore(item, videos) }
        val isDownloaded = ui.downloaded.value.contains(item.id)
        // Every upload shows how far it was watched.
        val watched = rememberWatchedFraction(item)
        if (wide) BrandedVideoCell(item, isDownloaded, onClick, onMore, watchedFraction = watched) else BrandedVideoRow(item, isDownloaded, onClick, onMore, watchedFraction = watched)
    }
    pagedStatus("tab-$tab", paged)
}

/** The Playlists tab: a [PlaylistRow] for each playlist the channel has. */
private fun PageItems.playlistItems(ui: ChannelUi, line: PageLine) {
    val paged = ui.model.tab(ChannelTab.PLAYLISTS)
    val playlists = paged.items.mapNotNull { (it as? ListItem.Playlist)?.item }.distinctBy { it.id }
    rows(playlists, key = { _, playlist -> "p:${playlist.id}" }, contentType = "playlist") { playlist ->
        PlaylistRow(item = playlist, onClick = { ui.onOpenPlaylist(playlist.id) }, modifier = line.fit(Modifier), desktop = ui.wide)
    }
    pagedStatus("tab-${ChannelTab.PLAYLISTS}", paged)
}

// ---- the Shorts tab ---------------------------------------------------------------------------------------------------------------

/**
 * Tracks how far the content under a header that is not part of it has been scrolled, from what the content consumed: the header
 * is moved up by the same distance (its layer, read while drawing), so it scrolls with the content as if it were its first item.
 */
@Stable
private class HeaderScroll(private val scrolled: MutableState<Float>) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y != 0f) scrolled.value = (scrolled.value - consumed.y).coerceAtLeast(0f)
        return Offset.Zero
    }
}

/**
 * The Shorts tab: [ShortsGrid] scrolls by itself and takes no header, so the head stands over it, its height given to the grid as top
 * padding and moved up with the grid's scrolling ([HeaderScroll]). The status of the load (skeleton, failure, nothing) stands in the
 * head, under the chips. A later page that failed is asked for again by the next call for more.
 */
@Composable
private fun ShortsTab(ui: ChannelUi, contentPadding: PaddingValues) {
    val density = LocalDensity.current
    val paged = ui.model.tab(ChannelTab.SHORTS)
    val shorts = videosOf(paged.items)
    var headerPx by remember { mutableIntStateOf(0) }
    val scrolled = rememberSaveable { mutableStateOf(0f) }
    val connection = remember(scrolled) { HeaderScroll(scrolled) }
    val line = if (ui.wide) PageLine.WideColumn else PageLine.Phone
    val gridPadding = PaddingValues(
        start = line.contentLine,
        end = line.contentLine,
        top = with(density) { headerPx.toDp() },
        bottom = contentPadding.calculateBottomPadding(),
    )
    Box(Modifier.fillMaxSize().nestedScroll(connection)) {
        ShortsGrid(
            items = shorts,
            onOpen = { ui.onOpenShort(it, shorts) },
            onLoadMore = { if (paged.state == PagedState.FAILED_MORE) paged.retry() else paged.loadMore() },
            contentPadding = gridPadding,
            wide = ui.wide,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .graphicsLayer { translationY = -scrolled.value },
        ) {
            Spacer(Modifier.height(contentPadding.calculateTopPadding()))
            HeaderParts(ui, line)
            ShortsStatus(paged, line)
        }
    }
}

/** What the Shorts tab says under the chips while it has nothing to show: the skeleton of a row of cards, the failure, or "nothing here". */
@Composable
private fun ShortsStatus(paged: PagedItems<ListItem>, line: PageLine) {
    when (paged.state) {
        PagedState.IDLE, PagedState.LOADING_FIRST -> Row(
            Modifier.fillMaxWidth().padding(start = line.contentLine, end = line.contentLine, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(VideoMetrics.SHORTS_GRID_GAP_DP.dp),
        ) {
            repeat(VideoMetrics.SHORTS_GRID_COLUMNS_PHONE) {
                CosmoShimmer(Modifier.weight(1f).aspectRatio(9f / 16f), SHORTS_SKELETON_SHAPE)
            }
        }
        PagedState.FAILED_FIRST -> ListFailed(paged::retry)
        PagedState.READY -> if (paged.items.isEmpty()) DefaultEmpty()
        else -> Unit
    }
}

private val SHORTS_SKELETON_SHAPE = RoundedCornerShape(CosmoRadii.rMd)

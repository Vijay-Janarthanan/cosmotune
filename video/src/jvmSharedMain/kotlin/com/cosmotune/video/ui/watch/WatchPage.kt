package com.cosmotune.video.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.ui.sheets.CosmoSheetScrim
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.downloads.DownloadController
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.readLocalBundle
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.ui.blocks.rememberSubscribeToggle
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.Description
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.PictureInPictureState
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.player.rememberPictureInPicture
import com.cosmotune.video.segments.Votes
import com.cosmotune.video.segments.VotesProvider
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.actions.VideoActionHandler
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.MetaLine
import com.cosmotune.video.ui.blocks.VideoAction
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.rememberNowMs
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.video.ui.comments.CommentsPanel
import com.cosmotune.video.ui.comments.CommentsPreviewTile
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.VideoPlayerBox
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoBackHandler
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The watch page (spec 5.4), the page of [VideoRoute.Watch]: it starts the video ([StartWatching]: `play` unless the video is
 * already the one on, a seek to the route's start when it is), follows the playback when it moves on by itself, plays the download
 * when the source cannot be reached, and draws one of three layouts:
 *
 * - the phone's page: the player under the status bar on a black band, `VideoMetrics.playerHeightDp` tall, and under it one column
 *   (B): [WatchMeta], [DescriptionTile], [CommentsPreviewTile] and "Up next"; the description sheet and the comments sheet stand
 *   under the player when open;
 * - full screen (the button, a swipe up, or the phone turned to landscape): the video alone in its own window ([VideoFullWindow]),
 *   `VideoPlayerBox(layout = FULLSCREEN)`, landscape for a landscape video, Back leaves it;
 * - [wide] (840 dp and up, always on the desktop): [WatchPageWide].
 *
 * Picture-in-picture (spec 6.2): auto-enter is on while the setting is, the video plays and this page is on top, with the video's
 * shape and the player's bounds; off while paused and when the page leaves. The activity stopping (not in picture-in-picture) sends
 * the video to the background (`onUiVisible(false)`), and starting brings it back. [contentPadding] is the shell's (the floating
 * chrome's room at the bottom); the page draws its own top.
 */
@Composable
fun WatchPage(route: VideoRoute.Watch, contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val playback = graph.playback
    val state by playback.state.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val pip = rememberPictureInPicture()

    StartWatching(route, playback, navigator)
    PlayDownloadWhenOffline(route, playback, graph.downloads)
    WatchLifecycle(playback, pip)
    DisposableEffect(Unit) {
        WatchSession.pages++
        onDispose { if (--WatchSession.pages == 0) WatchSession.leftEveryPage() }
    }

    // Picture-in-picture: auto-enter follows the setting, the playing video and this page on top; off when the page leaves.
    val pipUpdater = remember(pip) { PipUpdater(pip) }
    val pipEnabled = WatchRules.pipEnabled(settings.autoPip, state, onTop = navigator.top == route)
    val aspect = state.aspect
    SideEffect { pipUpdater.update(enabled = pipEnabled, aspect = aspect) }
    DisposableEffect(pipUpdater) { onDispose { pipUpdater.leave() } }
    val enterPip = rememberPipEntry(pip)

    val view = rememberWatchView(route.videoId, state, settings)
    val actions = rememberVideoActions()
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }
    val callbacks = watchCallbacks(view, state.details, actions, playback, navigator, rememberSubscribeToggle()) { sheetFor = it }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val phoneLandscape = WatchRules.phoneLandscape(maxWidth.value, maxHeight.value, rotationEntersFullScreen)
        val fullscreen = WatchRules.fullscreenShown(WatchSession.fullscreen, phoneLandscape, WatchSession.portraitLock)
        FullscreenController(
            orientation = WatchRules.orientation(WatchSession.fullscreen, landscapeVideo = aspect >= 1f, WatchSession.portraitLock),
            lightStatusBar = !wide && !fullscreen,
        )
        val enterFullscreen = {
            WatchSession.fullscreen = true
            WatchSession.portraitLock = false
        }
        val exitFullscreen = {
            WatchSession.fullscreen = false
            // Left while the phone is turned: the page comes back upright, and stays so until it leaves.
            if (phoneLandscape) WatchSession.portraitLock = true
        }
        when {
            fullscreen -> WatchFullscreen(playback, graph.surface, view.title, exitFullscreen, enterPip, pipUpdater)
            wide -> WatchPageWide(view, playback, graph.surface, contentPadding, maxWidth, maxHeight, aspect, callbacks, pipUpdater)
            else -> WatchPagePhone(
                view = view,
                playback = playback,
                surface = graph.surface,
                contentPadding = contentPadding,
                width = maxWidth,
                height = maxHeight,
                aspect = aspect,
                callbacks = callbacks,
                pipUpdater = pipUpdater,
                onCollapse = if (navigator.canPop) ({ navigator.pop(); Unit }) else null,
                onEnterFullscreen = enterFullscreen,
                onEnterPip = enterPip,
            )
        }
    }

    sheetFor?.let { item ->
        val downloaded by rememberDownloadedIds()
        VideoActionsSheet(
            item = item,
            context = VideoSheetContext.DEFAULT,
            onAction = { action -> actions.handle(item, action, VideoSheetContext.DEFAULT, list = view.related) },
            onDismiss = { sheetFor = null },
            downloaded = item.id in downloaded,
        )
    }
}

// ---- what the page shows ----------------------------------------------------------------------------------------------------

/**
 * What both layouts show (one value per recomposition): [info] (null while nothing is known of the video yet: [loading] draws the
 * shimmer), the [title] as shown and the [originalTitle] DeArrow replaced, the [description] (null when empty: a download keeps
 * none), the [chapters], the [related] videos (Shorts left out with "Hide Shorts"; none offline), whether the page is [offline] (a
 * download playing from its bundle: no related videos, comments or dislike count, and no source call, spec 5.4 "Offline"), [autoplay] and
 * whether the comments show.
 */
@Immutable
internal data class WatchView(
    val videoId: String,
    val info: WatchInfo?,
    val title: String,
    val originalTitle: String?,
    val description: Description?,
    val chapters: List<Chapter>,
    val related: List<VideoItem>,
    val offline: Boolean,
    val autoplay: Boolean,
    val commentsShown: Boolean,
    val loading: Boolean,
)

@Composable
private fun rememberWatchView(videoId: String, state: PlaybackUiState, settings: VideoSettings): WatchView {
    val graph = LocalVideoGraph.current
    val own = state.details?.takeIf { it.item.id == videoId }
    // A download plays with a stand-in for the details (no description, no related videos): the page is offline then and calls
    // nothing of the source for it (spec 5.4 "Offline"; the video is already the playback's, from its local bundle).
    val offline = own != null && WatchRules.isOfflineStandIn(own)
    val details = own
    val item = details?.item ?: state.item?.takeIf { it.id == videoId }
    val votes by produceState<Votes?>(null, videoId, settings.dislikes, offline) {
        value = if (settings.dislikes && !offline) votesOrNull(graph.votes, videoId) else null
    }
    val watchLater by graph.library.watchLater.collectAsState()
    val subscriptions by graph.library.subscriptions.collectAsState()
    val downloadedLabel by rememberDownloadedLabel(graph.downloads, videoId)
    val title = if (item != null) rememberVideoTitle(item) else ""
    val related = remember(details?.related, settings.hideShorts) { WatchRules.relatedVideos(details, settings.hideShorts) }
    val chapters = details?.chapters.orEmpty()
    val info = item?.let {
        WatchInfo(
            item = it,
            title = title,
            likes = details?.likeCount ?: votes?.likes,
            dislikes = votes?.dislikes,
            subscriberCount = details?.subscriberCount,
            subscribed = it.channelId != null && subscriptions.any { s -> s.channelId == it.channelId },
            inWatchLater = watchLater.any { v -> v.id == it.id },
            downloadedLabel = downloadedLabel,
        )
    }
    return WatchView(
        videoId = videoId,
        info = info,
        title = title,
        originalTitle = item?.title?.takeIf { it != title },
        description = details?.description?.takeIf { it.text.isNotBlank() },
        chapters = chapters,
        related = if (offline) emptyList() else related,
        offline = offline,
        autoplay = settings.autoplay,
        commentsShown = !offline,
        loading = item == null && state.error == null,
    )
}

/** The quality label of [videoId]'s finished download ("720p"), or null; it changes when a download finishes, not with its progress. */
@Composable
private fun rememberDownloadedLabel(downloads: DownloadController, videoId: String): State<String?> {
    val flow = remember(downloads, videoId) {
        downloads.downloads.map { list -> list.firstOrNull { it.item.id == videoId && it.state is DownloadState.Done }?.choice?.label }
            .distinctUntilChanged()
    }
    val initial = remember(downloads, videoId) {
        downloads.downloads.value.firstOrNull { it.item.id == videoId && it.state is DownloadState.Done }?.choice?.label
    }
    return flow.collectAsState(initial)
}

private suspend fun votesOrNull(votes: VotesProvider, videoId: String): Votes? = try {
    votes.votes(videoId)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

/** What the blocks do: the chips, the channel, a seek, a related video's tap and "more", the Autoplay switch. */
@Stable
internal class WatchCallbacks(
    val onChip: (WatchChip) -> Unit,
    val onOpenChannel: () -> Unit,
    val onSubscribe: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onPlay: (VideoItem) -> Unit,
    val onMore: (VideoItem) -> Unit,
    val onAutoplay: (Boolean) -> Unit,
)

/**
 * The page's callbacks: each chip through the shared action handler (Download asks the download sheet, or opens Downloads when the
 * video is downloaded), the channel page, follow or unfollow, a seek, a related video played with the list (a Short opens the pager),
 * its sheet ([onSheet]), and the Autoplay switch, which the playback stores in the settings too.
 */
private fun watchCallbacks(
    view: WatchView,
    details: VideoDetails?,
    actions: VideoActionHandler,
    playback: VideoPlayback,
    navigator: VideoNavigator,
    subscribeToggle: (ChannelItem, Boolean) -> Unit,
    onSheet: (VideoItem) -> Unit,
): WatchCallbacks {
    val info = view.info
    val own = details?.takeIf { it.item.id == view.videoId }
    fun act(action: VideoAction) {
        if (info != null) actions.handle(info.item, action, VideoSheetContext.DEFAULT, own)
    }
    return WatchCallbacks(
        onChip = { chip ->
            when (chip) {
                WatchChip.SAVE -> act(VideoAction.SaveToPlaylist)
                WatchChip.WATCH_LATER -> act(VideoAction.WatchLater)
                WatchChip.DOWNLOAD -> when {
                    info == null -> Unit
                    info.downloadedLabel != null -> navigator.push(VideoRoute.Downloads)
                    else -> actions.download(info.item)
                }
                WatchChip.SHARE -> act(VideoAction.Share)
                WatchChip.PLAY_AS_SONG -> act(VideoAction.PlayAsSong)
                WatchChip.THEATRE -> WatchSession.theatre = !WatchSession.theatre
                WatchChip.LIKES, WatchChip.DISLIKES -> Unit
            }
        },
        onOpenChannel = { info?.item?.channelId?.let { navigator.push(VideoRoute.Channel(it)) } },
        onSubscribe = {
            val item = info?.item
            val channelId = item?.channelId
            if (item != null && channelId != null) {
                subscribeToggle(ChannelItem(channelId, item.channelName.orEmpty(), item.channelAvatarUrl, info.subscriberCount, null, null), info.subscribed)
            }
        },
        onSeek = { ms -> playback.seekTo(ms) },
        onPlay = { item -> actions.play(item, view.related) },
        onMore = onSheet,
        onAutoplay = { on -> playback.setAutoplay(on) },
    )
}

// ---- the phone's page --------------------------------------------------------------------------------------------------------

/** The page's side margin (spec 5.0). */
private val PHONE_SIDE = CosmoSpacing.margin

/**
 * The phone's page (spec 5.4 "Phone, portrait"): the black band with the player under the status bar, then the column. The
 * description sheet and the comments sheet open under the player, and Back closes them.
 */
@Composable
private fun WatchPagePhone(
    view: WatchView,
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    contentPadding: PaddingValues,
    width: Dp,
    height: Dp,
    aspect: Float,
    callbacks: WatchCallbacks,
    pipUpdater: PipUpdater,
    onCollapse: (() -> Unit)?,
    onEnterFullscreen: () -> Unit,
    onEnterPip: (() -> Unit)?,
) {
    var descriptionOpen by rememberSaveable(view.videoId) { mutableStateOf(false) }
    var commentsOpen by rememberSaveable(view.videoId) { mutableStateOf(false) }
    VideoBackHandler(enabled = descriptionOpen || commentsOpen) {
        descriptionOpen = false
        commentsOpen = false
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val playerHeight = VideoMetrics.playerHeightDp(width.value, height.value, aspect).dp
    val downloaded = rememberDownloadedIds()
    val watched = rememberWatchedFractions(view.related)
    val onBounds = remember(pipUpdater) { { rect: Rect -> pipUpdater.bounds(rect) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The status bar's area and the bands around the picture are black (spec 5.4; the black of the player).
            Box(Modifier.fillMaxWidth().background(Color.Black).padding(top = statusTop)) {
                VideoPlayerBox(
                    playback = playback,
                    surface = surface,
                    layout = PlayerLayout.PAGE,
                    modifier = Modifier.fillMaxWidth().height(playerHeight),
                    onCollapse = onCollapse,
                    onToggleFullscreen = onEnterFullscreen,
                    onEnterPip = onEnterPip,
                    onBoundsChanged = onBounds,
                )
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                watchBlocks(
                    view = view,
                    callbacks = callbacks,
                    onOpenDescription = { descriptionOpen = true },
                    onOpenComments = { commentsOpen = true },
                )
                if (view.related.isNotEmpty()) {
                    item(key = "upnext") { UpNextHeader(view.autoplay, callbacks.onAutoplay) }
                    upNextRows(view.related, watched, downloaded, desktop = false, onPlay = callbacks.onPlay, onMore = callbacks.onMore)
                }
            }
        }
        // The sheets stand between the player and the shell's floating chrome, over the sheets' 40 % black scrim on the page (the
        // documented exception), so the page does not show through their glass; a tap on the scrim closes them.
        val top = statusTop + playerHeight
        val panel = Modifier.padding(top = top, bottom = contentPadding.calculateBottomPadding())
        if ((descriptionOpen && view.info != null) || (commentsOpen && view.commentsShown)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = top)
                    .background(CosmoSheetScrim)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            descriptionOpen = false
                            commentsOpen = false
                        }
                    },
            )
        }
        val info = view.info
        if (descriptionOpen && info != null) {
            DescriptionSheet(
                title = view.title,
                originalTitle = view.originalTitle,
                meta = MetaLine(info.item, rememberNowMs(), showChannel = false),
                description = view.description ?: Description("", emptyList()),
                chapters = view.chapters,
                onSeek = callbacks.onSeek,
                onDismiss = { descriptionOpen = false },
                modifier = panel,
            )
        }
        if (commentsOpen && view.commentsShown) {
            CommentsPanel(
                videoId = view.videoId,
                sheet = true,
                onDismiss = { commentsOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(top = top, bottom = contentPadding.calculateBottomPadding()),
            )
        }
    }
}

/** The phone's blocks under the player: the meta (or its shimmer while loading), the description tile, the comments tile. */
private fun LazyListScope.watchBlocks(
    view: WatchView,
    callbacks: WatchCallbacks,
    onOpenDescription: () -> Unit,
    onOpenComments: () -> Unit,
) {
    val info = view.info
    if (info == null) {
        if (view.loading) item(key = "loading") { MetaShimmer(PHONE_SIDE) }
        return
    }
    item(key = "meta") {
        WatchMeta(
            info = info,
            wide = false,
            theatre = false,
            sidePadding = PHONE_SIDE,
            onOpenDescription = onOpenDescription,
            onChip = callbacks.onChip,
            onOpenChannel = callbacks.onOpenChannel,
            onSubscribe = callbacks.onSubscribe,
        )
    }
    view.description?.let { description ->
        item(key = "description") { DescriptionTile(description, onOpenDescription, PHONE_SIDE) }
    }
    if (view.commentsShown) {
        item(key = "comments") { CommentsPreviewTile(videoId = view.videoId, onOpen = onOpenComments, modifier = Modifier.fillMaxWidth()) }
    }
}

/** The title and meta lines in the shape they will take, while the video's details load (spec 5.0: `CosmoShimmer`). */
@Composable
internal fun MetaShimmer(sidePadding: Dp) {
    val shape = RoundedCornerShape(CosmoRadii.xs)
    Column(Modifier.fillMaxWidth().padding(start = sidePadding, end = sidePadding, top = 12.dp)) {
        CosmoShimmer(Modifier.fillMaxWidth(SHIMMER_TITLE_SHARE).height(20.dp), shape)
        CosmoShimmer(Modifier.padding(top = 10.dp).fillMaxWidth(SHIMMER_META_SHARE).height(14.dp), shape)
        CosmoShimmer(Modifier.padding(top = 16.dp).fillMaxWidth().height(36.dp), shape)
    }
}

private const val SHIMMER_TITLE_SHARE = 0.85f
private const val SHIMMER_META_SHARE = 0.5f

// ---- full screen -------------------------------------------------------------------------------------------------------------

/**
 * Full screen (spec 5.4): the page keeps black under it, and the video alone stands in its own window: `VideoPlayerBox(layout =
 * FULLSCREEN)`, whose full-screen button and Back ([onExit]) leave, with picture-in-picture's button where the device has it.
 */
@Composable
private fun WatchFullscreen(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    title: String,
    onExit: () -> Unit,
    onEnterPip: (() -> Unit)?,
    pipUpdater: PipUpdater,
) {
    Box(Modifier.fillMaxSize().background(Color.Black))
    val onBounds = remember(pipUpdater) { { rect: Rect -> pipUpdater.bounds(rect) } }
    VideoFullWindow(onExit = onExit) {
        FullscreenContent(playback, surface, title.takeIf { it.isNotEmpty() }, onExit, onEnterPip, onBounds)
    }
}

/** What the full-screen window holds: only the player, in its full-screen layout. */
@Composable
internal fun FullscreenContent(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    title: String?,
    onExit: () -> Unit,
    onEnterPip: (() -> Unit)?,
    onBoundsChanged: (Rect) -> Unit,
) {
    VideoPlayerBox(
        playback = playback,
        surface = surface,
        layout = PlayerLayout.FULLSCREEN,
        modifier = Modifier.fillMaxSize(),
        onToggleFullscreen = onExit,
        onEnterPip = onEnterPip,
        onBoundsChanged = onBoundsChanged,
        title = title,
    )
}

// ---- picture-in-picture, the background and the start ------------------------------------------------------------------------

/**
 * What the shell draws while the window is in picture-in-picture (spec 6.2): `VideoPlayerBox(layout = FULLSCREEN, chrome = false)`,
 * the picture and its subtitles. The watch page is not composed meanwhile, so the window's life is followed here: the activity
 * stopping in picture-in-picture (the screen turned off) sends the video to the background and starting brings it back; leaving
 * picture-in-picture with the activity stopped means its window was closed, and the video pauses.
 */
@Composable
internal fun WatchPictureInPicture(playback: VideoPlayback, surface: VideoSurfaceProvider?, modifier: Modifier = Modifier) {
    VideoPlayerBox(playback, surface, PlayerLayout.FULLSCREEN, modifier, onToggleFullscreen = {}, chrome = false)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, playback) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> playback.onUiVisible(false)
                Lifecycle.Event.ON_START -> playback.onUiVisible(true)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (WatchRules.pipWindowClosed(lifecycle.currentState) && playback.state.value.playing) playback.togglePlayPause()
        }
    }
}

/**
 * The background (spec 6.1): the activity stopping while not in picture-in-picture sends the video to the background (audio only, or
 * paused when the setting says so); starting brings it back.
 */
@Composable
private fun WatchLifecycle(playback: VideoPlayback, pip: PictureInPictureState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, playback, pip) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!pip.active) playback.onUiVisible(false)
                Lifecycle.Event.ON_START -> playback.onUiVisible(true)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * Step 1 of spec 5.4: on entering the route, `play(videoId, startMs)` unless that video is already the one on (then only the route's
 * start is honoured, once); a page found again after the playback moved on by itself follows it. Afterwards the page follows the
 * playback whenever it moves on to another video by itself (autoplay, the queue, the notification's next): the top route becomes that
 * video's watch page.
 */
@Composable
private fun StartWatching(route: VideoRoute.Watch, playback: VideoPlayback, navigator: VideoNavigator) {
    LaunchedEffect(route, playback, navigator) {
        val now = playback.state.value
        when (val entry = WatchRules.entry(route, now.item?.id, now.short, WatchSession.lastRoute)) {
            WatchEntry.Play -> playback.play(route.videoId, route.startMs)
            is WatchEntry.Seek -> playback.seekTo(entry.ms)
            is WatchEntry.Follow -> {
                if (navigator.top == route) navigator.replaceTop(VideoRoute.Watch(entry.videoId))
                return@LaunchedEffect
            }
            WatchEntry.Keep -> Unit
        }
        WatchSession.lastRoute = route
        var seenOwn = false
        playback.state.map { it.item?.id to it.short }.distinctUntilChanged().collect { (id, short) ->
            if (id == route.videoId) seenOwn = true
            val next = WatchRules.follow(route, id, short, seenOwn, onTop = navigator.top == route) ?: return@collect
            val nextRoute = VideoRoute.Watch(next)
            WatchSession.lastRoute = nextRoute
            navigator.replaceTop(nextRoute)
        }
    }
}

/**
 * Step 10b: when the video cannot be loaded for want of a network and it has a finished download, the download plays instead
 * (`playLocal`, with its subtitles, chapters and segments read from its sidecar).
 */
@Composable
private fun PlayDownloadWhenOffline(route: VideoRoute.Watch, playback: VideoPlayback, downloads: DownloadController) {
    LaunchedEffect(route.videoId, playback, downloads) {
        playback.state
            .map { WatchRules.failedForNetwork(it, route.videoId) }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                val entry = downloads.downloads.value.firstOrNull { e -> e.item.id == route.videoId && e.state is DownloadState.Done } ?: return@collect
                val bundle = withContext(Dispatchers.IO) { entry.readLocalBundle() } ?: return@collect
                playback.playLocal(entry.item, bundle, route.startMs)
            }
    }
}

// ---- the rules (pure) ----------------------------------------------------------------------------------------------------------

/** What the page remembers between its instances in this process (one window draws Video at a time). Main thread. */
internal object WatchSession {
    /** The last route a watch page started or found playing: a page found again after the playback moved on follows it. */
    @Volatile
    var lastRoute: VideoRoute.Watch? = null

    /** Theatre mode on the wide page (key T), kept for the session as the user left it. */
    var theatre by mutableStateOf(false)

    /** The phone's full screen asked for by the button or a swipe up; it outlives a page that is replaced by the next video's. */
    var fullscreen by mutableStateOf(false)

    /** Full screen was left while the phone was turned: the page stays upright until the last watch page leaves. */
    var portraitLock by mutableStateOf(false)

    /** The wide page's full window (key F, the button, a double click); it outlives a page replaced by the next video's. */
    var fullWindow by mutableStateOf(false)

    /** Watch pages composed now (two while one fades into the next). */
    var pages = 0

    /**
     * The last watch page left (it was popped, or picture-in-picture took the window): full screen, the full window and the upright
     * hold end with it, so the next watch page opens as a page.
     */
    fun leftEveryPage() {
        fullscreen = false
        portraitLock = false
        fullWindow = false
    }
}

/** What the page does on entering its route (see [WatchRules.entry]). */
internal sealed interface WatchEntry {
    /** Play the route's video. */
    data object Play : WatchEntry

    /** The video is on: seek to the route's start. */
    data class Seek(val ms: Long) : WatchEntry

    /** The playback moved on from this page's video by itself: show the video that plays instead. */
    data class Follow(val videoId: String) : WatchEntry

    /** The video is on: leave it as it is (playing, paused, ended with Replay, or failed with Try again). */
    data object Keep : WatchEntry
}

/** The watch page's decisions, pure so they are tested without a window. */
internal object WatchRules {
    /** A phone turned to landscape has a window shorter than this (Material's compact height); a tablet's is taller. */
    const val COMPACT_HEIGHT_DP = 480f

    /** Theatre mode's player is at most this share of the page's height, so the columns below keep some room (not in the spec). */
    const val THEATRE_MAX_SHARE = 0.75f

    /**
     * On entering [route] with [currentId] on (a Short when [currentShort]) and [lastRoute] the route a page last started or found:
     * - the route's video is on: [WatchEntry.Seek] to the route's start when the route is new and has one, else [WatchEntry.Keep];
     * - another video is on, the route was this page's before, and it is not a Short: [WatchEntry.Follow] it (the page was left and
     *   found again after autoplay, the queue or picture-in-picture moved on);
     * - else [WatchEntry.Play].
     */
    fun entry(route: VideoRoute.Watch, currentId: String?, currentShort: Boolean, lastRoute: VideoRoute.Watch?): WatchEntry {
        val start = route.startMs
        return when {
            currentId == route.videoId -> if (route != lastRoute && start != null) WatchEntry.Seek(start) else WatchEntry.Keep
            currentId != null && route == lastRoute && !currentShort -> WatchEntry.Follow(currentId)
            else -> WatchEntry.Play
        }
    }

    /**
     * The video the page moves to while shown: [currentId] once the page has [seenOwn] video on and the playback moved on to another
     * video by itself, while the page is [onTop] and the new one is not a Short; else null.
     */
    fun follow(route: VideoRoute.Watch, currentId: String?, currentShort: Boolean, seenOwn: Boolean, onTop: Boolean): String? =
        if (seenOwn && onTop && currentId != null && currentId != route.videoId && !currentShort) currentId else null

    /** Whether leaving the window should enter picture-in-picture (spec 6.2): the setting, the video playing, the page on top. */
    fun pipEnabled(autoPip: Boolean, state: PlaybackUiState, onTop: Boolean): Boolean =
        autoPip && onTop && state.item != null && state.playing &&
            (state.status == PlayerStatus.READY || state.status == PlayerStatus.BUFFERING)

    /** Picture-in-picture ended with the activity below started: its window was closed (Android's documented sign). */
    fun pipWindowClosed(state: Lifecycle.State): Boolean = !state.isAtLeast(Lifecycle.State.STARTED)

    /** Whether a window [widthDp] x [heightDp] is a phone turned to landscape (where [rotates], the phone): wider than tall, and short. */
    fun phoneLandscape(widthDp: Float, heightDp: Float, rotates: Boolean): Boolean = rotates && widthDp > heightDp && heightDp < COMPACT_HEIGHT_DP

    /** Full screen shows while asked for, or while the phone is turned unless full screen was just left that way. */
    fun fullscreenShown(requested: Boolean, phoneLandscape: Boolean, portraitLock: Boolean): Boolean = requested || (phoneLandscape && !portraitLock)

    /** The window's orientation: landscape for a landscape video in full screen, upright after leaving it turned, else the user's. */
    fun orientation(requested: Boolean, landscapeVideo: Boolean, portraitLock: Boolean): WatchOrientation = when {
        requested && landscapeVideo -> WatchOrientation.LANDSCAPE
        portraitLock -> WatchOrientation.PORTRAIT
        else -> WatchOrientation.FREE
    }

    /** Theatre mode's player height: the content width at the video's shape, at most [THEATRE_MAX_SHARE] of [availableDp]. */
    fun theatreHeightDp(widthDp: Float, availableDp: Float, aspect: Float): Float {
        val a = if (aspect > 0f) aspect else 16f / 9f
        return min(widthDp / a, THEATRE_MAX_SHARE * availableDp).coerceAtLeast(0f)
    }

    /**
     * Whether [details] are the stand-in the controller gives a download (spec 6.1 "Offline"): no stream of any kind, which a video
     * of the source always has (an address, a manifest or a stream list).
     */
    fun isOfflineStandIn(details: VideoDetails): Boolean = with(details.streams) {
        videoOnly.isEmpty() && muxed.isEmpty() && audio.isEmpty() && hlsUrl == null && dashMpdUrl == null
    }

    /** The related videos of [details] (videos only; Shorts left out when [hideShorts]), in their order. */
    fun relatedVideos(details: VideoDetails?, hideShorts: Boolean): List<VideoItem> {
        val videos = details?.related.orEmpty().mapNotNull { (it as? ListItem.Video)?.item }.distinctBy { it.id }
        return ShortsFilter.filter(videos, hideShorts) { it }
    }

    /** Whether [state] is a failure for want of a network while loading [videoId] (a failure before any detail names no item). */
    fun failedForNetwork(state: PlaybackUiState, videoId: String): Boolean =
        state.error == VideoUiError.NETWORK && (state.item?.id ?: videoId) == videoId

    /** The two whole numbers picture-in-picture takes for [aspect] (width / height): a base of [ASPECT_BASE]; 16:9 when unknown. */
    fun aspectWidth(aspect: Float): Int = ((if (aspect > 0f) aspect else 16f / 9f) * ASPECT_BASE).roundToInt().coerceAtLeast(1)

    const val ASPECT_BASE = 1000
}

/**
 * Hands picture-in-picture what it needs, only when something changed (spec 6.2; the player's bounds are reported on every layout
 * pass that moves it): whether leaving the window enters it, the video's shape, and the player's bounds as the source rectangle.
 */
internal class PipUpdater(private val pip: PictureInPictureState) {
    private var enabled = false
    private var aspectWidth = 0
    private var rect: Rect? = null
    private var sent = false

    /** The page's wish: [enabled] while the setting is on, the video plays and the page is on top; [aspect] the video's shape. */
    fun update(enabled: Boolean, aspect: Float) {
        val width = WatchRules.aspectWidth(aspect)
        if (sent && enabled == this.enabled && width == aspectWidth) return
        this.enabled = enabled
        aspectWidth = width
        send()
    }

    /** The player's bounds in the window (the source rectangle the system animates from). */
    fun bounds(rect: Rect) {
        if (rect == this.rect) return
        this.rect = rect
        if (sent) send()
    }

    /** Auto-enter off. */
    fun off() {
        enabled = false
        send()
    }

    /**
     * The page left the route: auto-enter off, unless another watch page has spoken since (one page fading into the next video's
     * must not turn off what the new one turned on).
     */
    fun leave() {
        if (last === this) off()
    }

    private fun send() {
        sent = true
        last = this
        pip.update(enabled, if (aspectWidth > 0) aspectWidth else WatchRules.aspectWidth(0f), WatchRules.ASPECT_BASE, rect)
    }

    private companion object {
        /** The updater that spoke last (main thread, like composition). */
        var last: PipUpdater? = null
    }
}

package com.cosmotune.video.ui.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.sheets.CosmoToast
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_error_network
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.resources.video_short_liked
import com.cosmotune.video.resources.video_shorts_end
import com.cosmotune.video.resources.video_speed_hold
import com.cosmotune.video.shorts.ShortsFeed
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.shorts.ShortsPage
import com.cosmotune.video.shorts.ShortsSeedCache
import com.cosmotune.video.ui.actions.VideoActionHandler
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoAction
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.video.ui.comments.CommentsPanel
import com.cosmotune.video.ui.player.errorMessage
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoBackHandler
import com.music.bitchord.ui.components.FLOATING_BAR_MAX_WIDTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.random.Random

/*
 * The Shorts screen (spec 5.15): the full-screen pager on the phone, the page of the desktop (ShortsDesktopPage.kt). What both share
 * lives here: the queue the pager shows, the controller that turns gestures into playback and library calls, and one page.
 *
 * Black behind the video is the black of a full window (the documented exception); white text over the video is the media one.
 */

/**
 * The Shorts screen for [route] (spec 5.15): its queue comes from `LocalVideoGraph.current.shorts` for the route's origin, seeded with
 * the list the card was opened from (`ShortsSeedCache`), and it opens at the route's Short. When [wide] (the desktop, a window of
 * 840 dp and more) it is the desktop page: a centred 9:16 column with its buttons beside it; else the phone's full-screen pager (the
 * shell draws no bar, tabs or capsule and gives no padding on this route). [contentPadding] is the shell's (none on this route).
 *
 * Only the settled page plays: it is played as a Short (`play(id, short = true)`: loops, no resume, no autoplay) and the next one is
 * prefetched. Leaving the screen stops the Short and lifts the pager's mute; the mute is kept for the rest of the session.
 */
@Composable
fun ShortsScreen(route: VideoRoute.Shorts, contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val actions = rememberVideoActions()
    val currentActions by rememberUpdatedState(actions)
    val key = rememberSaveable(route) { ShortsQueues.newKey() }
    val queue = remember(key, graph.shorts) { ShortsQueues.obtain(key, route.origin) { ShortsQueue(graph.shorts, route.origin, seedOf(route)) } }
    // Keyed on what lives as long as the screen: a new controller would stop and restart the Short.
    val controller = remember(queue, graph.playback, graph.library, navigator) {
        ShortsController(
            playback = graph.playback,
            library = graph.library,
            onShare = { item -> currentActions.handle(item, VideoAction.Share, VideoSheetContext.DEFAULT) },
            onOpenChannel = { item -> item.channelId?.let { navigator.push(VideoRoute.Channel(it)) } },
            onBack = { navigator.pop() },
        )
    }
    LaunchedEffect(queue) { queue.start() }
    DisposableEffect(controller) {
        controller.enter()
        onDispose { controller.release() }
    }
    if (wide) ShortsDesktopPage(route, queue, controller, contentPadding) else ShortsPhonePager(route, queue, controller)
    ShortsSheets(queue, controller, actions, commentsAsSheet = !wide)
}

// ---- the queue ------------------------------------------------------------------------------------------------------------

/**
 * The Shorts a pager shows (spec 5.15): [items] (the seed at once, then the pages of [feed] for [origin], each Short once), the token
 * of the next page, and whether a load is running or the last one failed. A screen calls [start] once and [more] three pages before
 * the end; [pageCount] adds the last page ("No more Shorts here", or the failure with "Try again") once the queue has ended or failed.
 * Loads run on the caller's (main) dispatcher: the source moves its own work off it.
 */
@Stable
internal class ShortsQueue(private val feed: ShortsFeed, val origin: ShortsOrigin, private val seed: List<VideoItem>) {
    var items: List<VideoItem> by mutableStateOf(seed.distinctBy { it.id })
        private set
    var next: PageToken? by mutableStateOf(null)
        private set
    var started: Boolean by mutableStateOf(false)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** Whether the queue has nothing more to give. */
    val ended: Boolean get() = started && next == null && !failed

    /** The pager's pages: the Shorts, and the last page once the queue has ended or failed. */
    val pageCount: Int get() = items.size + if (ended || failed) 1 else 0

    /** The pager key of page [page]: the Short's id, or [END_KEY] for the last page. */
    fun keyAt(page: Int): Any = items.getOrNull(page)?.id ?: END_KEY

    /** The page of the Short [id], or 0 when it is not (yet) in the queue. */
    fun indexOf(id: String?): Int = items.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** Whether the pager on page [current] should ask for more: three pages (or fewer) from the end, a next page, nothing running. */
    fun wantsMore(current: Int): Boolean =
        started && !loading && !failed && next != null && current >= items.size - 1 - VideoMetrics.SHORTS_LOAD_MORE_BEFORE

    /** Loads the first page, once. */
    suspend fun start() {
        if (started || loading) return
        load { feed.start(origin, seed) }
    }

    /** Loads the next page (or the first, when it failed); "Try again" calls it too. */
    suspend fun more() {
        if (loading) return
        if (!started) {
            load { feed.start(origin, seed) }
            return
        }
        val token = next ?: return
        load { feed.more(origin, token) }
    }

    private suspend fun load(call: suspend () -> ShortsPage) {
        loading = true
        failed = false
        try {
            val page = call()
            val known = items.mapTo(HashSet()) { it.id }
            val added = page.items.filter { known.add(it.id) }
            if (added.isNotEmpty()) items = items + added
            next = page.next
            started = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    companion object {
        const val END_KEY = "shorts-end"
    }
}

/**
 * The queues of the Shorts pages in the stacks, by a key each page keeps in its saved state: a page that comes back (Back from a
 * channel opened from the pager, a rotation) finds its queue, its place and its loaded pages again. The [KEPT] most recent are kept.
 */
internal object ShortsQueues {
    private const val KEPT = 4
    private val queues = LinkedHashMap<String, ShortsQueue>()

    /** A key no other page has, even after the process was restarted under a restored stack. */
    fun newKey(): String = "shorts-" + Random.nextLong().toString(36)

    fun obtain(key: String, origin: ShortsOrigin, create: () -> ShortsQueue): ShortsQueue {
        val queue = queues.remove(key)?.takeIf { it.origin == origin } ?: create()
        queues[key] = queue
        while (queues.size > KEPT) queues.remove(queues.keys.first())
        return queue
    }
}

/** The list the card was opened from (spec 5.15), kept in the cache for the page's return; nothing when the route has none. */
internal fun seedOf(route: VideoRoute.Shorts): List<VideoItem> {
    val key = route.seedKey ?: return emptyList()
    val seed = ShortsSeedCache.take(key) ?: return emptyList()
    ShortsSeedCache.put(key, seed)
    return seed
}

/** What the pager keeps for the session (spec 5.15: "the choice is kept for the session"): the mute, and the liked toast's once. */
internal object ShortsSession {
    var muted: Boolean = false
    var likedToastShown: Boolean = false

    /** The controller that last played a Short: only it stops the Short when its screen leaves. */
    var owner: ShortsController? = null

    /** Forgets everything (tests). */
    fun reset() {
        muted = false
        likedToastShown = false
        owner = null
    }
}

// ---- the controller -------------------------------------------------------------------------------------------------------

/**
 * Turns the pager's events into playback and library calls (spec 5.15), and holds what the overlay shows for them: the settled Short,
 * whether the user paused it, the pause glyph's and the heart burst's serials, the liked toast, the open title, the 2x hold, the sheet
 * and the comments. Main thread only.
 */
@Stable
internal class ShortsController(
    val playback: VideoPlayback,
    private val library: VideoLibrary,
    private val onShare: (VideoItem) -> Unit,
    private val onOpenChannel: (VideoItem) -> Unit,
    private val onBack: () -> Unit,
) {
    var settledId: String? by mutableStateOf(null)
        private set
    var userPaused: Boolean by mutableStateOf(false)
        private set
    var glyphSerial: Int by mutableIntStateOf(0)
        private set
    var burst: HeartBurstSpec? by mutableStateOf(null)
        private set
    var toastSerial: Int by mutableIntStateOf(0)
        private set
    var expandedId: String? by mutableStateOf(null)
        private set
    var fast: Boolean by mutableStateOf(false)
        private set

    /** The Short whose video sheet is open, or null. */
    var sheetFor: VideoItem? by mutableStateOf(null)

    /** The Short whose comments are open, or null. */
    var commentsFor: String? by mutableStateOf(null)

    private var burstSerial = 0
    private var speedBefore = 1f
    private var wentAway = false

    /** The screen came: the session's mute applies. */
    fun enter() {
        if (playback.state.value.muted != ShortsSession.muted) playback.setMuted(ShortsSession.muted)
    }

    /**
     * A page settled on the Short [id] (null: the last page, which has none): it plays as a Short and [next] is prefetched (its details
     * and streams). The same Short again (more pages arrived) keeps playing as it was; on the last page the Short pauses.
     */
    fun onSettled(id: String?, next: String?) {
        val changed = id != settledId
        if (changed) {
            userPaused = false
            expandedId = null
            settledId = id
        }
        if (id == null) {
            val now = playback.state.value
            if (now.short && now.playing) playback.togglePlayPause()
            return
        }
        ShortsSession.owner = this
        playback.play(id, short = true)
        // The Short was already loaded and is paused (the last page paused it, or the app left): the controller keeps a loaded
        // video as it is, so the pager plays it again itself. Never after a user's pause: that one is only undone by a tap.
        val now = playback.state.value
        if (changed && now.item?.id == id && now.short && !now.playing && (now.status == PlayerStatus.READY || now.status == PlayerStatus.BUFFERING)) {
            playback.togglePlayPause()
        }
        if (next != null) playback.prefetch(next)
    }

    /** A tap: pauses (with the glyph) or plays the settled Short. */
    fun togglePlay() {
        val playing = playback.state.value.playing
        playback.togglePlayPause()
        userPaused = playing
        if (playing) glyphSerial += 1
    }

    /** The like button: likes [item] locally, or takes the like back. */
    fun toggleLike(item: VideoItem, liked: Boolean) = library.setLiked(item, !liked)

    /** A double tap at ([x], [y]) on [item]'s page: liked locally (never unliked), the heart burst there, the toast once a session. */
    fun doubleTap(item: VideoItem, x: Float, y: Float) {
        library.setLiked(item, true)
        burstSerial += 1
        burst = HeartBurstSpec(x, y, burstSerial)
        if (!ShortsSession.likedToastShown) {
            ShortsSession.likedToastShown = true
            toastSerial += 1
        }
    }

    /** The burst numbered [serial] has played. */
    fun burstDone(serial: Int) {
        if (burst?.serial == serial) burst = null
    }

    /** The liked toast numbered [serial] has had its time. */
    fun toastDone(serial: Int) {
        if (toastSerial == serial) toastSerial = 0
    }

    /** A long press holds 2x ([on]) and its release puts the speed back. */
    fun holdFast(on: Boolean) {
        if (on == fast) return
        if (on) {
            speedBefore = playback.state.value.speed
            playback.setSpeed(FAST_SPEED)
        } else {
            playback.setSpeed(speedBefore)
        }
        fast = on
    }

    /** The mute button and the M key: kept for the session. */
    fun toggleMute() {
        ShortsSession.muted = !playback.state.value.muted
        playback.setMuted(ShortsSession.muted)
    }

    /** The chip: follows [item]'s channel, or stops following it. */
    fun toggleSubscribe(item: VideoItem, subscribed: Boolean) {
        val id = item.channelId ?: return
        if (subscribed) {
            library.unsubscribe(id)
        } else {
            library.subscribe(ChannelItem(id, item.channelName.orEmpty(), item.channelAvatarUrl, subscriberCount = null, videoCount = null, description = null))
        }
    }

    /** A tap on the title shows five lines with the description's start, or two again. */
    fun toggleExpanded(id: String) {
        expandedId = if (expandedId == id) null else id
    }

    fun share(item: VideoItem) = onShare(item)
    fun openChannel(item: VideoItem) = onOpenChannel(item)
    fun back() = onBack()

    /**
     * The app went to the background ([foreground] false) or came back. A Short does not play on out of sight (the controller pauses it
     * when told the UI left); when the pager comes back it plays it again unless the user had paused it.
     */
    fun onForeground(foreground: Boolean) {
        if (!foreground) {
            if (playback.state.value.short) {
                wentAway = true
                playback.onUiVisible(false)
            }
            return
        }
        if (!wentAway) return
        wentAway = false
        playback.onUiVisible(true)
        val now = playback.state.value
        if (now.short && now.item?.id == settledId && !now.playing && !userPaused) playback.togglePlayPause()
    }

    /**
     * The screen left: the Short this screen played stops, and the pager's mute is lifted for the rest of Video. When another Shorts
     * screen has played since (a Short's "Play" in the sheet opens a new pager), that one keeps the playback and the mute.
     */
    fun release() {
        val owner = ShortsSession.owner
        if (owner != null && owner !== this) return
        ShortsSession.owner = null
        if (fast) holdFast(false)
        val now = playback.state.value
        if (owner === this && now.short) playback.stop()
        if (now.muted) playback.setMuted(false)
    }

    companion object {
        /** A long press plays at twice the speed while held (spec 5.15). */
        const val FAST_SPEED = 2f
    }
}

/** The settled page's Short and the one after it, as [DriveShorts] watches them. */
@Immutable
private data class Settled(val id: String?, val next: String?)

/**
 * What every Shorts pager runs: [ShortsController.onSettled] whenever the settled page (or the Short after it) changes, and
 * [ShortsQueue.more] three pages before the end.
 */
@Composable
internal fun DriveShorts(queue: ShortsQueue, pagerState: PagerState, controller: ShortsController) {
    LaunchedEffect(queue, pagerState, controller) {
        snapshotFlow {
            val page = pagerState.settledPage
            Settled(queue.items.getOrNull(page)?.id, queue.items.getOrNull(page + 1)?.id)
        }
            .distinctUntilChanged()
            .collect { controller.onSettled(it.id, it.next) }
    }
    LaunchedEffect(queue, pagerState) {
        snapshotFlow { queue.wantsMore(pagerState.currentPage) }
            .distinctUntilChanged()
            .filter { it }
            .collect { queue.more() }
    }
}

/**
 * How the picture of a video [videoAspect] (width / height) sits in a page [box] big (spec 5.15): it covers the page (FILL) when the
 * two shapes are within 5 % of each other, and is shown whole (FIT, black bars) otherwise.
 */
internal fun shortsScale(videoAspect: Float, box: IntSize): VideoScale {
    if (box.width <= 0 || box.height <= 0 || videoAspect <= 0f) return VideoScale.FIT
    val boxAspect = box.width.toFloat() / box.height.toFloat()
    return if (abs(videoAspect - boxAspect) / boxAspect <= FILL_TOLERANCE) VideoScale.FILL else VideoScale.FIT
}

/** Within this share of the page's shape, a video covers the page. */
internal const val FILL_TOLERANCE = 0.05f

// ---- one page -------------------------------------------------------------------------------------------------------------

/**
 * One Short of the pager (spec 5.15): the picture (the item's thumbnail, and the player's surface over it once the settled Short has
 * loaded: only the settled page draws the surface, so the one engine's picture moves to it), the scrims, the pause glyph, the bottom
 * block, the right rail when [rail] (the phone), and the progress line. [active] is the settled page; [bottomInset] the navigation
 * bar's height (the phone) or nothing. Tap, double tap and long press are the controller's.
 */
@Composable
internal fun ShortsPageContent(
    item: VideoItem,
    active: Boolean,
    state: PlaybackUiState,
    controller: ShortsController,
    marks: ShortsMarks,
    rail: Boolean,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val graph = LocalVideoGraph.current
    val title = rememberVideoTitle(item)
    val loaded = active && state.item?.id == item.id
    val details = if (loaded) state.details?.takeIf { it.item.id == item.id } else null
    val showSurface = loaded && state.status != PlayerStatus.LOADING && state.status != PlayerStatus.IDLE && state.status != PlayerStatus.ERROR
    var box by remember { mutableStateOf(IntSize.Zero) }
    val liked = item.id in marks.liked
    val subscribed = item.channelId != null && item.channelId in marks.subscribed
    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { box = it }
            .pointerInput(item.id, active, controller) {
                detectTapGestures(
                    onTap = { if (active) controller.togglePlay() },
                    onDoubleTap = { at -> controller.doubleTap(item, at.x, at.y) },
                    onLongPress = { if (active) controller.holdFast(true) },
                    onPress = {
                        tryAwaitRelease()
                        controller.holdFast(false)
                    },
                )
            },
    ) {
        AsyncImage(model = item.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (showSurface) graph.surface?.Surface(Modifier.fillMaxSize(), shortsScale(state.aspect, box))
        Box(Modifier.fillMaxSize().shortsScrims())
        if (loaded && state.status == PlayerStatus.ERROR) {
            val type = LocalCosmoType.current
            Text(
                text = stringResource(errorMessage(state.error ?: VideoUiError.STOPPED)),
                style = type.body(TypeScale.BODY),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            )
        }
        if (active) PauseGlyph(controller.glyphSerial, Modifier.align(Alignment.Center))
        ShortsBottomBlock(
            item = item,
            title = title,
            description = details?.description?.text,
            expanded = controller.expandedId == item.id,
            onToggleExpanded = { controller.toggleExpanded(item.id) },
            subscribed = subscribed,
            onSubscribe = { controller.toggleSubscribe(item, subscribed) },
            onOpenChannel = { controller.openChannel(item) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = VideoMetrics.PAGER_BLOCK_START_DP.dp,
                    end = if (rail) VideoMetrics.PAGER_BLOCK_END_DP.dp else VideoMetrics.PAGER_BLOCK_START_DP.dp,
                    bottom = bottomInset + VideoMetrics.PAGER_BLOCK_BOTTOM_DP.dp,
                ),
        )
        if (rail) {
            ShortsRail(
                liked = liked,
                likeCount = details?.likeCount,
                commentCount = null,
                onLike = { controller.toggleLike(item, liked) },
                onComments = { controller.commentsFor = item.id },
                onShare = { controller.share(item) },
                onMore = { controller.sheetFor = item },
                overMedia = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = VideoMetrics.PAGER_RAIL_END_DP.dp, bottom = bottomInset + VideoMetrics.PAGER_RAIL_BOTTOM_DP.dp),
            )
        }
        ShortsProgressLine(
            playback = graph.playback,
            durationMs = if (loaded) state.durationMs else 0L,
            playing = loaded && state.playing,
            active = loaded,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
        )
    }
}

/** What the pages mark on their Shorts: the liked ones and the followed channels (ids), read once for the screen. */
@Immutable
internal class ShortsMarks(val liked: Set<String>, val subscribed: Set<String>)

@Composable
internal fun rememberShortsMarks(): ShortsMarks {
    val library = LocalVideoGraph.current.library
    val liked by library.liked.collectAsState()
    val subscriptions by library.subscriptions.collectAsState()
    return remember(liked, subscriptions) { ShortsMarks(liked.mapTo(HashSet()) { it.id }, subscriptions.mapTo(HashSet()) { it.channelId }) }
}

/**
 * The page after the last Short (spec 5.15): "No more Shorts here", or, when the last load failed, the failure and "Try again" (a C
 * quiet button). Settling here pauses the Short.
 */
@Composable
internal fun ShortsEndPage(queue: ShortsQueue, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val type = LocalCosmoType.current
    Column(modifier.background(Color.Black), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(if (queue.failed) Res.string.video_error_network else Res.string.video_shorts_end),
            style = type.body(TypeScale.BODY),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (queue.failed) {
            Spacer(Modifier.height(16.dp))
            CosmoButton(stringResource(Res.string.video_retry), onClick = { scope.launch { queue.more() } }, kind = CosmoButtonKind.QUIET, floating = true)
        }
    }
}

/**
 * What floats over the pager's pages (spec 5.15), in the box the pages fill: the heart burst where the double tap was, and under the
 * top row ([below] from the top) the "2x speed" pill while a long press holds and the liked toast for 4 s.
 */
@Composable
internal fun BoxScope.ShortsFloating(controller: ShortsController, below: Dp) {
    controller.burst?.let { spec -> key(spec.serial) { HeartBurst(spec, onDone = { controller.burstDone(spec.serial) }) } }
    if (controller.fast) {
        ShortsPill(stringResource(Res.string.video_speed_hold), Modifier.align(Alignment.TopCenter).padding(top = below))
    }
    val toast = controller.toastSerial
    if (toast > 0) {
        LaunchedEffect(toast) {
            delay(TOAST_MS)
            controller.toastDone(toast)
        }
        key(toast) {
            CosmoToast(
                stringResource(Res.string.video_short_liked),
                Modifier.align(Alignment.TopCenter).padding(top = below).widthIn(max = FLOATING_BAR_MAX_WIDTH),
            )
        }
    }
}

/** The liked toast's time (`CosmoToast`: 4 s). */
private const val TOAST_MS = 4_000L

/**
 * The sheets the pager opens: the video sheet of the Short ([ShortsController.sheetFor]; its actions are the shared handler's, with
 * the queue as the list), and on the phone ([commentsAsSheet]) the comments as a sheet; Back closes the comments first.
 */
@Composable
internal fun ShortsSheets(queue: ShortsQueue, controller: ShortsController, actions: VideoActionHandler, commentsAsSheet: Boolean) {
    val downloaded by rememberDownloadedIds()
    val state by controller.playback.state.collectAsState()
    controller.sheetFor?.let { item ->
        VideoActionsSheet(
            item = item,
            context = VideoSheetContext.DEFAULT,
            onAction = { action ->
                val details = state.details?.takeIf { it.item.id == item.id }
                actions.handle(item, action, VideoSheetContext.DEFAULT, details = details, list = queue.items)
            },
            onDismiss = { controller.sheetFor = null },
            downloaded = item.id in downloaded,
        )
    }
    val comments = controller.commentsFor
    if (commentsAsSheet && comments != null) {
        VideoBackHandler(enabled = true) { controller.commentsFor = null }
        CommentsPanel(videoId = comments, sheet = true, onDismiss = { controller.commentsFor = null })
    }
}

// ---- the phone ------------------------------------------------------------------------------------------------------------

/**
 * The phone's pager (spec 5.15): full screen under the status and navigation bars, on black; a [VerticalPager] keyed by video id with
 * the next page composed ahead (its thumbnail loading), each page a [ShortsPageContent] with the rail; over them the top row and the
 * floating pieces. While the app is in the background the Short pauses, and plays again when it comes back.
 */
@Composable
private fun ShortsPhonePager(route: VideoRoute.Shorts, queue: ShortsQueue, controller: ShortsController) {
    val state by controller.playback.state.collectAsState()
    val marks = rememberShortsMarks()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val startPage = remember(queue) { queue.indexOf(route.startVideoId) }
    val pagerState = rememberPagerState(initialPage = startPage) { queue.pageCount }
    DriveShorts(queue, pagerState, controller)
    // Out of sight (stopped, as the watch page counts it) the Short pauses; started again, it plays on.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onForeground(false)
                Lifecycle.Event.ON_START -> controller.onForeground(true)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { page -> queue.keyAt(page) },
        ) { page ->
            val item = queue.items.getOrNull(page)
            if (item == null) {
                ShortsEndPage(queue, Modifier.fillMaxSize())
            } else {
                ShortsPageContent(
                    item = item,
                    active = page == pagerState.settledPage,
                    state = state,
                    controller = controller,
                    marks = marks,
                    rail = true,
                    bottomInset = navigationBottom,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (queue.pageCount == 0) CosmoLoadingBlob(Modifier.align(Alignment.Center))
        ShortsTopRow(
            muted = state.muted,
            onBack = controller::back,
            onToggleMute = controller::toggleMute,
            topInset = statusTop,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
        )
        ShortsFloating(controller, below = statusTop + TOP_ROW_TOP * 2 + VideoMetrics.PAGER_TOP_BUTTON_DP.dp)
    }
}

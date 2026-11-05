package com.cosmotune.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.chrome.CosmoProfileButton
import com.cosmotune.ui.chrome.CosmoTopBar
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitRootActions
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.graph.rememberVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_local_open
import com.cosmotune.video.resources.video_profile_name
import com.cosmotune.video.resources.video_profile_subtitle
import com.cosmotune.video.resources.video_search
import com.cosmotune.video.ui.account.VideoAccountSheet
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.TopBarScroll
import com.cosmotune.video.ui.shell.VideoBackHandler
import com.cosmotune.video.ui.shell.VideoBottomChrome
import com.cosmotune.video.ui.shell.VideoNavHost
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.ui.shell.rememberVideoCapsule
import com.cosmotune.video.ui.shell.rememberVideoNavigator
import com.cosmotune.video.ui.shell.routeTitle
import com.cosmotune.video.ui.shell.videoDrawerEntries
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Video mode's whole screen on the phone (spec section 5.1), drawn by the app's `PhoneModeHost` in place of Music's root while
 * the mode is Video. [windowWidth] is the window's, so the shell can pick its layout without measuring (a window of 840 dp or
 * more gives the pages `wide`); [windowHeight] is part of the contract and the shell has no use for it yet. [host] is the way back to
 * Music and the links with it.
 *
 * It provides [LocalVideoGraph] and [LocalVideoNavigator] and draws, on the flat page ground: the nav host with the content padding
 * of the top bar and the dock; the shared top bar ([CosmoTopBar]); and the shared dock ([VideoBottomChrome], with the mini player in it
 * while a video is loaded). The watch page has neither bar nor dock, as a full-screen surface (DESIGN_SYSTEM.md 4.16, 5.1; its player
 * stands under the status bar); the Shorts pager has no top bar, no dock and no padding (spec 5.15). The system Back key pops a page; a
 * video waiting in [VideoHostLinks.pendingVideo] opens its watch page. A playing video changes no colour here.
 *
 * The only blur this shell adds is the dock's.
 */
@Composable
fun CosmoVideoApp(windowWidth: Dp, windowHeight: Dp, host: VideoHostLinks, modifier: Modifier = Modifier) {
    val graph = rememberVideoGraph(host)
    val navigator = rememberVideoNavigator()
    CompositionLocalProvider(LocalVideoGraph provides graph, LocalVideoNavigator provides navigator) {
        VideoPhoneShell(windowWidth, host, modifier)
    }
}

@Composable
private fun VideoPhoneShell(windowWidth: Dp, host: VideoHostLinks, modifier: Modifier) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val route = navigator.top

    VideoBackHandler(enabled = navigator.canPop) { navigator.pop() }
    OpenPendingVideo(host, navigator)

    val pip = com.cosmotune.video.player.rememberPictureInPicture().active
    if (pip) {
        VideoPipLayer(graph)
        return
    }
    com.cosmotune.video.ui.shell.VideoBackgroundLifecycle(graph.playback, onWatch = route is VideoRoute.Watch)   // Y-34: leaving from any page

    val fullScreen = route is VideoRoute.Shorts
    // The local player stands like the watch page: the picture under the status bar, no top bar, no dock.
    val onWatch = route is VideoRoute.Watch || route is VideoRoute.LocalPlayer
    val density = LocalDensity.current
    val navigationInsetDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value }
    val topBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_CONTENT
    val capsule = rememberVideoCapsule(graph.playback)
    val videoLoaded by remember(capsule) { derivedStateOf { capsule.value.song != null } }
    val capsuleShown = videoLoaded && !onWatch && !fullScreen
    var chromePixels by remember { mutableIntStateOf(0) }
    val chromeHeight = maxOf(ShellMetrics.phoneChromeHeightDp(navigationInsetDp, withMiniPlayer = capsuleShown).dp, with(density) { chromePixels.toDp() })
    val contentPadding = when {
        fullScreen -> PaddingValues()
        onWatch -> PaddingValues(bottom = PAGE_GAP + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        else -> PaddingValues(top = topBarHeight, bottom = chromeHeight + PAGE_GAP)
    }

    val opener = com.cosmotune.video.ui.local.rememberLocalVideoOpener()
    val picker = com.cosmotune.video.ui.local.rememberVideoFilePicker(onVideos = { opener.open(it) }, onSubtitle = {})
    val hazeState = remember { HazeState() }
    val tracker = remember { TopBarScroll() }
    val scrolledAt = with(density) { SCROLLED_AT.toPx() }
    val scrolled = remember(tracker, scrolledAt) { derivedStateOf { tracker.offset > scrolledAt } }
    SideEffect { tracker.show(navigator.topPageKey()) }
    val palette = LocalCosmoColors.current.palette

    Box(modifier.fillMaxSize().background(Color(palette.backgroundBottom))) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState).nestedScroll(tracker.connection)) {
            VideoNavHost(navigator, contentPadding, wide = windowWidth.value >= VideoMetrics.WIDE_MIN_DP)
        }
        if (!fullScreen && !onWatch) {
            VideoBar(
                route = route,
                tracker = tracker,
                scrolled = { scrolled.value },
                height = topBarHeight,
                onBack = if (navigator.canPop) ({ navigator.pop() }) else null,
                onOpenFile = { picker.openVideos() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (!fullScreen && !onWatch) {
            VideoBottomChrome(navigator, graph.playback, hazeState, capsuleShown, Modifier.align(Alignment.BottomCenter), onHeightChanged = { chromePixels = it })
        }
        com.cosmotune.video.ui.notice.FirstRunNoticeLayer()

    }
}

/**
 * The top bar of Video's pages (DESIGN_SYSTEM.md 4.1): the app's one [CosmoTopBar], with the mode pill on a root page and back on a
 * pushed one, the page's name once its large title has gone, then a root page's one action (a video file on the device), Search (not
 * on the Search page itself) and the account's avatar, in the same place as in every other mode. The bar is clear at the top of a page
 * and takes the page's ground, with a hairline under it, as the page scrolls under it: read while drawing, so a scroll recomposes nothing.
 */
@Composable
internal fun VideoBar(
    route: VideoRoute,
    tracker: TopBarScroll,
    scrolled: () -> Boolean,
    height: Dp,
    onBack: (() -> Unit)?,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val account = graph.account
    val state by account.state.collectAsState()
    val signedIn = state as? AccountState.SignedIn
    val scope = rememberCoroutineScope()
    var accountSheet by remember { mutableStateOf(false) }
    val colors = LocalCosmoColors.current
    val profileName = signedIn?.displayName ?: stringResource(Res.string.video_profile_name)
    val profileSubtitle = signedIn?.handle ?: stringResource(Res.string.video_profile_subtitle)
    val density = LocalDensity.current
    val fadeRun = with(density) { BAR_FADE_RUN.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val shown = (tracker.offset / fadeRun).coerceIn(0f, 1f)
                if (shown > 0f) {
                    drawRect(colors.live(CoverField.BACKGROUND_BOTTOM).copy(alpha = shown))
                    drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = HAIRLINE * shown), Offset(0f, size.height - 1f), Size(size.width, 1f))
                }
            },
    ) {
        CosmoTopBar(
            title = routeTitle(route),
            scrolled = scrolled(),
            height = height,
            onBack = onBack,
            actions = {
                val search = stringResource(Res.string.video_search)
                val openFile = stringResource(Res.string.video_local_open)
                KitRootActions(
                    onSearch = { navigator.push(VideoRoute.Search()) },
                    searchLabel = search,
                    showSearch = route !is VideoRoute.Search,
                    pageAction = { if (onBack == null) KitIconButton(CosmoIcons.Video, openFile, onOpenFile) },
                    account = {
                        CosmoProfileButton(
                            avatarUrl = signedIn?.avatarUrl,
                            name = profileName,
                            subtitle = profileSubtitle,
                            entries = videoDrawerEntries(navigator),
                            onAccount = { accountSheet = true },
                        )
                    },
                )
            },
        )
    }
    if (accountSheet) {
        VideoAccountSheet(
            state = account.state.value,
            onSignIn = { scope.launch { account.signInRequest() } },
            onSwitch = { id -> scope.launch { account.switchAccount(id) } },
            onSignOut = { scope.launch { account.signOut() } },
            onDismiss = { accountSheet = false },
        )
    }
}

/**
 * While the phone is in picture-in-picture the window is the picture and nothing else: `VideoPlayerBox(layout = FULLSCREEN, chrome =
 * false)`, fitted, with its subtitles, on black (the black of a full window is the documented exception to "colours come from the
 * palette"), and the window's life while the watch page is away (Y-19's `WatchPictureInPicture`).
 */
@Composable
private fun VideoPipLayer(graph: VideoGraph) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        com.cosmotune.video.ui.watch.WatchPictureInPicture(graph.playback, graph.surface, Modifier.fillMaxSize())
    }
}

/**
 * Opens the watch page of the video the host has waiting (a shared link, or Music's "Watch the music video"): [VideoHostLinks.pendingVideo]
 * is read whenever the state it reads changes, and the host is told with [VideoHostLinks.onPendingHandled] once the page is pushed.
 */
@Composable
internal fun OpenPendingVideo(host: VideoHostLinks, navigator: VideoNavigator) {
    val opener = com.cosmotune.video.ui.local.rememberLocalVideoOpener()
    LaunchedEffect(host, navigator, opener) {
        snapshotFlow { host.pendingVideo() }.filterNotNull().collect { pending ->
            val local = pending.localSource
            if (local != null) {
                // A file another app handed over: the local player plays it.
                opener.open(listOf(com.cosmotune.video.ui.local.PickedVideo(local, pending.localName ?: com.cosmotune.video.local.LocalVideo.fileName(local))))
            } else {
                navigator.push(VideoRoute.Watch(pending.videoId, pending.startMs))
            }
            host.onPendingHandled()
        }
    }
}

/** The top bar's own height under the status bar (the number of Music's top bar, `TopBarContentHeight`). */
private val TOP_BAR_CONTENT = 52.dp

/** The gap between a page's last row and the dock. */
private val PAGE_GAP = 12.dp

/** How far a page has to scroll before its name fades into the top bar: its large title has gone by then. */
private val SCROLLED_AT = 48.dp

/** How far a page scrolls while the bar takes the page's ground, and the bar's hairline then (the theme's 8 %). */
private val BAR_FADE_RUN = 24.dp
private const val HAIRLINE = 0.08f

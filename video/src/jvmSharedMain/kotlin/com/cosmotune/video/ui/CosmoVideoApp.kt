package com.cosmotune.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.theme.cosmoAmbientGround
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.graph.rememberVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.TopBarScroll
import com.cosmotune.video.ui.shell.VideoAccent
import com.cosmotune.video.ui.shell.VideoBackHandler
import com.cosmotune.video.ui.shell.VideoBottomChrome
import com.cosmotune.video.ui.shell.VideoNavHost
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.ui.shell.VideoTopBar
import com.cosmotune.video.ui.shell.rememberVideoCapsule
import com.cosmotune.video.ui.shell.rememberVideoNavigator
import com.cosmotune.video.ui.shell.videoDrawerEntries
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.filterNotNull

/**
 * Video mode's whole screen on the phone (spec section 5.1), drawn by the app's `PhoneModeHost` in place of Music's root while
 * the mode is Video. [windowWidth] is the window's, so the shell can pick its layout without measuring (a window of 840 dp or
 * more gives the pages `wide`); [windowHeight] is part of the contract and the shell has no use for it yet. [host] is the way back to
 * Music and the links with it.
 *
 * It provides [LocalVideoGraph] and [LocalVideoNavigator] and draws, over the app's ambient ground: the nav host with the content
 * padding of the top bar and the floating chrome; the top bar; and the chrome (tab capsule, and the mini capsule while a video is
 * loaded and the watch page is not on show). The watch page has no top bar (its player stands under the status bar); the Shorts
 * pager has no top bar, no chrome and no padding (it is full screen, spec 5.15). The system Back key pops a page; a video
 * waiting in [VideoHostLinks.pendingVideo] opens its watch page; the accent follows the video that is loaded.
 *
 * The only blur this shell adds is the one `CosmoBottomChrome` draws (the tab capsule and the mini capsule sample the page, which
 * is the haze source); the top bar's buttons are glass circles with none of their own.
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

    VideoAccent(graph.playback)
    VideoBackHandler(enabled = navigator.canPop) { navigator.pop() }
    OpenPendingVideo(host, navigator)

    val pip = false   // Y-19 replaces this line
    if (pip) {
        VideoPipLayer(graph)
        return
    }

    val fullScreen = route is VideoRoute.Shorts
    val onWatch = route is VideoRoute.Watch
    val density = LocalDensity.current
    val navigationInsetDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value }
    val topBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_CONTENT
    val capsule = rememberVideoCapsule(graph.playback)
    val videoLoaded by remember(capsule) { derivedStateOf { capsule.value.song != null } }
    val capsuleShown = videoLoaded && !onWatch && !fullScreen
    val chromeHeight = ShellMetrics.phoneChromeHeightDp(navigationInsetDp, withMiniPlayer = capsuleShown).dp
    val contentPadding = when {
        fullScreen -> PaddingValues()
        onWatch -> PaddingValues(bottom = chromeHeight + PAGE_GAP)
        else -> PaddingValues(top = topBarHeight + PAGE_GAP, bottom = chromeHeight + PAGE_GAP)
    }

    val hazeState = remember { HazeState() }
    val tracker = remember { TopBarScroll() }
    val scrolledAt = with(density) { SCROLLED_AT.toPx() }
    val scrolled = remember(tracker, scrolledAt) { derivedStateOf { tracker.offset > scrolledAt } }
    SideEffect { tracker.show(navigator.topPageKey()) }

    Box(modifier.fillMaxSize().cosmoAmbientGround()) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState).nestedScroll(tracker.connection)) {
            VideoNavHost(navigator, contentPadding, wide = windowWidth.value >= VideoMetrics.WIDE_MIN_DP)
        }
        if (!fullScreen && !onWatch) {
            VideoTopBar(
                route = route,
                scrolled = { scrolled.value },
                height = topBarHeight,
                onBack = if (navigator.canPop) ({ navigator.pop() }) else null,
                onSearch = { navigator.push(VideoRoute.Search()) },
                drawerEntries = videoDrawerEntries(navigator),
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (!fullScreen) {
            VideoBottomChrome(navigator, graph.playback, hazeState, capsuleShown, Modifier.align(Alignment.BottomCenter))
        }
        com.cosmotune.video.ui.notice.FirstRunNoticeLayer()

    }
}

/**
 * While the phone is in picture-in-picture the window is the picture and nothing else: the player's surface, fitted, on black (the
 * black of a full window is the documented exception to "colours come from the palette").
 */
@Composable
private fun VideoPipLayer(graph: VideoGraph) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        graph.surface?.Surface(Modifier.fillMaxSize(), VideoScale.FIT)
    }
}

/**
 * Opens the watch page of the video the host has waiting (a shared link, or Music's "Watch the music video"): [VideoHostLinks.pendingVideo]
 * is read whenever the state it reads changes, and the host is told with [VideoHostLinks.onPendingHandled] once the page is pushed.
 */
@Composable
internal fun OpenPendingVideo(host: VideoHostLinks, navigator: VideoNavigator) {
    LaunchedEffect(host, navigator) {
        snapshotFlow { host.pendingVideo() }.filterNotNull().collect { pending ->
            navigator.push(VideoRoute.Watch(pending.videoId, pending.startMs))
            host.onPendingHandled()
        }
    }
}

/** The top bar's own height under the status bar (the number of Music's top bar, `TopBarContentHeight`). */
private val TOP_BAR_CONTENT = 52.dp

/** The gap between a page's first row and the top bar, and between its last row and the floating chrome. */
private val PAGE_GAP = 12.dp

/** How far a page has to scroll before its name fades into the top bar. */
private val SCROLLED_AT = 72.dp

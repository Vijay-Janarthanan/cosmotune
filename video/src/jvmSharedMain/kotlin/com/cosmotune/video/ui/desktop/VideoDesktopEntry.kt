package com.cosmotune.video.ui.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.chrome.CosmoDrawerEntry
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.graph.rememberVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.ui.OpenPendingVideo
import com.cosmotune.video.ui.VideoHostLinks
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoAccent
import com.cosmotune.video.ui.shell.VideoBottomChrome
import com.cosmotune.video.ui.shell.VideoNavHost
import com.cosmotune.video.ui.shell.rememberVideoCapsule
import com.cosmotune.video.ui.shell.videoDrawerEntries
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/*
 * The desktop's entry points into Video mode (spec section 5.1): the page that stands in the window's content area, the
 * sidebar's Video part, the profile drawer's list and the Back key. The desktop host (`com.cosmotune.video.host`, in
 * `desktopApp`) calls these four and nothing else of `:video`.
 */

/**
 * Video's page in the content area: [contentPadding] is what the desktop frame leaves clear for its bars. It provides the graph and
 * the process-wide navigator, draws [VideoNavHost] (always `wide`) under a top margin of 16 dp, and handles the search shortcut:
 * Ctrl K (Cmd K on macOS) takes the focus to the sidebar's field. The bottom is left to the pages (16 dp): Music's player bar, which
 * the frame reserves room for, stands aside in Video. A window narrower than the desktop's compact width (980 dp) has no sidebar
 * and the frame's bottom bar is Music's, so the page draws the tab capsule itself, and the shortcut opens the search page.
 * The Shorts pager gets the whole page.
 */
@Composable
fun VideoDesktopPage(contentPadding: PaddingValues, host: VideoHostLinks) {
    val graph = rememberVideoGraph(host)
    val navigator = DesktopVideoState.navigator
    // The sidebar is composed apart from this page: it learns the graph (for its channels) from here.
    SideEffect { DesktopVideoState.graph = graph }
    CompositionLocalProvider(LocalVideoGraph provides graph, LocalVideoNavigator provides navigator) {
        VideoDesktopPageBody(contentPadding, host)
    }
}

@Composable
private fun VideoDesktopPageBody(contentPadding: PaddingValues, host: VideoHostLinks) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val density = LocalDensity.current
    val windowWidth: Dp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val compact = windowWidth.value < ShellMetrics.DESKTOP_COMPACT_DP
    val route = navigator.top
    val fullPage = route is VideoRoute.Shorts

    VideoAccent(graph.playback)
    OpenPendingVideo(host, navigator)

    val capsule = rememberVideoCapsule(graph.playback)
    val videoLoaded by remember(capsule) { derivedStateOf { capsule.value.song != null } }
    val chromeHeight = ShellMetrics.phoneChromeHeightDp(0f, withMiniPlayer = videoLoaded).dp
    val frameBottom = contentPadding.calculateBottomPadding()
    val padding = when {
        fullPage -> PaddingValues()
        compact -> PaddingValues(top = PAGE_TOP, bottom = maxOf(frameBottom, chromeHeight + PAGE_BOTTOM))
        else -> PaddingValues(top = PAGE_TOP, bottom = PAGE_BOTTOM)
    }

    val hazeState = remember { HazeState() }
    val focus = remember { FocusRequester() }
    // The page keeps the keyboard focus until a control takes it, so the search shortcut is heard with nothing else focused.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val shortcut = if (IS_MAC) event.isMetaPressed else event.isCtrlPressed
                if (event.type == KeyEventType.KeyDown && shortcut && event.key == Key.K) {
                    if (compact) navigator.push(VideoRoute.Search()) else DesktopVideoState.requestSearchFocus()
                    true
                } else {
                    false
                }
            },
    ) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            VideoNavHost(navigator, padding, wide = true)
        }
        if (compact && !fullPage) {
            VideoBottomChrome(
                navigator = navigator,
                playback = graph.playback,
                hazeState = hazeState,
                capsuleShown = videoLoaded && route !is VideoRoute.Watch,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        com.cosmotune.video.ui.notice.FirstRunNoticeLayer()

    }
}

/**
 * The sidebar's Video section: 220 dp, the search field, the navigation rows, the channels and Settings (see
 * [VideoDesktopSidebarContent]). [focusSearch] is true when the host's search shortcut asked for the field; [onSearchFocused] tells
 * the host the request was served. [caption] is what the host draws at the head of the sidebar where the application draws its own
 * window frame (the window buttons and the drag area; the sidebar then keeps 4 dp above the field instead of 16): null where
 * there is none. A host that replaces its whole sidebar with this one passes the caption it would have drawn.
 */
@Composable
fun VideoDesktopSidebar(focusSearch: Boolean, onSearchFocused: () -> Unit, caption: (@Composable () -> Unit)? = null) {
    VideoDesktopSidebarContent(focusSearch, onSearchFocused, caption)
}

/** The rows the profile drawer lists below the modes while the mode is Video: History, Downloads, Video settings and Import. */
@Composable
fun videoDesktopDrawerEntries(): List<CosmoDrawerEntry> = videoDrawerEntries(DesktopVideoState.navigator)

/**
 * The desktop's Back (mouse button 4, Alt and Left) inside Video: [canGoBack] is whether there is a page to go back to (it is read
 * in composition and follows the navigator), [goBack] pops it.
 */
object VideoDesktopBack {
    val canGoBack: Boolean get() = DesktopVideoState.navigator.canPop

    fun goBack() {
        DesktopVideoState.navigator.pop()
    }
}

/** Where the page begins under the frame's top bar (`.d2-main`: 16 dp), and how far its last row stands from the window's foot. */
private val PAGE_TOP = 16.dp
private val PAGE_BOTTOM = 16.dp

private val IS_MAC = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

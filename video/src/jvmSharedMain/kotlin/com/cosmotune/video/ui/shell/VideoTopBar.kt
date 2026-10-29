package com.cosmotune.video.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.chrome.CosmoDrawerEntry
import com.cosmotune.ui.chrome.CosmoProfileButton
import com.cosmotune.ui.chrome.CosmoTopBar
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channels
import com.cosmotune.video.resources.video_downloads
import com.cosmotune.video.resources.video_drawer_downloads
import com.cosmotune.video.resources.video_drawer_history
import com.cosmotune.video.resources.video_drawer_import
import com.cosmotune.video.resources.video_drawer_settings
import com.cosmotune.video.resources.video_history
import com.cosmotune.video.resources.video_import
import com.cosmotune.video.resources.video_liked
import com.cosmotune.video.resources.video_local_open
import com.cosmotune.video.resources.video_local_title
import com.cosmotune.video.resources.video_profile_name
import com.cosmotune.video.resources.video_profile_subtitle
import com.cosmotune.video.resources.video_saved_playlists
import com.cosmotune.video.resources.video_search
import com.cosmotune.video.resources.video_tab_home
import com.cosmotune.video.resources.video_tab_library
import com.cosmotune.video.resources.video_tab_subscriptions
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.VideoIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The phone's top bar (spec 5.1), [CosmoTopBar] with Video's parts: the logo mark at rest on a tab root, a glass back button on a
 * pushed page ([onBack]); the page's name fading in once the page has [scrolled]; at the end Search (a 40 dp glass button, not
 * on the Search page itself) and the profile button, whose drawer lists [drawerEntries]. A top fade under it ([VideoTopFade]) is
 * the backdrop the glass buttons float on. [height] is the bar with the status bar in it, as [CosmoTopBar] takes it.
 *
 * [onOpenFile], when given, adds a glass button before Search that opens the picker for a video file on the device (the local player).
 *
 * [scrolled] is a lambda read here, so a page's scroll recomposes this bar when the answer changes and nothing above it.
 */
@Composable
internal fun VideoTopBar(
    route: VideoRoute,
    scrolled: () -> Boolean,
    height: Dp,
    onBack: (() -> Unit)?,
    onSearch: () -> Unit,
    drawerEntries: List<CosmoDrawerEntry>,
    modifier: Modifier = Modifier,
    onOpenFile: (() -> Unit)? = null,
) {
    val title = routeTitle(route)
    val search = stringResource(Res.string.video_search)
    val openFile = stringResource(Res.string.video_local_open)
    val account = LocalVideoGraph.current.account
    val signedIn = account.state.collectAsState().value as? AccountState.SignedIn
    val profileName = signedIn?.displayName ?: stringResource(Res.string.video_profile_name)
    val profileSubtitle = signedIn?.handle ?: stringResource(Res.string.video_profile_subtitle)
    val avatarUrl = signedIn?.avatarUrl
    val scope = rememberCoroutineScope()
    var accountSheet by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        VideoTopFade(height)
        CosmoTopBar(
            title = title,
            scrolled = scrolled(),
            height = height,
            onBack = onBack,
            actions = {
                if (onOpenFile != null && route !is VideoRoute.Search) {
                    CosmoIconButton(
                        icon = CosmoIcons.Video,
                        contentDescription = openFile,
                        onClick = onOpenFile,
                        style = CosmoIconButtonStyle.GLASS,
                        size = 40.dp,
                    )
                }
                if (route !is VideoRoute.Search) {
                    CosmoIconButton(
                        icon = CosmoIcons.Search,
                        contentDescription = search,
                        onClick = onSearch,
                        style = CosmoIconButtonStyle.GLASS,
                        size = 40.dp,
                    )
                }
                CosmoProfileButton(
                    avatarUrl = avatarUrl,
                    name = profileName,
                    subtitle = profileSubtitle,
                    entries = drawerEntries,
                    onAccount = { accountSheet = true },
                )
            },
        )
    }
    if (accountSheet) {
        AccountSheet(
            state = account.state.value,
            onSignIn = { scope.launch { account.signInRequest() } },
            onSwitch = { id -> scope.launch { account.switchAccount(id) } },
            onSignOut = { scope.launch { account.signOut() } },
            onDismiss = { accountSheet = false },
        )
    }
}

/**
 * The page's colour fading to nothing under the top bar, so the glass buttons and the logo stay readable over a page that scrolls
 * beneath them (the shell has no bar of its own to draw: Music's root does the same with its top fade). It reaches [FADE_RUN] below
 * the bar. The colour is the live `backgroundTop`, read while drawing, so a change of cover recomposes nothing.
 */
@Composable
private fun VideoTopFade(barHeight: Dp) {
    val colors = LocalCosmoColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(barHeight + FADE_RUN)
            .drawWithCache {
                // Read here, so the brush is rebuilt only while a fade of the palette runs.
                val page = colors.live(CoverField.BACKGROUND_TOP)
                val brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to page.copy(alpha = FADE_TOP_ALPHA),
                        FADE_HOLD to page.copy(alpha = FADE_TOP_ALPHA * FADE_HOLD_ALPHA),
                        1f to Color.Transparent,
                    ),
                    startY = 0f,
                    endY = size.height,
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** The name of a page for the top bar; empty where the page knows it better (a channel's or a playlist's own name). */
@Composable
internal fun routeTitle(route: VideoRoute): String = when (route) {
    VideoRoute.Home -> stringResource(Res.string.video_tab_home)
    VideoRoute.Subscriptions -> stringResource(Res.string.video_tab_subscriptions)
    VideoRoute.Library -> stringResource(Res.string.video_tab_library)
    is VideoRoute.Search -> stringResource(Res.string.video_search)
    VideoRoute.History -> stringResource(Res.string.video_history)
    VideoRoute.WatchLater -> stringResource(Res.string.video_watch_later)
    VideoRoute.Liked -> stringResource(Res.string.video_liked)
    VideoRoute.SavedPlaylists -> stringResource(Res.string.video_saved_playlists)
    VideoRoute.Channels -> stringResource(Res.string.video_channels)
    VideoRoute.Downloads -> stringResource(Res.string.video_downloads)
    VideoRoute.Settings -> stringResource(Res.string.video_drawer_settings)
    VideoRoute.Import -> stringResource(Res.string.video_import)
    VideoRoute.LocalFiles -> stringResource(Res.string.video_local_title)
    is VideoRoute.LocalPlayer, is VideoRoute.Watch, is VideoRoute.Shorts, is VideoRoute.Channel, is VideoRoute.Playlist, is VideoRoute.LocalPlaylist -> ""
}

/**
 * The rows of the profile drawer below the modes (spec 5.1): History, Downloads, Video settings and Import, each a page pushed on
 * [navigator]. The phone's top bar and the desktop's top bar both list them.
 */
@Composable
internal fun videoDrawerEntries(navigator: VideoNavigator): List<CosmoDrawerEntry> {
    val history = stringResource(Res.string.video_drawer_history)
    val downloads = stringResource(Res.string.video_drawer_downloads)
    val settings = stringResource(Res.string.video_drawer_settings)
    val importLabel = stringResource(Res.string.video_drawer_import)
    return remember(navigator, history, downloads, settings, importLabel) {
        listOf(
            CosmoDrawerEntry(VideoIcons.History, history, onClick = { navigator.push(VideoRoute.History) }),
            CosmoDrawerEntry(CosmoIcons.Download, downloads, onClick = { navigator.push(VideoRoute.Downloads) }),
            CosmoDrawerEntry(CosmoIcons.Settings, settings, onClick = { navigator.push(VideoRoute.Settings) }),
            CosmoDrawerEntry(VideoIcons.Upload, importLabel, onClick = { navigator.push(VideoRoute.Import) }),
        )
    }
}

/**
 * How far the page on show has been scrolled, for the top bar's title: whatever scrolls under [connection] reports what it
 * consumed, so no page has to know about the bar. The distance is kept per page ([show]), so a page that is returned to is as
 * scrolled as it was left (its own list state is restored by the nav host). [offset] is snapshot state: read it only inside a
 * `derivedStateOf`, which answers once when the title should appear, never per scroll step.
 */
@Stable
internal class TopBarScroll {
    private val left = HashMap<String, Float>()
    private var key = ""

    /** The scrolled distance of the page on show, in pixels, never negative. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** Hand it to `Modifier.nestedScroll` above the pages. */
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y != 0f) offset = (offset - consumed.y).coerceAtLeast(0f)
            return Offset.Zero
        }
    }

    /** The page with this key is on show now: the one before keeps its distance, this one gets back its own. */
    fun show(pageKey: String) {
        if (pageKey == key) return
        if (left.size >= KEPT) left.clear()
        left[key] = offset
        key = pageKey
        offset = left[pageKey] ?: 0f
    }

    private companion object {
        const val KEPT = 64
    }
}

/** How far the fade runs below the bar, and how opaque it is at the top and where its middle stop sits. */
private val FADE_RUN = 28.dp
private const val FADE_TOP_ALPHA = 0.96f
private const val FADE_HOLD = 0.62f
private const val FADE_HOLD_ALPHA = 0.55f

package com.cosmotune.music.ui.tv.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.tv.components.LocalTvChrome
import com.cosmotune.music.ui.tv.components.TvChromePinned
import com.cosmotune.music.ui.tv.components.TvFocusIndication
import com.cosmotune.music.ui.tv.components.TvTabPill
import com.cosmotune.music.ui.tv.components.tvRingHost
import com.cosmotune.music.ui.tv.player.TvWatchPlayer
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.music.watch.AndroidWatch
import com.cosmotune.ui.components.FocusModality
import com.cosmotune.ui.components.LocalFocusModality
import com.cosmotune.ui.theme.CosmoLookTheme
import com.cosmotune.ui.watch.ProvideWatch
import com.cosmotune.ui.watch.WatchApp
import com.cosmotune.ui.watch.WatchBackend
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchPlayback
import com.cosmotune.ui.watch.WatchRoute
import com.cosmotune.ui.watch.WatchSurfaceScale
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.video.graph.androidVideoGraph
import com.cosmotune.video.player.VideoScale
import kotlinx.coroutines.delay

/**
 * How much larger Watch's pages are drawn on the TV than on a desktop window: type, cards and spacing all at once, read from across
 * the room. The pages keep their side-by-side layout ([WatchApp]'s `wideLayout`) though the window is narrower in their units.
 */
internal const val TV_WATCH_SCALE = 1.2f

/**
 * Watch mode on the TV: the shared Watch screens ([WatchApp], films, series and live TV from the plugins that are on), hosted the
 * way the phone hosts them — the real repository ([AndroidWatch.repository], installed as [WatchBackend]'s), the downloads and the
 * player on Video mode's Media3 engine ([AndroidWatch.player]) — with the QR drawer the phone's. Watch's pages read the Orbit look
 * from the Cosmo theme, so it is installed (dark) around them.
 *
 * Sized for the sofa: the pages are drawn [TV_WATCH_SCALE] larger, and focus is the TV's thick peach ring a little outside the
 * piece ([tvRingHost]) rather than the pages' own thin one. Watch's tabs are TV chips over the page. D-pad: down from the chips
 * enters the page (the featured card, then the rows), left and right walk a row, up comes back; Back from the page returns to the
 * chips, Back from the chips is the TV's own (up to the chips at the top, then Home). A film or series page shows its own back
 * header and Back closes it; Play opens the TV's own player ([TvWatchPlayer]) over the whole screen, the chips at the top stepping
 * aside.
 */
@Composable
fun TvWatchScreen(modifier: Modifier = Modifier) {
    TvChromePinned()
    val tab by WatchNav.tab.collectAsState()
    val stack by WatchNav.stack.collectAsState()
    val top = stack.lastOrNull()
    val context = LocalContext.current
    val repository = remember(context) { AndroidWatch.repository(context).also(WatchBackend::install) }
    val downloads = remember(context) { AndroidWatch.downloads(context) }
    val playback = rememberTvWatchPlayback(context, wanted = stack.any { it is WatchRoute.Play })
    val chrome = LocalTvChrome.current
    val chipsFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    var screenHasFocus by remember { mutableStateOf(false) }
    var pageHasFocus by remember { mutableStateOf(false) }

    BackHandler(enabled = stack.isNotEmpty()) { WatchNav.back() }
    BackHandler(enabled = stack.isEmpty() && pageHasFocus) { runCatching { chipsFocus.requestFocus() } }
    // The player fills the screen: the chips at the top step aside while it is up, and come back with the page.
    LaunchedEffect(top is WatchRoute.Play) { chrome.contentScrolled = top is WatchRoute.Play }
    // A page opened or closed takes the focused card with it: focus goes to the top of the page now shown, unless it is up in the
    // chips at the top.
    LaunchedEffect(stack) {
        delay(120)
        if (WatchNav.stack.value.lastOrNull() !is WatchRoute.Play && chrome.pageFocused && !screenHasFocus) runCatching { pageFocus.requestFocus() }
    }

    CosmoLookTheme(dark = true, legacy = { content -> content() }) {
        // TvFocusIndication stays for the pieces in popups (menus); under the ring host it draws nothing.
        CompositionLocalProvider(LocalIndication provides TvFocusIndication) {
            ProvideWatch(repository = repository, playback = playback, downloads = downloads,
                qrCode = { payload, size -> com.cosmotune.music.ui.components.QrCode(payload, size = size) }) {
                Box(modifier.fillMaxSize().onFocusChanged { screenHasFocus = it.hasFocus }) {
                    if (top is WatchRoute.Play) {
                        TvWatchPlayer(top, playback, Modifier.fillMaxSize())
                    } else Column(Modifier.fillMaxSize().padding(top = TvDimensions.ContentTop)) {
                        if (stack.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRestorer(chipsFocus)
                                    .focusGroup()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = TvDimensions.SafeMarginHorizontal, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                WatchTab.entries.forEach { t ->
                                    TvTabPill(
                                        label = t.label,
                                        selected = t == tab,
                                        onClick = { WatchNav.go(t) },
                                        modifier = if (t == tab) Modifier.focusRequester(chipsFocus) else Modifier,
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .focusRequester(pageFocus)
                                .onFocusChanged { pageHasFocus = it.hasFocus }
                                .tvRingHost(cornerCap = 22.dp * TV_WATCH_SCALE)
                                .focusGroup(),
                        ) {
                            TvWatchScale {
                                // The pages' own thin keyboard ring stays off: the TV's ring above is the one focus shows.
                                CompositionLocalProvider(LocalFocusModality provides remember { FocusModality() }) {
                                    // Side gutters that put the first card on the TV's safe margin once scaled (the pages add 16).
                                    val gutter = TvDimensions.SafeMarginHorizontal / TV_WATCH_SCALE - 16.dp
                                    WatchApp(
                                        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 24.dp),
                                        showNavigation = false,
                                        wideLayout = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Draws [content] [TV_WATCH_SCALE] larger: every dp and sp beneath it (the TV's own UI scale stays applied underneath). */
@Composable
private fun TvWatchScale(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scaled = remember(base) { Density(base.density * TV_WATCH_SCALE, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * Watch's player on the TV, as the phone builds it: [AndroidWatch.player] on Video mode's Media3 engine, built the first time Play
 * is pressed, its picture the engine's own surface (whole or cut to fill). Audio focus keeps Music and Watch from playing over each
 * other, as it does for Video.
 */
@Composable
private fun rememberTvWatchPlayback(context: Context, wanted: Boolean): WatchPlayback? {
    val player = remember(context, wanted) {
        if (wanted || AndroidWatch.playerBuilt) runCatching { AndroidWatch.player(context) }.getOrNull() else null
    }
    return remember(player) {
        player?.let { p ->
            WatchPlayback(p) { scale, surfaceModifier ->
                androidVideoGraph(context.applicationContext).surface?.Surface(
                    surfaceModifier,
                    if (scale == WatchSurfaceScale.CROP) VideoScale.FILL else VideoScale.FIT,
                )
            }
        }
    }
}

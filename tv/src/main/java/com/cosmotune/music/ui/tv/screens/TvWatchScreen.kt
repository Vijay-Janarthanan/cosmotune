package com.cosmotune.music.ui.tv.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.tv.components.LocalTvChrome
import com.cosmotune.music.ui.tv.components.ProvideTvComfortScrolling
import com.cosmotune.music.ui.tv.components.TvFocusIndication
import com.cosmotune.music.ui.tv.components.tvRingHost
import com.cosmotune.music.ui.tv.player.TvWatchPlayer
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.music.ui.tv.theme.TvThemeColors
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
 * piece ([tvRingHost]) rather than the pages' own thin one. Watch's tabs are not a second row here: they follow the Watch chip in
 * the TV's own chips at the top, and the home's All, Films, Series and Live TV chips are left off (the tabs say the same). The page
 * runs up under the chips from the top of the screen, so its wash is the whole screen's, and the chips step aside once the page is
 * scrolled. D-pad: down from the chips enters the page (the featured card, then the rows), left and right walk a row, up comes
 * back; Back from the page is the TV's own (up to the chips, then Home). A film or series page shows its own back header and Back
 * closes it; Play opens the TV's own player ([TvWatchPlayer]) over the whole screen with the page kept underneath, so Back from
 * the player comes back to the page as it was.
 */
@Composable
fun TvWatchScreen(modifier: Modifier = Modifier) {
    val stack by WatchNav.stack.collectAsState()
    val top = stack.lastOrNull()
    val playing = top is WatchRoute.Play
    val context = LocalContext.current
    val repository = remember(context) { AndroidWatch.repository(context).also(WatchBackend::install) }
    val downloads = remember(context) { AndroidWatch.downloads(context) }
    val playback = rememberTvWatchPlayback(context, wanted = stack.any { it is WatchRoute.Play })
    val chrome = LocalTvChrome.current
    val pageFocus = remember { FocusRequester() }
    var screenHasFocus by remember { mutableStateOf(false) }
    var scrolled by remember { mutableStateOf(false) }

    BackHandler(enabled = stack.isNotEmpty()) { WatchNav.back() }
    // The chips at the top step aside while the page is scrolled away from its top, and while the player fills the screen.
    LaunchedEffect(scrolled, playing, chrome.epoch) { chrome.contentScrolled = scrolled || playing }
    // A page opened or closed takes the focused card with it: focus goes to the top of the page now shown, unless it is up in the
    // chips at the top. The page under the player is kept as it was (its filters, its scroll), so coming back from the player
    // returns to the same place in it.
    // The stack when focus came into the page, and whether focus left it because the page it was on went (a film's page opening
    // over the card, or closing) rather than by the remote: then it is taken back into the page now shown.
    var gainedWith by remember { mutableStateOf<List<WatchRoute>?>(null) }
    var reclaim by remember { mutableStateOf(false) }
    LaunchedEffect(stack, reclaim) {
        delay(120)
        val wanted = reclaim || (chrome.pageFocused && !screenHasFocus)
        reclaim = false
        if (WatchNav.stack.value.lastOrNull() !is WatchRoute.Play && wanted) runCatching { pageFocus.requestFocus() }
    }

    CosmoLookTheme(dark = true, legacy = { content -> content() }) {
        // TvFocusIndication stays for the pieces in popups (menus); under the ring host it draws nothing.
        CompositionLocalProvider(LocalIndication provides TvFocusIndication) {
            ProvideWatch(repository = repository, playback = playback, downloads = downloads,
                qrCode = { payload, size -> com.cosmotune.music.ui.components.QrCode(payload, size = size) }) {
                Box(modifier.fillMaxSize().onFocusChanged { screenHasFocus = it.hasFocus }.focusGroup()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // Not drawn under the player, whose picture is behind the window (the player keeps the remote to itself).
                            .graphicsLayer { alpha = if (playing) 0f else 1f }
                            .focusRequester(pageFocus)
                            .onFocusChanged {
                                if (it.hasFocus) gainedWith = WatchNav.stack.value
                                else if (gainedWith != null && gainedWith != WatchNav.stack.value) reclaim = true
                                if (!it.hasFocus) gainedWith = null
                            }
                            .tvRingHost(cornerCap = 22.dp * TV_WATCH_SCALE)
                            .focusGroup(),
                    ) {
                        ProvideTvComfortScrolling(top = WatchScrollMargin, bottom = WatchScrollMargin) {
                            TvWatchScale {
                                // The pages' own thin keyboard ring stays off: the TV's ring above is the one focus shows.
                                CompositionLocalProvider(LocalFocusModality provides remember { FocusModality() }) {
                                    WatchApp(
                                        // Under the chips at the top; rows start on the TV's safe margin once scaled and run out to the edges.
                                        contentPadding = PaddingValues(top = (TvDimensions.ContentTop - 20.dp) / TV_WATCH_SCALE, bottom = 24.dp),
                                        showNavigation = false,
                                        wideLayout = true,
                                        side = TvDimensions.SafeMarginHorizontal / TV_WATCH_SCALE,
                                        filterChips = false,
                                        pageUnderPlayer = true,
                                        onScrolled = { scrolled = it },
                                    )
                                }
                            }
                        }
                        // Once the page has scrolled, what passes under the chips fades into the ground behind them.
                        if (scrolled && !playing) {
                            val ground = TvThemeColors.current.ground
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(TvDimensions.ContentTop + 12.dp)
                                    .background(Brush.verticalGradient(0f to ground, 0.55f to ground.copy(alpha = 0.85f), 1f to Color.Transparent)),
                            )
                        }
                    }
                    if (top is WatchRoute.Play) TvWatchPlayer(top, playback, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** How far from the screen's edges a focused piece of a Watch page is kept when the page scrolls to it, both ways. */
private val WatchScrollMargin = 52.dp

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

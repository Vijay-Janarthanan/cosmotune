package com.cosmotune.music.ui.tv.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.tv.components.LocalTvChrome
import com.cosmotune.music.ui.tv.components.TvChromePinned
import com.cosmotune.music.ui.tv.components.TvFocusIndication
import com.cosmotune.music.ui.tv.components.TvTabPill
import com.cosmotune.music.ui.tv.player.TvWatchPlayer
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.music.watch.AndroidWatch
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

/**
 * Watch mode on the TV: the shared Watch screens ([WatchApp], films, series and live TV from the plugins that are on), hosted the
 * way the phone hosts them — the real repository ([AndroidWatch.repository], installed as [WatchBackend]'s), the downloads and the
 * player on Video mode's Media3 engine ([AndroidWatch.player]) — with the QR drawer the phone's. Watch's pages read the Orbit look
 * from the Cosmo theme, so it is installed (dark) around them; the TV's peach focus ring is put back over Material's ripple so the
 * D-pad shows where it is.
 *
 * Watch's tabs are TV chips over the page; a film or series page shows its own back header, and Back closes it. Play opens the
 * TV's own player ([TvWatchPlayer]) over the whole screen, the chips at the top stepping aside.
 */
@Composable
fun TvWatchScreen(modifier: Modifier = Modifier) {
    TvChromePinned()
    val tab by WatchNav.tab.collectAsState()
    val stack by WatchNav.stack.collectAsState()
    val top = stack.lastOrNull()
    BackHandler(enabled = stack.isNotEmpty()) { WatchNav.back() }
    val context = LocalContext.current
    val repository = remember(context) { AndroidWatch.repository(context).also(WatchBackend::install) }
    val downloads = remember(context) { AndroidWatch.downloads(context) }
    val playback = rememberTvWatchPlayback(context, wanted = stack.any { it is WatchRoute.Play })
    // The player fills the screen: the chips at the top step aside while it is up, and come back with the page.
    val chrome = LocalTvChrome.current
    LaunchedEffect(top is WatchRoute.Play) { chrome.contentScrolled = top is WatchRoute.Play }
    CosmoLookTheme(dark = true, legacy = { content -> content() }) {
        CompositionLocalProvider(LocalIndication provides TvFocusIndication) {
            ProvideWatch(repository = repository, playback = playback, downloads = downloads,
                qrCode = { payload, size -> com.cosmotune.music.ui.components.QrCode(payload, size = size) }) {
                if (top is WatchRoute.Play) {
                    TvWatchPlayer(top, playback, modifier.fillMaxSize())
                } else Column(modifier.fillMaxSize().padding(top = TvDimensions.ContentTop)) {
                    if (stack.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = TvDimensions.SafeMarginHorizontal, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            WatchTab.entries.forEach { t ->
                                TvTabPill(label = t.label, selected = t == tab, onClick = { WatchNav.go(t) })
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        WatchApp(contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 24.dp), showNavigation = false)
                    }
                }
            }
        }
    }
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

package com.cosmotune.music.ui.tv.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.tv.components.TvChromePinned
import com.cosmotune.music.ui.tv.components.TvFocusIndication
import com.cosmotune.music.ui.tv.components.TvTabPill
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.ui.theme.CosmoLookTheme
import com.cosmotune.ui.watch.ProvideWatch
import com.cosmotune.ui.watch.WatchApp
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchTab

/**
 * Watch mode on the TV: the shared Watch screens ([WatchApp], films, series and live TV from the plugins that are on), hosted the
 * way the phone hosts them — the repository is [com.cosmotune.ui.watch.WatchBackend]'s, no player is provided yet (the player page
 * shows its "not connected" card), and the QR drawer is the phone's. Watch's pages read the Orbit look from the Cosmo theme, so it
 * is installed (dark) around them; the TV's peach focus ring is put back over Material's ripple so the D-pad shows where it is.
 *
 * Watch's tabs are TV chips over the page; a film or series page shows its own back header, and Back closes it.
 */
@Composable
fun TvWatchScreen(modifier: Modifier = Modifier) {
    TvChromePinned()
    val tab by WatchNav.tab.collectAsState()
    val stack by WatchNav.stack.collectAsState()
    BackHandler(enabled = stack.isNotEmpty()) { WatchNav.back() }
    CosmoLookTheme(dark = true, legacy = { content -> content() }) {
        CompositionLocalProvider(LocalIndication provides TvFocusIndication) {
            ProvideWatch(qrCode = { payload, size -> com.cosmotune.music.ui.components.QrCode(payload, size = size) }) {
                Column(modifier.fillMaxSize().padding(top = TvDimensions.ContentTop)) {
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

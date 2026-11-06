package com.cosmotune.music.ui.tv.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.tv.components.TvChromePinned
import com.cosmotune.music.ui.tv.components.TvFocusIndication
import com.cosmotune.music.ui.tv.components.tvRingHost
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.ui.components.FocusModality
import com.cosmotune.ui.components.LocalFocusModality
import com.cosmotune.ui.live.LiveNowPage
import com.cosmotune.ui.theme.CosmoLookTheme

/**
 * "Live now" on the TV, the page the Live pill of the chips row opens (Canvas repo, design/cosmotune/live-strip/board.html): the
 * shared panel ([LiveNowPage]) drawn as large as Watch's pages are ([TV_WATCH_SCALE]), on the dark Orbit look, the TV's thick ring
 * for focus. Watch on a card or a row opens Watch's player through the host ([com.cosmotune.ui.live.LiveNowHost.play]).
 */
@Composable
fun TvLiveScreen(modifier: Modifier = Modifier) {
    TvChromePinned()
    CosmoLookTheme(dark = true, legacy = { content -> content() }) {
        CompositionLocalProvider(LocalIndication provides TvFocusIndication) {
            Box(
                modifier.fillMaxSize().padding(top = TvDimensions.ContentTop)
                    .tvRingHost(cornerCap = 22.dp * TV_WATCH_SCALE)
                    .focusGroup(),
            ) {
                val base = LocalDensity.current
                val scaled = remember(base) { Density(base.density * TV_WATCH_SCALE, base.fontScale) }
                CompositionLocalProvider(LocalDensity provides scaled, LocalFocusModality provides remember { FocusModality() }) {
                    val side = TvDimensions.SafeMarginHorizontal / TV_WATCH_SCALE
                    LiveNowPage(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 40.dp))
                }
            }
        }
    }
}

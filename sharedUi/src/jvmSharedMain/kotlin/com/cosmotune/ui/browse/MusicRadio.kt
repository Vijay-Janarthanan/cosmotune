package com.cosmotune.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.radio.MixGroups
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitColourCardRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRecordRow
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.OrbitSpace

/**
 * Music's Radio (upstream 1.10's Radio tab, GPL-3.0): the signed-in listener's "Mixed for you" mixes, at the top of Explore. Explore
 * rather than a fifth tab, because the phone's dock (Home, Explore, Releases, Library and the round Search) has no room for another
 * at 360 dp without dropping the chosen tab's name, and mixes are a way into music as moods and genres are. Signed out there is
 * nothing (the page is the account's own); [rememberMusicRadio] is null then and Explore shows its moods and genres alone.
 */
/** Radio's rows for Explore ([com.cosmotune.ui.search.CosmoBrowsePage]), provided by each app around its Explore page; none by default. */
val LocalExploreRadio = androidx.compose.runtime.staticCompositionLocalOf<(LazyListScope.(side: Dp) -> Unit)?> { null }

@Composable
fun rememberMusicRadio(refreshKey: Any? = null): UiState<MixGroups>? {
    val cookie by Innertube.cookieState.collectAsState()
    val signedIn = cookie != null
    val state by produceState<UiState<MixGroups>?>(if (signedIn) UiState.Loading else null, signedIn, refreshKey) {
        if (!signedIn) { value = null; return@produceState }
        if (value !is UiState.Success) value = UiState.Loading
        value = YtMusicRepository.mixedForYou().fold(
            onSuccess = { groups -> UiState.Success(groups) },
            onFailure = { UiState.Error("Your mixes could not be loaded") },
        )
    }
    return state
}

/**
 * Radio's rows: the named mixes (My Supermix, Discover Mix, Replay Mix …) as colour cards, then "Your mixes" and each mood's mixes as
 * records, the shelves Home draws. A tap opens a mix, a hold opens its menu. While the mixes load a quiet line says so; a failure says
 * so with Try again, and Explore's moods and genres follow either way.
 */
fun LazyListScope.musicRadio(
    radio: UiState<MixGroups>?,
    side: Dp,
    fonts: OrbitFonts,
    look: OrbitLook,
    onOpen: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    onLongPress: ((ShelfItem) -> Unit)? = null,
) {
    if (radio == null) return
    val heading = Modifier.padding(horizontal = side + 4.dp).padding(top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent)
    when (radio) {
        is UiState.Loading -> item(key = "radio:loading") {
            Column(heading) {
                OrbitSectionHeader("Radio", "Mixes made for you", fonts, look, Modifier)
                Text("Looking for your mixes…", style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 12.dp))
            }
        }
        is UiState.Error -> item(key = "radio:error") {
            Column(heading) {
                OrbitSectionHeader("Radio", radio.message, fonts, look, Modifier)
                OrbitChip("Try again", fonts, look, onClick = onRetry, modifier = Modifier.padding(top = 12.dp), leading = null)
            }
        }
        is UiState.Success -> {
            val groups = radio.data
            if (groups.isEmpty) return
            if (groups.stations.isNotEmpty()) item(key = "radio:stations") {
                Column(Modifier.testTag("music:radio")) {
                    OrbitSectionHeader("Radio", "Mixes made for you", fonts, look, heading)
                    OrbitColourCardRow(groups.stations, fonts, side, onOpen, onLongPress)
                }
            }
            if (groups.yours.isNotEmpty()) item(key = "radio:yours") {
                Column {
                    OrbitSectionHeader("Your mixes", if (groups.stations.isEmpty()) "Radio made from what you play" else "", fonts, look, heading)
                    OrbitRecordRow(groups.yours, fonts, look, side, onOpen, onLongPress)
                }
            }
            groups.moods.forEach { mood ->
                item(key = "radio:mood:" + mood.name.lowercase()) {
                    Column {
                        OrbitSectionHeader(mood.name, "", fonts, look, heading)
                        OrbitRecordRow(mood.items, fonts, look, side, onOpen, onLongPress)
                    }
                }
            }
        }
    }
}

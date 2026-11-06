package com.cosmotune.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle
import com.cosmotune.music.ui.screens.localizeCardSubtitle
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.OrbitPortraitRow
import com.cosmotune.ui.orbit.OrbitRecord
import com.cosmotune.ui.orbit.OrbitShelfRow
import com.cosmotune.ui.blocks.shelfKey
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * A mood's or genre's page (`searchMood`, `screens-more-a.js:93-96`) in Orbit: its name as the page heading (the wide face with its
 * peach full stop) and the shelves of what it opens, each a section heading over a row of 150 dp records (artists as round
 * portraits). The records carry no play moon: [MoodGenreSwitch] has no callback that plays, so a tap opens the item. It takes
 * [MoodGenreSwitch]'s parameters.
 */
@Composable
fun CosmoMoodPage(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val side = CosmoSpacing.margin
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "mood:title") { CosmoPageHeader(title = title) }
        when (state) {
            UiState.Loading -> items(3, key = { "mood:skeleton:$it" }) {
                // A heading and a row of discs, where the shelves will stand.
                Column(Modifier.fillMaxWidth().padding(start = side, end = side, top = 32.dp)) {
                    WatchSkeleton(look, Modifier.width(180.dp).height(20.dp), radius = 10.dp)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        repeat(3) { WatchSkeleton(look, Modifier.size(150.dp), radius = 75.dp) }
                    }
                }
            }
            is UiState.Error -> item(key = "mood:error") {
                CosmoEmptyState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            // A feed can repeat a shelf's title, and a repeated key would stop the list, so the place is part of the key.
            is UiState.Success -> itemsIndexed(state.data, key = { index, shelf -> "mood:shelf:${shelf.title}#$index" }) { _, shelf ->
                Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                    OrbitPageSection(
                        title = localizeShelfTitle(shelf.title),
                        subtitle = localizeShelfSubtitle(shelf.subtitle),
                        modifier = Modifier.padding(start = side + 4.dp, end = side, bottom = 14.dp),
                    )
                    val artists = shelf.items.isNotEmpty() && shelf.items.all { it.browseId?.startsWith("UC") == true }
                    if (artists) OrbitPortraitRow(shelf.items, fonts, look, side, onClick = onItemClick, size = 112.dp)
                    else OrbitShelfRow(side + 4.dp, 18.dp) {
                        itemsIndexed(shelf.items, key = { i, item -> shelfKey(item, i) }) { _, item ->
                            OrbitRecord(item.title, localizeCardSubtitle(item.subtitle), item.thumbnailUrl, fonts, look, onClick = { onItemClick(item) }, onPlay = null, size = 150.dp)
                        }
                    }
                }
            }
        }
    }
}

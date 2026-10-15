package com.cosmotune.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.blocks.CosmoShelf
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.retry
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.feedSkeleton
import org.jetbrains.compose.resources.stringResource

/**
 * A mood's or genre's page (`searchMood`, `screens-more-a.js:93-96`): its name in the page heading and the shelves of what it
 * opens, each a row of 150 dp cards. It takes [MoodGenreSwitch]'s parameters. The board's "Play the mood" and "Start a radio"
 * buttons are not here: [MoodGenreSwitch] has no callback for them, so there is nothing for them to call.
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
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "mood:title") { CosmoPageHeader(title = title) }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item(key = "mood:error") {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            // A feed can repeat a shelf's title, and a repeated key would stop the list, so the place is part of the key.
            is UiState.Success -> itemsIndexed(state.data, key = { index, shelf -> "mood:shelf:${shelf.title}#$index" }) { _, shelf ->
                CosmoShelf(
                    title = shelf.title,
                    items = shelf.items,
                    onItemClick = onItemClick,
                    subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                    cardWidth = 150.dp,
                    leadFirst = false,
                )
            }
        }
    }
}

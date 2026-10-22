package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.music.ui.components.LocalShelfRowChrome

/**
 * A shelf of [VideoShelfCard]s (spec 5.0, 5.2): 22 dp of room above, the heading ([CosmoSectionHeader], [title]), and a row of
 * cards that scrolls sideways, 16 dp in from each side and 12 dp apart. A tap on a card calls [onItemClick]; its menu, [onItemMore].
 * [watched] gives a video's watched fraction by id (the watched bar of "Continue watching"), [downloaded] says whether a video is
 * downloaded, and [cardWidth] sets the cards (280 on the desktop). The cards are keyed by video id (a repeat in the list gets a
 * suffix, so a feed that lists a video twice cannot crash the row). On the desktop the row is wrapped in the platform's shelf
 * chrome (the arrows, the drag and the horizontal wheel), as part two's shelves are.
 */
@Composable
fun VideoShelf(
    title: String,
    items: List<VideoItem>,
    onItemClick: (VideoItem) -> Unit,
    onItemMore: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    watched: (String) -> Float? = { null },
    cardWidth: Dp = 240.dp,
    downloaded: (String) -> Boolean = { false },
) {
    val keys = remember(items) { uniqueKeys(items) }
    val rowState = rememberLazyListState()
    Column(modifier.padding(top = SHELF_TOP)) {
        CosmoSectionHeader(title = title)
        LocalShelfRowChrome.current.Wrap(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(VideoMetrics.SHELF_GAP_DP.dp),
            ) {
                items(count = items.size, key = { keys[it] }) { index ->
                    val item = items[index]
                    VideoShelfCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onMore = { onItemMore(item) },
                        watchedFraction = watched(item.id),
                        width = cardWidth,
                        downloaded = downloaded(item.id),
                    )
                }
            }
        }
    }
}

/** Room above a shelf, as part two's shelves have it (`.shelf2`). */
private val SHELF_TOP = 22.dp

/**
 * The keys of a list of videos: each video's id, and for a repeat of an id in the same list the id with `#` and the number of the
 * repeat. A lazy list refuses two items with one key, and a feed can list a video twice (two pages of one kiosk); the first
 * occurrence keeps the plain id, so the keys stay stable as the list grows.
 */
internal fun uniqueKeys(items: List<VideoItem>): List<String> {
    val seen = HashMap<String, Int>(items.size * 2)
    return items.map { item ->
        val before = seen[item.id] ?: 0
        seen[item.id] = before + 1
        if (before == 0) item.id else "${item.id}#$before"
    }
}

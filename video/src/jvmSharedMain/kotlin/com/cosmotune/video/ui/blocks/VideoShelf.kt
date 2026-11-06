package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.OrbitShelfRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem

/**
 * A shelf of [VideoShelfCard]s (Orbit board B): the section heading in the wide face ([OrbitSectionHeader], [title]), and a row of cards
 * that scrolls sideways in the kit's shelf row (the desktop's arrows, drag and wheel), 16 dp in from each side and 18 apart. A tap on
 * a card calls [onItemClick]; its menu, [onItemMore]. [watched] gives a video's watched fraction by id (the watched line of "Continue
 * watching"), [downloaded] says whether a video is downloaded, and [cardWidth] sets the cards. The cards are keyed by video id (a
 * repeat in the list gets a suffix, so a feed that lists a video twice cannot crash the row).
 */
@Composable
fun VideoShelf(
    title: String,
    items: List<VideoItem>,
    onItemClick: (VideoItem) -> Unit,
    onItemMore: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    watched: (String) -> Float? = { null },
    cardWidth: Dp = VideoMetrics.SHELF_CARD_W_DP.dp,
    downloaded: (String) -> Boolean = { false },
) {
    val keys = remember(items) { uniqueKeys(items) }
    val indexed = remember(items) { items.indices.toList() }
    Column(modifier) {
        OrbitSectionHeader(title, "", orbitFonts(), orbitLook(), Modifier.padding(start = CosmoSpacing.margin + 6.dp, end = CosmoSpacing.margin, top = 20.dp, bottom = 14.dp))
        OrbitShelfRow(CosmoSpacing.margin, 18.dp) {
            items(indexed, key = { keys[it] }) { index ->
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

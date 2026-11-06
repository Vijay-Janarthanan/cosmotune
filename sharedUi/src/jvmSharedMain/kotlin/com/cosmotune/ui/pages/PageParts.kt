package com.cosmotune.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.Song

/*
 * The pieces the album, playlist and artist pages share besides the Orbit kit: their numbers, the filter that the find-in-list field
 * applies, the rule for a shelf of artists, and the skeleton of a page's list.
 */

/** The numbers of the pages. Dp as floats, like the other metrics objects. */
internal object PageMetrics {
    /** The room between the bar and the page's colour card. */
    const val COVER_TOP_DP = 8f

    /** The track rows of a release (`.tracks2 .mrow`), which the release page's skeleton stands for. */
    const val TRACK_ROW_DP = BlockMetrics.TRACK_ROW_DP

    /** Where the find-in-list field stands once open: after the card and the list controls. */
    const val SEARCH_ITEM_INDEX = 2
}

/**
 * The songs a typed query leaves standing, each still carrying its place in the full list (an album's track numbers are the release's
 * own, not positions in what the filter left). The rule of the find-in-list field: the title or the artist contains the query,
 * whatever the case; a blank query leaves everything.
 */
internal fun List<Song>.matchingQuery(query: String): List<IndexedValue<Song>> {
    val all = withIndex().toList()
    val needle = query.trim()
    if (needle.isEmpty()) return all
    return all.filter { (_, song) -> song.title.contains(needle, ignoreCase = true) || song.artist.contains(needle, ignoreCase = true) }
}

/** Whether every card of the shelf is an artist (a browse id of a channel, `UC...`): artists are drawn as circles. */
internal fun HomeShelf.isArtists(): Boolean = items.isNotEmpty() && items.all { it.browseId?.startsWith("UC") == true }

// ---- the skeleton of a page's list -------------------------------------------------------------------------------------------

/** A 48 dp cover, 16 dp before two lines 12 dp tall and 8 apart, ragged in width so they read as text (DESIGN_SYSTEM.md 4.15). */
private val SKELETON_COVER_SHAPE = RoundedCornerShape(CosmoRadii.rSm)
private val SKELETON_LINE_SHAPE = RoundedCornerShape(CosmoRadii.rXs)
private val SKELETON_TITLE_WIDTHS = floatArrayOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val SKELETON_SUBTITLE_WIDTHS = floatArrayOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

/** The place of the page's buttons: two 40 dp pills, and the 56 dp play circle at the end. */
private val SKELETON_PILL_SHAPE = RoundedCornerShape(50)

/**
 * A page's list while its songs load: the place of the page's buttons, then [rows] rows [rowDp] tall, 16 dp from the sides, each a
 * 48 dp cover and two lines. Every block is the cover's own skeleton
 * ([CosmoShimmer]: `skelBase` with a sweep of `skelShine`, still under reduced motion) at full strength: the old `detailSkeleton`
 * dimmed its blocks to 40 % over the page, which read as a grey wash, and drew the old page's round buttons. Keys are stable.
 */
internal fun LazyListScope.pageSkeleton(keyPrefix: String, rowDp: Float, rows: Int = 8) {
    item(key = keyPrefix + ":actions", contentType = "skeleton-actions") {
        Row(
            Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoShimmer(Modifier.size(width = 104.dp, height = 40.dp), SKELETON_PILL_SHAPE)
            CosmoShimmer(Modifier.size(width = 88.dp, height = 40.dp), SKELETON_PILL_SHAPE)
            Spacer(Modifier.weight(1f))
            CosmoShimmer(Modifier.size(56.dp), CircleShape)
        }
    }
    items(rows, key = { keyPrefix + ":row:" + it }, contentType = { "skeleton-row" }) { index ->
        Row(
            Modifier.fillMaxWidth().height(rowDp.dp).padding(horizontal = CosmoSpacing.margin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoShimmer(Modifier.size(BlockMetrics.ROW_ART_DP.dp), SKELETON_COVER_SHAPE)
            Spacer(Modifier.width(BlockMetrics.ROW_ART_GAP_DP.dp))
            Column(Modifier.weight(1f)) {
                CosmoShimmer(Modifier.fillMaxWidth(SKELETON_TITLE_WIDTHS[index % SKELETON_TITLE_WIDTHS.size]).height(12.dp), SKELETON_LINE_SHAPE)
                Spacer(Modifier.height(8.dp))
                CosmoShimmer(Modifier.fillMaxWidth(SKELETON_SUBTITLE_WIDTHS[index % SKELETON_SUBTITLE_WIDTHS.size]).height(12.dp), SKELETON_LINE_SHAPE)
            }
        }
    }
}

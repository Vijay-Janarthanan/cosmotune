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
 * applies, and the rule for a shelf of artists. Their placeholders are the kit's ([com.cosmotune.ui.orbit.orbitRowSkeletons]).
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
    return all.filter { (_, song) -> com.cosmotune.music.data.LooseMatch.matches(needle, song.title, song.artist) }
}

/** Whether every card of the shelf is an artist (a browse id of a channel, `UC...`): artists are drawn as circles. */
internal fun HomeShelf.isArtists(): Boolean = items.isNotEmpty() && items.all { it.browseId?.startsWith("UC") == true }

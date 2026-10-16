package com.cosmotune.video.ui.downloads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.downloads.DownloadsFilter
import com.cosmotune.video.downloads.DownloadsSort
import org.jetbrains.compose.resources.StringResource

/*
 * The Downloads page's own state and numbers (plan task Y-33). What the page shows is worked out by the pure plan in :shared
 * (`com.cosmotune.video.downloads.DownloadsPlan`); this file keeps what the page remembers while it is in the stack (the filter,
 * the sort, the collapsed batches) and the numbers of spec 5.10 that `VideoMetrics` does not carry.
 */

/** The numbers of the Downloads page (spec 5.10, 5.0). */
internal object DownloadsMetrics {
    /** "Wide: the same list, 840 dp at most." */
    const val LIST_MAX_WIDTH_DP = 840f

    /** A wide page's side margin besides the rows' own 16: together the desktop's 28 (`.d2-main`). */
    const val WIDE_SIDE_DP = VideoMetrics.DESKTOP_MARGIN_DP - VideoMetrics.MARGIN_DP

    /** The heading (`.pg-head2`, as Library's): 10 dp above, the margin less 4 at the end for the 40 dp button's own air. */
    const val HEADING_TOP_DP = 10f
    const val HEADING_END_DP = VideoMetrics.MARGIN_DP - 4f

    /** The storage line: 13 sp `textSecondary`, 4 dp under the heading. */
    const val STORAGE_SP = 13f
    const val STORAGE_TOP_DP = 4f

    /** A download's state where a row's meta line stands: 12.5 sp (spec 5.10: "Waiting" in 12.5 sp, the video row's meta size). */
    const val STATE_SP = VideoMetrics.ROW_META_SP

    /** The end of a row: the more button's 40 dp place, holding the 24 dp progress ring or the 24 dp loading blob. */
    const val END_DP = VideoMetrics.MORE_BUTTON_DP
    const val RING_DP = 24f
    const val RING_STROKE_DP = 2.5f
    const val BLOB_DP = 24f

    /** A batch's header (spec 5.10): the title 15.5 sp 650, "12 of 24" 13 sp, a 3 dp progress line, 40 dp TONAL buttons 8 dp apart. */
    const val BATCH_TITLE_SP = VideoMetrics.FEED_TITLE_SP
    const val BATCH_COUNTER_SP = VideoMetrics.FEED_META_SP
    const val BATCH_LINE_DP = 3f
    const val BATCH_LINE_TOP_DP = 8f
    const val BATCH_BUTTON_DP = 40f
    const val BATCH_BUTTON_GAP_DP = 8f
    const val BATCH_PAD_TOP_DP = 14f
    const val BATCH_PAD_BOTTOM_DP = 4f
    const val BATCH_CHEVRON_DP = 20f

    /** How long the page's toast stays (`CosmoToast`: 4 s). */
    const val TOAST_MS = 4_000L

    /** The storage line is measured again this long after a change, once a deletion has had time to free the space. */
    const val REMEASURE_MS = 1_500L
}

/**
 * What the Downloads page remembers while it is in the stack (kept with the page's saved state, [Saver]): the [filter] chip, the
 * [sort] of the menu and the batches the user [collapsed].
 */
@Stable
internal class DownloadsPageState(filter: DownloadsFilter, sort: DownloadsSort, collapsed: Set<String>) {
    var filter by mutableStateOf(filter)
    var sort by mutableStateOf(sort)
    var collapsed by mutableStateOf(collapsed)
        private set

    /** Collapses batch [batchId], or opens it again. */
    fun toggle(batchId: String) {
        collapsed = if (batchId in collapsed) collapsed - batchId else collapsed + batchId
    }

    companion object {
        /** The state as a list of text: the filter's name, the sort's name, then the collapsed batch ids. A name it does not know reads as the default. */
        val Saver: Saver<DownloadsPageState, Any> = listSaver(
            save = { listOf(it.filter.name, it.sort.name) + it.collapsed },
            restore = { saved ->
                DownloadsPageState(
                    filter = DownloadsFilter.entries.firstOrNull { it.name == saved.getOrNull(0) } ?: DownloadsFilter.ALL,
                    sort = DownloadsSort.entries.firstOrNull { it.name == saved.getOrNull(1) } ?: DownloadsSort.NEWEST,
                    collapsed = saved.drop(2).toSet(),
                )
            },
        )
    }
}

/** The page's state: All, Newest, nothing collapsed, then whatever the user chose. */
@Composable
internal fun rememberDownloadsPageState(): DownloadsPageState =
    rememberSaveable(saver = DownloadsPageState.Saver) { DownloadsPageState(DownloadsFilter.ALL, DownloadsSort.NEWEST, emptySet()) }

/** One toast of the page ("Saved to Movies"); [serial] tells two of the same apart, so a second one shows again. */
@Immutable
internal data class DownloadsToast(val message: StringResource, val serial: Int)

/**
 * The column every block of the page stands in: the whole width on a phone; on a [wide] page (spec 5.10: "Wide: the same list,
 * 840 dp at most") at most 840 dp, centred, with [DownloadsMetrics.WIDE_SIDE_DP] more margin so the rows' own 16 make the desktop's 28.
 * Applied to each item rather than to the list, so the list still scrolls under a mouse anywhere across the page.
 */
internal fun Modifier.downloadsColumn(wide: Boolean): Modifier =
    if (!wide) this
    else this
        .fillMaxWidth()
        .padding(horizontal = DownloadsMetrics.WIDE_SIDE_DP.dp)
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = DownloadsMetrics.LIST_MAX_WIDTH_DP.dp)
        .fillMaxWidth()

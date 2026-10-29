package com.cosmotune.video.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_autoplay
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.resources.video_up_next
import com.cosmotune.video.resources.video_up_next_empty
import com.cosmotune.video.resources.video_up_next_error
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.video.ui.blocks.VideoCard
import com.cosmotune.video.ui.blocks.VideoRow
import com.cosmotune.video.ui.blocks.rowThumbnailSize
import com.cosmotune.video.ui.branding.rememberChannelAvatar
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.video.ui.home.CardSkeleton
import org.jetbrains.compose.resources.stringResource

/*
 * "Up next" (spec 5.4, item 6): the overline with the Autoplay switch at its end (`settings.autoplay`; the switch goes through the
 * playback, which stores the setting too), then the related videos as video rows, keyed by id. A row's tap plays it (a Short opens
 * the pager with the list's Shorts), its "more" opens the video sheet; titles and pictures come through DeArrow.
 *
 * The section never vanishes for a video that loaded: while the list is on its way (the details, or the second request for it) it
 * is a skeleton of rows, and when there is none it is a line saying so ("No suggestions for this video", or "Could not load the
 * suggestions") with "Try again".
 */

/** What the "Up next" section draws, from [WatchRules.upNext]. */
internal enum class UpNextShow { NONE, LIST, LOADING, EMPTY, FAILED }

/** How many placeholder rows the skeleton shows. */
private const val SKELETON_ROWS = 4

/**
 * The "Up next" section of the page's list: nothing ([UpNextShow.NONE]), the header and the related rows, the header and a
 * skeleton, or the header and a note with a retry. [desktop] takes the side column's rows.
 */
internal fun LazyListScope.upNextSection(
    view: WatchView,
    watched: State<Map<String, Float>>,
    downloaded: State<Set<String>>,
    desktop: Boolean,
    callbacks: WatchCallbacks,
) {
    if (view.upNext == UpNextShow.NONE) return
    item(key = "upnext") { UpNextHeader(view.autoplay, callbacks.onAutoplay) }
    when (view.upNext) {
        UpNextShow.LIST -> upNextRows(view.related, watched, downloaded, desktop, onPlay = callbacks.onPlay, onMore = callbacks.onMore)
        UpNextShow.LOADING -> items(SKELETON_ROWS, key = { "upnext-skeleton:$it" }) { UpNextSkeletonRow(desktop) }
        UpNextShow.EMPTY, UpNextShow.FAILED ->
            item(key = "upnext-note") { UpNextNote(failed = view.upNext == UpNextShow.FAILED, onRetry = callbacks.onRetryRelated) }
        UpNextShow.NONE -> Unit
    }
}

/** A related video's row while the list loads: the shape of the row (desktop) or of the feed card (phone), in the shimmer. */
@Composable
internal fun UpNextSkeletonRow(desktop: Boolean) {
    if (!desktop) {
        CardSkeleton(Modifier.padding(horizontal = CosmoSpacing.margin).padding(bottom = VideoMetrics.FEED_AFTER_DP.dp))
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = VideoMetrics.ROW_PAD_V_DP.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CosmoShimmer(Modifier.rowThumbnailSize(true), ThumbnailShape)
        Spacer(Modifier.width(CosmoSpacing.gutter))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SKELETON_LINE_GAP)) {
            CosmoShimmer(Modifier.fillMaxWidth().height(SKELETON_TITLE_HEIGHT))
            CosmoShimmer(Modifier.fillMaxWidth(SKELETON_META_FRACTION).height(SKELETON_META_HEIGHT))
        }
    }
}

private val SKELETON_LINE_GAP = 8.dp
private val SKELETON_TITLE_HEIGHT = 14.dp
private val SKELETON_META_HEIGHT = 12.dp
private const val SKELETON_META_FRACTION = 0.55f

/** The line under the header when there is no list: "No suggestions for this video" or "Could not load the suggestions", and "Try again". */
@Composable
internal fun UpNextNote(failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val message = stringResource(if (failed) Res.string.video_up_next_error else Res.string.video_up_next_empty)
    Column(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = NOTE_PAD_V)) {
        Text(message, style = type.forText(type.label, message), color = Color(palette.textSecondary))
        Spacer(Modifier.height(NOTE_GAP))
        CosmoButton(stringResource(Res.string.video_retry), onRetry, kind = CosmoButtonKind.TONAL, size = CosmoButtonSize.SMALL)
    }
}

private val NOTE_PAD_V = 8.dp
private val NOTE_GAP = 12.dp

/** The gap between "Autoplay" and its switch. */
private val SWITCH_GAP = 8.dp

/**
 * The overline keeps only 4 dp at its end (room for a text button's own 12 dp of padding); the switch has none, so it gives the 12
 * back and stands at the page's 16 dp margin.
 */
private val SWITCH_END = 12.dp

/** "Up next" with "Autoplay" and its switch at the end. */
@Composable
internal fun UpNextHeader(autoplay: Boolean, onAutoplay: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val label = stringResource(Res.string.video_autoplay)
    CosmoOverline(
        text = stringResource(Res.string.video_up_next),
        modifier = modifier,
        trailing = {
            Row(Modifier.padding(end = SWITCH_END), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = type.forText(type.label, label), color = Color(palette.textSecondary), modifier = Modifier.clearAndSetSemantics { })
                Spacer(Modifier.width(SWITCH_GAP))
                CosmoSwitch(checked = autoplay, onCheckedChange = onAutoplay, modifier = Modifier.semantics { contentDescription = label })
            }
        },
    )
}

/**
 * The related videos as rows (spec 5.4), keyed by id: [watched] gives each one's watched bar, [downloaded] marks finished downloads;
 * [desktop] takes the desktop row's thumbnail (168 x 94.5).
 */
internal fun LazyListScope.upNextRows(
    related: List<VideoItem>,
    watched: State<Map<String, Float>>,
    downloaded: State<Set<String>>,
    desktop: Boolean,
    onPlay: (VideoItem) -> Unit,
    onMore: (VideoItem) -> Unit,
) {
    items(related, key = { "related:${it.id}" }) { item ->
        UpNextRow(item, watched.value[item.id], item.id in downloaded.value, desktop, onPlay, onMore)
    }
}

/** One related video: [VideoRow] with DeArrow's title and picture. */
@Composable
internal fun UpNextRow(item: VideoItem, watchedFraction: Float?, downloaded: Boolean, desktop: Boolean, onPlay: (VideoItem) -> Unit, onMore: (VideoItem) -> Unit) {
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    val avatar = rememberChannelAvatar(item)
    val shown = remember(item, picture, avatar) {
        if (picture == item.thumbnailUrl && avatar == item.channelAvatarUrl) item else item.copy(thumbnailUrl = picture, channelAvatarUrl = avatar)
    }
    if (desktop) {
        // The side column's compact row, as the video apps have it beside the player.
        VideoRow(
            item = shown,
            onClick = { onPlay(item) },
            onMore = { onMore(item) },
            watchedFraction = watchedFraction,
            desktop = true,
            title = title,
            downloaded = downloaded,
        )
    } else {
        // The phone's full-width card with the channel's picture, as in the feed.
        VideoCard(
            item = shown,
            onClick = { onPlay(item) },
            onMore = { onMore(item) },
            watchedFraction = watchedFraction,
            title = title,
            downloaded = downloaded,
        )
    }
}

/**
 * The watched bar of each of [items] from the history (spec 5.0: shown from 10 s watched). The history changes every 10 s while a
 * video plays; the map is worked out again then, and the rows are redrawn only when it comes out different.
 */
@Composable
internal fun rememberWatchedFractions(items: List<VideoItem>): State<Map<String, Float>> {
    val history = LocalVideoGraph.current.library.history.collectAsState()
    return remember(items, history) { derivedStateOf { watchedFractions(items, history.value) } }
}

/** [items]' watched fractions from [history], for those that have a bar (spec 5.0). */
internal fun watchedFractions(items: List<VideoItem>, history: List<HistoryEntry>): Map<String, Float> {
    if (items.isEmpty() || history.isEmpty()) return emptyMap()
    val ids = items.mapTo(HashSet(items.size)) { it.id }
    val out = HashMap<String, Float>()
    for (entry in history) {
        if (entry.item.id !in ids) continue
        entry.item.watchedFraction(entry.positionMs, entry.durationMs)?.let { out[entry.item.id] = it }
    }
    return out
}

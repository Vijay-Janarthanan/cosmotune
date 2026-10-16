package com.cosmotune.video.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.DownloadsPlan
import com.cosmotune.video.downloads.PauseReason
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_audio_only
import com.cosmotune.video.resources.video_dl_delete
import com.cosmotune.video.resources.video_dl_done
import com.cosmotune.video.resources.video_dl_failed
import com.cosmotune.video.resources.video_dl_joining
import com.cosmotune.video.resources.video_dl_paused
import com.cosmotune.video.resources.video_dl_paused_battery
import com.cosmotune.video.resources.video_dl_running
import com.cosmotune.video.resources.video_dl_subs_got
import com.cosmotune.video.resources.video_dl_subs_none
import com.cosmotune.video.resources.video_dl_waiting
import com.cosmotune.video.resources.video_dl_waiting_wifi
import com.cosmotune.video.resources.video_meta_join
import com.cosmotune.video.resources.video_more
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.video.ui.blocks.VideoRowFrame
import com.cosmotune.video.ui.blocks.rowThumbnailSize
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * One download on the Downloads page (spec 5.10): a video row (Y-13's: the 160 x 90 thumbnail, 168 x 94.5 on the [desktop], radius
 * 16, padding 8 16, the title in 15 sp 650 up to two lines) whose words under the title are the download's state, in the meta line's
 * 12.5 sp `textSecondary`: "Waiting", "Waiting for Wi-Fi", "Paused" or "Paused: battery low", "42 percent", "Joining",
 * "720p · 312 MB · Subtitles: English", "Failed · Try again". The end of the row is the 24 dp progress ring in `accent` on `track`
 * while it runs, the 24 dp loading blob while it joins, and the 40 dp more button otherwise. A finished download carries the
 * downloaded badge on its thumbnail.
 *
 * A tap is [onClick]; the more button, a hold and a right click are [onMore] (the item's sheet). [onSwipeDelete] (the phone) lets
 * a swipe either way ask to delete it: the row springs back and the page asks first.
 */
@Composable
internal fun DownloadItemRow(
    entry: DownloadEntry,
    onClick: () -> Unit,
    onMore: () -> Unit,
    desktop: Boolean,
    modifier: Modifier = Modifier,
    onSwipeDelete: (() -> Unit)? = null,
) {
    val item = entry.item
    val title = rememberVideoTitle(item)
    val thumbnail = rememberVideoThumbnail(item)
    val state = downloadStateText(entry)
    val finished = entry.state is DownloadState.Done
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        VideoRowFrame(
            thumbnail = {
                ThumbnailFrame(
                    url = thumbnail,
                    modifier = Modifier.rowThumbnailSize(desktop),
                    shape = ThumbnailShape,
                    durationSec = item.durationSec,
                    downloaded = finished,
                )
            },
            title = title,
            meta = state,
            onClick = onClick,
            onMore = onMore,
            modifier = rowModifier,
            trailing = { DownloadStateEnd(entry.state, onMore) },
        )
    }
    if (onSwipeDelete == null) row(modifier) else SwipeToDelete(onSwipeDelete, modifier) { row(Modifier) }
}

/** The end of a download's row: the progress ring while it runs, the blob while it joins, the more button otherwise. */
@Composable
private fun RowScope.DownloadStateEnd(state: DownloadState, onMore: () -> Unit) {
    when (state) {
        is DownloadState.Running -> EndBox { DownloadRing(state.fraction) }
        DownloadState.Joining -> EndBox { CosmoLoadingBlob(Modifier.size(DownloadsMetrics.BLOB_DP.dp)) }
        else -> CosmoIconButton(
            icon = CosmoIcons.MoreVertical,
            contentDescription = stringResource(Res.string.video_more),
            onClick = onMore,
            style = CosmoIconButtonStyle.PLAIN,
            size = VideoMetrics.MORE_BUTTON_DP.dp,
        )
    }
}

/** The more button's 40 dp place, for a sign that is not a button (the row itself opens the sheet while a download is under way). */
@Composable
private fun EndBox(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(DownloadsMetrics.END_DP.dp), contentAlignment = Alignment.Center, content = content)
}

/**
 * The 24 dp progress ring (spec 5.10): the whole circle in `track`, the part done in `accent` from the top, clockwise, with round
 * ends. The accent is read while drawing (it follows a palette fade); the stroke is made once per size. It tells assistive technology
 * how far the download has come; the row's words say it too.
 */
@Composable
internal fun DownloadRing(fraction: Float, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val track = Color(colors.palette.track)
    val done = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .size(DownloadsMetrics.RING_DP.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(done, 0f..1f) }
            .drawWithCache {
                val width = DownloadsMetrics.RING_STROKE_DP.dp.toPx()
                val ring = Stroke(width)
                val arc = Stroke(width, cap = StrokeCap.Round)
                val corner = Offset(width / 2f, width / 2f)
                val box = Size(size.width - width, size.height - width)
                onDrawBehind {
                    drawArc(track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = corner, size = box, style = ring)
                    if (done > 0f) {
                        drawArc(colors.live(CoverField.ACCENT), startAngle = -90f, sweepAngle = 360f * done, useCenter = false, topLeft = corner, size = box, style = arc)
                    }
                }
            },
    )
}

/**
 * A download's state in words (spec 5.10), every text a `video_dl_*` key: Waiting; Waiting for Wi-Fi; Paused, or Paused: battery
 * low; the percentage while it runs; Joining; finished, the quality ("720p", or "Audio only"), the size and the subtitles it got
 * ("Subtitles: English", "No subtitles" when some were asked for and none were found); Failed · Try again.
 */
@Composable
internal fun downloadStateText(entry: DownloadEntry): String {
    return when (val s = entry.state) {
        DownloadState.Waiting -> stringResource(Res.string.video_dl_waiting)
        DownloadState.WaitingForWifi -> stringResource(Res.string.video_dl_waiting_wifi)
        is DownloadState.Paused -> stringResource(if (s.reason == PauseReason.BATTERY_LOW) Res.string.video_dl_paused_battery else Res.string.video_dl_paused)
        is DownloadState.Running -> stringResource(Res.string.video_dl_running, DownloadsPlan.percent(s.fraction))
        DownloadState.Joining -> stringResource(Res.string.video_dl_joining)
        is DownloadState.Done -> doneText(entry)
        is DownloadState.Failed -> stringResource(Res.string.video_meta_join, stringResource(Res.string.video_dl_failed), stringResource(Res.string.video_retry))
    }
}

/** "720p · 312 MB · Subtitles: English" (the parts from the plan, the words from the strings). */
@Composable
private fun doneText(entry: DownloadEntry): String {
    val parts = DownloadsPlan.doneParts(entry) ?: return ""
    val quality = parts.quality ?: stringResource(Res.string.video_dl_audio_only)
    val base = stringResource(Res.string.video_dl_done, quality, parts.size)
    val got = parts.subtitles
    val subtitles = when {
        got != null -> stringResource(Res.string.video_dl_subs_got, got)
        parts.missedSubtitles -> stringResource(Res.string.video_dl_subs_none)
        else -> null
    }
    return if (subtitles == null) base else stringResource(Res.string.video_meta_join, base, subtitles)
}

// ---- Swipe to delete (the phone) -----------------------------------------------------------------------------------------

/**
 * A swipe either way past [BlockMetrics.SWIPE_COMMIT] of the row's width calls [onDelete] (the page asks before anything is
 * deleted); the row always springs back, as part two's song row does with its swipe to queue. Short flings do nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(onDelete)
    var width by remember { mutableFloatStateOf(0f) }
    val holder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                val offset = runCatching { holder[0]?.requireOffset() ?: 0f }.getOrDefault(0f)
                if (width > 0f && abs(offset) >= width * BlockMetrics.SWIPE_COMMIT) current()
            }
            false // never dismissed here: the row springs back while the page asks
        },
        positionalThreshold = { distance -> distance * BlockMetrics.SWIPE_POSITIONAL_THRESHOLD },
    )
    holder[0] = state
    SwipeToDismissBox(
        state = state,
        modifier = modifier.onSizeChanged { width = it.width.toFloat() },
        backgroundContent = { DeleteReveal(state) },
    ) {
        content()
    }
}

/**
 * What the swipe uncovers: the strip the row has left, in ink at 5 % with the swipe container's radius, and in it a 72 dp block in
 * `dangerContainer` with the close mark and "Delete" in `onDangerContainer` (the danger family: this one deletes), 6 dp in from the
 * edge the row slid away from. A gesture's by-product: the sheet's Delete is the way in for assistive technology.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteReveal(state: SwipeToDismissBoxState) {
    val colors = LocalCosmoColors.current
    val label = stringResource(Res.string.video_dl_delete)
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .drawWithContent {
                val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
                if (offset == 0f) return@drawWithContent
                val left = if (offset > 0f) 0f else size.width + offset
                val right = if (offset > 0f) offset else size.width
                clipRect(left = left, top = 0f, right = right, bottom = size.height) {
                    drawRoundRect(
                        colors.live(CoverField.TEXT_PRIMARY).copy(alpha = BlockMetrics.SWIPE_CONTAINER_INK),
                        cornerRadius = CornerRadius(BlockMetrics.SWIPE_CONTAINER_RADIUS_DP.dp.toPx()),
                    )
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        DeleteBlock(label, Modifier.align(Alignment.CenterStart))
        DeleteBlock(label, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun DeleteBlock(label: String, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    val ink = Color(colors.palette.onDangerContainer)
    Column(
        modifier
            .padding(BlockMetrics.SWIPE_ACTION_INSET_DP.dp)
            .width(BlockMetrics.SWIPE_ACTION_DP.dp)
            .fillMaxHeight()
            .clip(SuperellipseShape(BlockMetrics.SWIPE_ACTION_RADIUS_DP.dp))
            .drawBehind { drawRect(colors.live(CoverField.DANGER_CONTAINER)) }
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(CosmoIcons.Close, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            color = ink,
            style = type.label.copy(fontSize = DELETE_LABEL_SP.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The swipe block's word: 11.5 sp bold, as the song row's "Queue". */
private const val DELETE_LABEL_SP = 11.5f

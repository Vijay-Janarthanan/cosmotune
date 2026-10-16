package com.cosmotune.video.ui.downloads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.downloads.DownloadsRow
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_batch_progress
import com.cosmotune.video.resources.video_dl_cancel
import com.cosmotune.video.resources.video_dl_hide_items
import com.cosmotune.video.resources.video_dl_pause
import com.cosmotune.video.resources.video_dl_resume
import com.cosmotune.video.resources.video_dl_show_items
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.rememberTitleStyle
import org.jetbrains.compose.resources.stringResource

/**
 * A batch's header on the Downloads page (spec 5.10: a playlist, a channel's latest, watch later, the feed): the batch's title
 * (15.5 sp 650, up to two lines) with a chevron, "12 of 24" (13 sp `textSecondary`), a 3 dp progress line in `accent` on `track`, and
 * at the end Pause or Resume and Cancel as 40 dp TONAL icon buttons (only while something of the batch is still to download).
 *
 * The title block is one button that collapses the batch's items or shows them again ([onToggle]); it says which it will do, and
 * the chevron turns (at once under reduced motion; the turn is read in the layer, so it recomposes nothing).
 */
@Composable
internal fun DownloadBatchHeader(
    batch: DownloadsRow.Batch,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCosmoColors.current.palette
    val reduced = LocalReducedMotion.current
    val title = batch.info.title
    val counter = stringResource(Res.string.video_dl_batch_progress, batch.done, batch.total)
    val toggleLabel = stringResource(if (batch.collapsed) Res.string.video_dl_show_items else Res.string.video_dl_hide_items)
    val titleStyle = rememberTitleStyle(DownloadsMetrics.BATCH_TITLE_SP, title)
    val counterStyle = rememberMetaStyle(DownloadsMetrics.BATCH_COUNTER_SP, counter)
    val turn = animateFloatAsState(if (batch.collapsed) 0f else HALF_TURN, CosmoMotion.move(reduced), label = "batch-chevron")
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = CosmoSpacing.margin - TOGGLE_INSET,
                end = DownloadsMetrics.HEADING_END_DP.dp,
                top = DownloadsMetrics.BATCH_PAD_TOP_DP.dp,
                bottom = DownloadsMetrics.BATCH_PAD_BOTTOM_DP.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .cosmoFocusRing(interaction, CosmoRadii.md)
                .clip(SuperellipseShape(CosmoRadii.md))
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
                .semantics(mergeDescendants = true) { stateDescription = toggleLabel }
                .padding(horizontal = TOGGLE_INSET, vertical = TOGGLE_INSET),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = Color(palette.textPrimary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    CosmoIcons.ChevronDown,
                    contentDescription = null,
                    tint = Color(palette.textSecondary),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(DownloadsMetrics.BATCH_CHEVRON_DP.dp)
                        .graphicsLayer { rotationZ = turn.value },
                )
            }
            Text(text = counter, style = counterStyle, color = Color(palette.textSecondary), maxLines = 1)
            BatchProgressLine(batch.progress, Modifier.padding(top = DownloadsMetrics.BATCH_LINE_TOP_DP.dp))
        }
        if (batch.unfinished > 0) {
            Spacer(Modifier.width(DownloadsMetrics.BATCH_BUTTON_GAP_DP.dp))
            CosmoIconButton(
                icon = if (batch.paused) CosmoIcons.Play else CosmoIcons.Pause,
                contentDescription = stringResource(if (batch.paused) Res.string.video_dl_resume else Res.string.video_dl_pause),
                onClick = if (batch.paused) onResume else onPause,
                style = CosmoIconButtonStyle.TONAL,
                size = DownloadsMetrics.BATCH_BUTTON_DP.dp,
            )
            Spacer(Modifier.width(DownloadsMetrics.BATCH_BUTTON_GAP_DP.dp))
            CosmoIconButton(
                icon = CosmoIcons.Close,
                contentDescription = stringResource(Res.string.video_dl_cancel),
                onClick = onCancel,
                style = CosmoIconButtonStyle.TONAL,
                size = DownloadsMetrics.BATCH_BUTTON_DP.dp,
            )
        }
    }
}

/**
 * The batch's 3 dp progress line (spec 5.10): the whole width in `track`, the part done in `accent` from the start edge, with round
 * ends. The accent is read while drawing; the part done is the plan's (what is done, and the part of what runs).
 */
@Composable
internal fun BatchProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val track = Color(colors.palette.track)
    val done = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(DownloadsMetrics.BATCH_LINE_DP.dp)
            .clip(RoundedCornerShape(percent = 50))
            .drawBehind {
                drawRect(track)
                val width = size.width * done
                val start = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                drawRect(colors.live(CoverField.ACCENT), topLeft = Offset(start, 0f), size = Size(width, size.height))
            },
    )
}

/** The chevron points down while the items are hidden (a tap shows them) and up, half a turn, while they show. */
private const val HALF_TURN = 180f

/** The title block's own padding: its focus ring and its press stand this far out from the text, which keeps its place. */
private val TOGGLE_INSET = 4.dp

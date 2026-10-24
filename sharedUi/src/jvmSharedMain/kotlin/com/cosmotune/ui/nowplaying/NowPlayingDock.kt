package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.SliderMetrics
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.BACK_RESTARTS_AFTER_MS
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_np_controls_expand
import com.cosmotune.music.sharedui.resources.cosmo_np_controls_minimized_state
import com.cosmotune.music.sharedui.resources.widget_next
import com.cosmotune.music.sharedui.resources.widget_previous
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The minimized controls (NP-1, the third phone of the owner's board): one slim bar floating over the video, 12 dp from the sides
 * and above the safe area (the caller's [modifier] places it and carries the drag), at least 72 dp tall, corners of 24 dp. The
 * surface is the solid glass of layer C (`cosmoGlass` with no backdrop and `overVideo`: nothing samples or blurs the video), on
 * the dark palette Now Playing has over a video in both themes.
 *
 * Inside: the cover (44 dp, radius 11), the title in the display face on one line over the artist on one line, then previous
 * (a 38 dp tonal tile), play (a 46 dp circle) and next (38 dp), the same controls the full transport draws, each with a 48 dp touch
 * target. The pill of the handle sits at the top centre; a 4 dp progress line runs along the top edge between the corners.
 *
 * A tap on the body expands the controls ([onExpand]); the buttons do what the full transport's do, a locked party included. The
 * playhead is read in the progress line's draw lambda and in one derived state (back restarts the song), never in composition.
 */
@Composable
internal fun NowPlayingDock(
    song: Song,
    coverUrl: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    controlsLocked: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onBlockedControl: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val pastRestartPoint by remember(position) { derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS } }
    val expand = stringResource(Res.string.cosmo_np_controls_expand)
    val state = stringResource(Res.string.cosmo_np_controls_minimized_state)
    val shape = remember { RoundedCornerShape(ControlsSheetRules.DOCK_RADIUS_DP.dp) }
    val titleStyle = remember(type, song.title) { type.forText(type.title(TypeScale.TITLE_M), song.title) }
    val artistStyle = remember(type, song.artist) { type.forText(TextStyle(fontFamily = type.textFamily, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight(600)), song.artist) }
    val pill = Color(palette.textPrimary).copy(alpha = ControlsSheetRules.PILL_ALPHA)

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = ControlsSheetRules.DOCK_HEIGHT_DP.dp)
            .cosmoGlass(hazeState = null, shape = shape, overVideo = true)
            // The title and the artist are merged into the bar's name; its state and the way back are said with it.
            .clickable(onClickLabel = expand, role = Role.Button, onClick = onExpand)
            .semantics {
                stateDescription = state
                customActions = listOf(CustomAccessibilityAction(expand) { onExpand(); true })
            }
            .drawBehind {
                // The playhead is read here and nowhere else in the bar: a tick redraws this line and recomposes nothing.
                val inset = ControlsSheetRules.DOCK_RADIUS_DP.dp.toPx()
                val thickness = ControlsSheetRules.DOCK_PROGRESS_DP.dp.toPx()
                val width = size.width - 2f * inset
                if (width <= 0f) return@drawBehind
                val ink = colors.live(CoverField.TEXT_PRIMARY)
                val round = CornerRadius(thickness / 2f)
                drawRoundRect(ink.copy(alpha = SliderMetrics.trackAlpha(colors.dark)), Offset(inset, 0f), Size(width, thickness), round)
                val played = if (durationMs > 0L) (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                if (played > 0f) drawRoundRect(colors.live(CoverField.ACCENT), Offset(inset, 0f), Size(width * played, thickness), round)
            },
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = ControlsSheetRules.DOCK_PILL_TOP_DP.dp)
                .size(ControlsSheetRules.PILL_WIDTH_DP.dp, ControlsSheetRules.PILL_HEIGHT_DP.dp)
                .background(pill, CircleShape),
        )
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = DOCK_START_PAD, end = DOCK_END_PAD, top = DOCK_VERTICAL_PAD, bottom = DOCK_VERTICAL_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(coverUrl, ControlsSheetRules.DOCK_THUMB_DP.dp, ControlsSheetRules.DOCK_THUMB_RADIUS_DP.dp, shadow = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = artistStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                AudioFormatLine()
            }
            Spacer(Modifier.width(4.dp))
            CosmoIconButton(
                CosmoIcons.Previous, stringResource(Res.string.widget_previous),
                onClick = { if (controlsLocked) onBlockedControl() else { haptics.play(Haptic.SkipPrevious); onPrevious() } },
                style = CosmoIconButtonStyle.TONAL, size = ControlsSheetRules.DOCK_TILE_DP.dp,
                enabled = controlsLocked || hasPrevious || pastRestartPoint,
            )
            PlayMorphButton(isPlaying, isLoading, onPlayPause, size = ControlsSheetRules.DOCK_PLAY_DP.dp)
            CosmoIconButton(
                CosmoIcons.Next, stringResource(Res.string.widget_next),
                onClick = { if (controlsLocked) onBlockedControl() else { haptics.play(Haptic.SkipNext); onNext() } },
                style = CosmoIconButtonStyle.TONAL, size = ControlsSheetRules.DOCK_TILE_DP.dp,
                enabled = controlsLocked || hasNext,
            )
        }
    }
}

/** The cover's inset from the left, (72 - 44) / 2, so it sits square in the bar; the right end is closed by the next tile's padding. */
private val DOCK_START_PAD = 14.dp
private val DOCK_END_PAD = 4.dp

/** Air above and below the row when the font is large enough to make the bar grow past 72 dp. */
private val DOCK_VERTICAL_PAD = 8.dp

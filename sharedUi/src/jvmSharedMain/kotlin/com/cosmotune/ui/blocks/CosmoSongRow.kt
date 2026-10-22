package com.cosmotune.ui.blocks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.BlockMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.play_next
import com.cosmotune.music.sharedui.resources.queue
import com.cosmotune.music.sharedui.resources.selected as selectedString
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.components.DownloadedBadge
import com.cosmotune.music.ui.components.ExplicitSongTitle
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.player.rememberRemoteArtworkUrl
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource

/**
 * A row for a song, in the B+C look (`SongRow`'s delegate). The cover (or [trackNumber] in an album's list), the title with its
 * explicit mark, the artist, and at the end [trailingText] (the duration when it is null), the downloaded mark and a more
 * button. A swipe queues the song or plays it next, as the old row did: the same thresholds, the same haptics, the row springs
 * back; the reveal under it is the B one, a container with a tonal block.
 *
 * [downloadedTint] only says whether to mark a downloaded song (null: no mark, the Downloads page); the mark is in the accent
 * of the cover. [selected] is the multi-selection of Downloads: a check disc replaces the cover. [isCurrent] washes the row and
 * [isPlaying] sets the equaliser going.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CosmoSongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
    trackNumber: Int? = null,
    downloadedTint: Color? = Color.Unspecified,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    selected: Boolean = false,
    height: Dp = 64.dp,
    trailingText: String? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    // A Spotify track still being matched to a YouTube Music song, or one that has no match: shown, but not something a menu can act on.
    val matching = song.isMatchPending
    val unavailable = song.isMatchMissing
    val moreAction = onMore ?: onLongPress
    val showMore = moreAction != null && !matching && !unavailable
    // The duration stands at the end unless the caller says something else there (an empty text says nothing).
    val endText = if (trailingText != null) trailingText.takeIf { it.isNotBlank() } else song.durationText
    val subtitle = remember(song.artist, song.downloadFormat) {
        listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.downloadFormat).joinToString(" · ")
    }
    val cover = rememberRemoteArtworkUrl(song)
    val leading = if (trackNumber != null) CosmoRowLeading.Number(trackNumber) else CosmoRowLeading.Cover(cover)
    val titleStyle = type.forText(type.rowTitleStyle(), song.title)
    val rowModifier = modifier
        .then(if (unavailable) Modifier.alpha(UNAVAILABLE_ALPHA) else Modifier)
        .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
    val trailing: (@Composable RowScope.() -> Unit)? =
        if (matching || endText != null || downloadedTint != null || showMore) {
            {
                if (matching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(palette.textSecondary))
                } else if (endText != null) {
                    Text(
                        text = endText,
                        style = type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal).tabular(),
                        color = Color(palette.textTertiary),
                        maxLines = 1,
                    )
                }
                if (downloadedTint != null) DownloadedBadge(song.videoId, Color(palette.accent))
                if (showMore) {
                    CosmoIconButton(
                        icon = CosmoIcons.MoreVertical,
                        contentDescription = stringResource(Res.string.more),
                        onClick = moreAction,
                        style = CosmoIconButtonStyle.PLAIN,
                        size = 40.dp,
                    )
                }
            }
        } else null

    @Composable
    fun Frame(m: Modifier) {
        CosmoRowFrame(
            title = song.title,
            onClick = onClick,
            modifier = m,
            subtitle = subtitle,
            why = null,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            washed = isCurrent || selected,
            hasLeading = true,
            numbered = trackNumber != null,
            onLongPress = onLongPress,
            // Holding a Downloads row selects it, so a right click has to keep opening the actions: the more button's action.
            onSecondaryClick = moreAction,
            height = height,
            titleContent = {
                ExplicitSongTitle(song = song, style = titleStyle, color = rowTitleColor(colors, isCurrent))
            },
            leadingContent = {
                if (selected) SelectedDisc(slotWidth = if (trackNumber != null) 24.dp else BlockMetrics.ROW_COVER_DP.dp)
                else RowLeadingContent(leading, isCurrent, isPlaying)
            },
            trailing = trailing,
        )
    }

    val swipeStateHolder = remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    var boxWidth by remember { mutableFloatStateOf(0f) }
    val currentOnSwipeToQueue by rememberUpdatedState(onSwipeToQueue)
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && currentOnSwipeToQueue != null) {
                val offset = try { swipeStateHolder.value?.requireOffset() ?: 0f } catch (e: Exception) { 0f }
                // Only queue if the physical drag reached the commit line, ignoring short accidental flings.
                if (abs(offset) >= boxWidth * BlockMetrics.SWIPE_COMMIT) {
                    haptics.play(Haptic.Select)
                    currentOnSwipeToQueue?.invoke()
                }
            }
            false // never dismiss: the row springs back
        },
        positionalThreshold = { distance -> distance * BlockMetrics.SWIPE_POSITIONAL_THRESHOLD },
    )
    swipeStateHolder.value = swipeState

    if (onSwipeToQueue == null) {
        Frame(rowModifier)
        return
    }

    // The reveal shows from the first pixel of the drag but the row only queues past the commit line, so one light tick at the
    // crossing tells the finger that letting go now will queue. Dragging back under 0.8 of the line re-arms it silently, and so
    // does the spring-back after a queue, which must not buzz the same gesture twice.
    LaunchedEffect(swipeState, boxWidth) {
        if (boxWidth <= 0f) return@LaunchedEffect
        val armAt = boxWidth * BlockMetrics.SWIPE_COMMIT
        var armed = false
        snapshotFlow { try { swipeState.requireOffset() } catch (e: Exception) { 0f } }
            .collect { offset ->
                val travelled = abs(offset)
                when {
                    !armed && travelled >= armAt -> {
                        armed = true
                        haptics.play(Haptic.Tick)
                    }
                    armed && travelled < armAt * BlockMetrics.SWIPE_REARM -> armed = false
                }
            }
    }

    SwipeToDismissBox(
        state = swipeState,
        modifier = rowModifier.onSizeChanged { boxWidth = it.width.toFloat() },
        backgroundContent = { SwipeReveal(swipeState) },
    ) {
        Frame(Modifier)
    }
}

/** A song that has no match yet is drawn at 45 % (the old row's value). */
private const val UNAVAILABLE_ALPHA = 0.45f

/** `selected`: a 24 dp disc in the accent with a check, centred in the width the cover or the number had. */
@Composable
private fun SelectedDisc(slotWidth: Dp) {
    val colors = LocalCosmoColors.current
    Box(Modifier.width(slotWidth), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(colors.live(CoverField.ACCENT)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                CosmoIcons.Check,
                contentDescription = stringResource(Res.string.selectedString),
                tint = Color(colors.palette.onAccent),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * What the swipe uncovers (`.swipe`, B): a container of radius 20 in ink at 5 % under the row, and in it a 72 dp block of radius 16
 * in the tonal fill with the queue icon and the word "Queue" ("Play next" when the setting says so), 6 dp in from the edge the row
 * slid away from. Only the strip the row has left is drawn, so the page shows through the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeReveal(swipeState: SwipeToDismissBoxState) {
    val playNext by AppUi.host.swipeToPlayNext.collectAsStateWithLifecycle()
    val colors = LocalCosmoColors.current
    val label = stringResource(if (playNext) Res.string.play_next else Res.string.queue)
    Box(
        Modifier
            .fillMaxSize()
            // The reveal is a gesture's by-product; the more button is the way in for assistive technology.
            .clearAndSetSemantics { }
            .drawWithContent {
                val offset = try { swipeState.requireOffset() } catch (e: Exception) { 0f }
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
        SwipeAction(label, Modifier.align(Alignment.CenterStart))
        SwipeAction(label, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SwipeAction(label: String, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    val ink = Color(colors.palette.onAccentContainer)
    Column(
        modifier
            .padding(BlockMetrics.SWIPE_ACTION_INSET_DP.dp)
            .width(BlockMetrics.SWIPE_ACTION_DP.dp)
            .fillMaxHeight()
            .clip(SuperellipseShape(BlockMetrics.SWIPE_ACTION_RADIUS_DP.dp))
            .drawBehind { drawRect(tonalFill(colors)) }
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(CosmoIcons.Queue, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

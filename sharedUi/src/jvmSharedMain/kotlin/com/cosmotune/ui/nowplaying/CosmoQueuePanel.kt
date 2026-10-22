package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.InlineQueue

/** How much of the palette's scrim fills the queue's card: the same in both themes (the panels that were kept draw white, D8). */
internal const val QUEUE_SCRIM_ALPHA = 0.9f

/**
 * The queue, on a dark card: [SuperellipseShape] of 28 dp filled with the palette's `scrim` at [QUEUE_SCRIM_ALPHA] in the light
 * theme as in the dark one. It takes the room the page gives it, between the top bar and the transport, and over a video the
 * picture still shows faintly through it.
 *
 * Inside is the app's own [InlineQueue], which draws white text and has its own heading, "Clear" and drag handles: reorder,
 * remove and jump work as they always did, and a party whose host holds the controls ([controlsLocked]) can only look.
 * The scrim is read in the draw phase, so a fade of the palette repaints the card and recomposes nothing.
 */
@Composable
internal fun CosmoQueuePanel(
    queue: List<Song>,
    queueIndex: Int,
    autoplayEnabled: Boolean,
    controlsLocked: Boolean,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onQueueDragActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCosmoColors.current
    Box(
        modifier
            .clip(SuperellipseShape(CosmoRadii.xl))
            .drawBehind { drawRect(colors.live(CoverField.SCRIM).copy(alpha = QUEUE_SCRIM_ALPHA)) },
    ) {
        InlineQueue(
            queue = queue,
            currentIndex = queueIndex,
            autoplayEnabled = autoplayEnabled,
            controlsLocked = controlsLocked,
            onJumpTo = onJumpTo,
            onRemove = onRemoveFromQueue,
            onMove = onMoveInQueue,
            onClear = onClearQueue,
            onDragActiveChange = onQueueDragActiveChange,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

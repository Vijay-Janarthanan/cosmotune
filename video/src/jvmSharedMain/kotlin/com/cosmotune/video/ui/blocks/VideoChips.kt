package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoChip

/** The chip row's padding: 6 above, 16 at the sides, 4 below (spec 5.0: "padding 6 16 4"). */
private val CHIPS_START = 16.dp
private val CHIPS_TOP = 6.dp
private val CHIPS_END = 16.dp
private val CHIPS_BOTTOM = 4.dp

/** The gap between chips (spec 5.0: "gap 8"). */
private val CHIPS_GAP = 8.dp

/**
 * A row of choices that scrolls sideways (spec 5.0: kiosks, filters, channel tabs): one [CosmoChip] (36 dp) per label, 8 dp apart,
 * in padding 6 16 4, the [selected]th one chosen (-1: none). A tap calls [onSelect] with the chip's index; the chip that is already
 * chosen plays no haptic (`haptic = !selected`, as part two's Search filters do). [leading] stands first in the same row, before the
 * chips (Home's Shorts chip, which has no place in the kiosk list), with the same gap.
 */
@Composable
fun VideoChips(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = CHIPS_START, top = CHIPS_TOP, end = CHIPS_END, bottom = CHIPS_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(CHIPS_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        labels.forEachIndexed { index, label ->
            CosmoChip(label = label, selected = index == selected, onClick = { onSelect(index) }, haptic = index != selected)
        }
    }
}

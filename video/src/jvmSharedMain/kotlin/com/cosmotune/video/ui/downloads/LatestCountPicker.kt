package com.cosmotune.video.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoChip
import com.cosmotune.ui.sheets.CosmoSheetHeading
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_latest_count
import org.jetbrains.compose.resources.stringResource

/** The chips start where the sheet's row text starts (8 dp row inset + 14 dp inside) and keep 8 dp between them (spec 5.0: chips 36 dp, gap 8). */
private val CHIPS_START = 22.dp
private val CHIPS_END = 16.dp
private val CHIPS_GAP = 8.dp
private val CHIPS_BOTTOM = 8.dp

/**
 * How many of a channel's newest uploads "Download the latest" queues (spec 5.9, 5.16): the overline "How many of the newest" and the
 * chips 5, 10, 25 and 50 ([LatestCount.CHOICES]; 10 by default), one of them chosen. The C chips of part two (glass pills that turn
 * solid ink when chosen), since the sheet floats. [onSelect] gets the count; fewer loaded uploads than the count queue all of them.
 */
@Composable
internal fun LatestCountPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        CosmoSheetHeading(stringResource(Res.string.video_dl_latest_count))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = CHIPS_START, end = CHIPS_END, bottom = CHIPS_BOTTOM)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(CHIPS_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (count in LatestCount.CHOICES) {
                key(count) {
                    CosmoChip(label = count.toString(), selected = count == selected, onClick = { onSelect(count) }, floating = true, haptic = count != selected)
                }
            }
        }
    }
}

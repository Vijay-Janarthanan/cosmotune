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
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.theme.CosmoSpacing

/**
 * A row of choices that scrolls sideways and never wraps (DESIGN_SYSTEM.md 4.4: kiosks, filters, channel tabs): one [KitChip] per label, 8 dp
 * apart, the page's margin at both ends, the [selected]th one chosen (-1: none). A tap calls [onSelect] with the chip's index. [leading]
 * stands first in the same row, before the chips, and [trailing] after them (Home's placeholders while the kiosks are on the way), with
 * the same gap.
 */
@Composable
fun VideoChips(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = CosmoSpacing.margin),
        horizontalArrangement = Arrangement.spacedBy(CHIPS_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        labels.forEachIndexed { index, label ->
            KitChip(label = label, selected = index == selected, onClick = { onSelect(index) })
        }
        trailing?.invoke()
    }
}

/** The gap between chips. */
private val CHIPS_GAP = 8.dp

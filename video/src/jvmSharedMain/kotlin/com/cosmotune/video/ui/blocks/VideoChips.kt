package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing

/**
 * A row of choices that scrolls sideways and never wraps (Orbit rule 7: kiosks, filters, channel tabs): one 40 dp [OrbitSelectChip]
 * per label, 8 dp apart, the page's margin at both ends inside the scroll, the [selected]th one solid ink (-1: none). A tap calls
 * [onSelect] with the chip's index. [leading] stands first in the same row, before the chips, and [trailing] after them, with the
 * same gap.
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
    val fonts = orbitFonts()
    val look = orbitLook()
    OrbitChipRow(CosmoSpacing.margin, modifier) {
        leading?.invoke()
        labels.forEachIndexed { index, label ->
            OrbitSelectChip(label, selected = index == selected, fonts = fonts, look = look, onClick = { onSelect(index) })
        }
        trailing?.invoke()
    }
}

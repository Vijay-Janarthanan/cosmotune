package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.player.RepeatModes
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.sleep.SleepTimerChip

/**
 * What is switched on right now, as the chip row shows it: a feature in force
 * is a solid chip beside the panel chips, never a state hidden in a menu.
 */
@Immutable
data class NowPlayingActive(
    val shuffle: Boolean = false,
    val repeatMode: Int = RepeatModes.OFF,
    val crossfade: Boolean = false,
    val automix: Boolean = false,
    val equaliser: Boolean = false,
    /** The receiver's name while casting, else null. */
    val castingTo: String? = null,
    val inRoom: Boolean = false,
)

/** One active-feature chip: what it says, its icon, what a tap on it does. */
data class ActiveChip(val title: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The active features in the order the row shows them, after the sleep timer.
 * With [transportOnCard] (the wide page, whose song card has its own lit
 * shuffle and repeat buttons) those two are left to the card.
 */
fun activeChips(
    active: NowPlayingActive,
    transportOnCard: Boolean,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenCast: () -> Unit,
    onOpenRoom: () -> Unit,
): List<ActiveChip> = buildList {
    if (!transportOnCard) {
        when (active.repeatMode) {
            RepeatModes.ONE -> add(ActiveChip("Repeat one", CosmoIcons.RepeatOne, onCycleRepeat))
            RepeatModes.OFF -> Unit
            else -> add(ActiveChip("Repeat all", CosmoIcons.Repeat, onCycleRepeat))
        }
        if (active.shuffle) add(ActiveChip("Shuffle", CosmoIcons.Shuffle, onToggleShuffle))
    }
    when {
        active.automix -> add(ActiveChip("Automix", CosmoIcons.Waveform, onOpenDetails))
        active.crossfade -> add(ActiveChip("Crossfade", CosmoIcons.Waveform, onOpenDetails))
    }
    if (active.equaliser) add(ActiveChip("Equaliser", CosmoIcons.Equaliser, onOpenDetails))
    active.castingTo?.let { add(ActiveChip(it, CosmoIcons.Devices, onOpenCast)) }
    if (active.inRoom) add(ActiveChip("In a room", CosmoIcons.Together, onOpenRoom))
}

/** The sleep timer's pill, then [chips], each solid. Laid out by the row it is put in. */
@Composable
fun NowPlayingStatusChips(chips: List<ActiveChip>, fonts: OrbitFonts, look: OrbitLook) {
    SleepTimerChip(fonts, look)
    for (chip in chips) {
        OrbitChip(
            chip.title, fonts, look, onClick = chip.onClick, selected = true,
            leading = { Icon(chip.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

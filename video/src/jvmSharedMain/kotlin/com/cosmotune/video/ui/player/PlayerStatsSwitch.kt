package com.cosmotune.video.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the "Stats for nerds" card shows. One switch for the whole session, not one per player box: the page's player, its full
 * window and the page that stands in for the player while the picture-in-picture window holds the picture all read it, so turning
 * the stats on in one shows them in the others (and they are still on when the picture comes back).
 */
internal object PlayerStatsSwitch {
    var shown: Boolean by mutableStateOf(false)
}

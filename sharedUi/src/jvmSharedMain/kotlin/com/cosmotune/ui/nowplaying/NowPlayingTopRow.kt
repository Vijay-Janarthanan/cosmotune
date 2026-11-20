package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.shell.LocalCosmoPlayerDismiss

/** Which panel the chips show: the lyrics, Up next or Related; [NONE] only on the phone, where a chip can be closed again. */
internal enum class NowPlayingPanel { NONE, LYRICS, UP_NEXT, RELATED }

/**
 * The top row (board sections 1, 3 and 5): the round chevron that closes the player, the mono "FROM · <source>" (a tap goes back
 * to where the music came from), and, with [chips], the Lyrics / Up next / Related chips and the "more" round button holding the
 * rarer actions ([more]). Without [chips] (the phone, whose chips sit at its foot) the row is the chevron, the source and more.
 */
@Composable
internal fun NowPlayingTopRow(
    source: String,
    onOpenOrigin: () -> Unit,
    panel: NowPlayingPanel,
    onPanel: (NowPlayingPanel) -> Unit,
    more: List<OrbitMenuItem>,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    chips: Boolean = true,
    /** What is switched on (the sleep timer, Automix, casting...), as solid chips before the panel chips; the wide page's. */
    status: @Composable () -> Unit = {},
    /** A menu opened from a "more" row (the sleep timer's), anchored where "more" is. */
    moreOverlay: @Composable () -> Unit = {},
    /** The phone's end of the row (the board, B): the screen button in place of More, which the phone's card carries. */
    screen: (@Composable () -> Unit)? = null,
) {
    val dismiss = LocalCosmoPlayerDismiss.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitRoundButton(CosmoIcons.ChevronDown, "Close the player", look.ink, dismiss)
        // While a dropped connection is being waited out, the row says so instead of where the
        // music comes from; the play button spins meanwhile (see CosmoNowPlaying).
        val reconnecting by com.cosmotune.music.playback.recovery.PlaybackReconnectStatus.reconnecting
            .collectAsState()
        Text(
            if (reconnecting != null) com.cosmotune.music.playback.recovery.PlaybackReconnectStatus.LABEL
            else "FROM · ${source.uppercase()}", style = fonts.label, color = look.ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpenOrigin).padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { status() }
        if (chips) PanelChips(panel, onPanel, fonts, look, closable = false)
        if (screen != null) screen() else MoreButton(more, fonts, look, look.ink, look.ground, moreOverlay)
    }
}

/**
 * The round "more" button and its Orbit menu of the rarer actions ([more]), in [ink] (solid in it while open, the glyph in
 * [onInk]); [overlay] is a menu one of its lines opens (the sleep timer's), anchored where it is. The wide page's top row and the
 * phone's song card (beside Like) hold it.
 */
@Composable
internal fun MoreButton(more: List<OrbitMenuItem>, fonts: OrbitFonts, look: OrbitLook, ink: androidx.compose.ui.graphics.Color, onInk: androidx.compose.ui.graphics.Color,
    overlay: @Composable () -> Unit = {}, size: androidx.compose.ui.unit.Dp = 40.dp) {
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    Box(Modifier.testTag("nowplaying:more")) {
        OrbitRoundButton(CosmoIcons.More, "More", ink, { open = true }, on = open, onInk = onInk, size = size)
        OrbitMenu(open, { open = false }, more, fonts, look, header = "More")
        overlay()
    }
}

/**
 * Lyrics, Up next and Related as Home's chips, the one showing solid. With [closable] (the phone) a tap on the one showing closes
 * it; otherwise one is always showing.
 */
@Composable
internal fun PanelChips(
    panel: NowPlayingPanel, onPanel: (NowPlayingPanel) -> Unit, fonts: OrbitFonts, look: OrbitLook, closable: Boolean, modifier: Modifier = Modifier,
    icons: Boolean = true,
    /** How many songs are up next, shown as a small peach badge on its chip (the wide page's tabs); null for none. */
    upNextCount: Int? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((which, title, icon) in listOf(
            Triple(NowPlayingPanel.LYRICS, "Lyrics", CosmoIcons.Lyrics),
            Triple(NowPlayingPanel.UP_NEXT, "Up next", CosmoIcons.Queue),
            Triple(NowPlayingPanel.RELATED, "Related", CosmoIcons.Explore),
        )) {
            val on = panel == which
            OrbitChip(
                title, fonts, look, onClick = { onPanel(if (on && closable) NowPlayingPanel.NONE else which) }, selected = on,
                leading = if (icons) ({ Icon(icon, null, modifier = Modifier.size(18.dp)) }) else null,
                trailing = if (which == NowPlayingPanel.UP_NEXT && upNextCount != null && upNextCount > 0) ({ CountBadge(upNextCount, fonts, look) }) else null,
            )
        }
    }
}

/** A count on a chip: the number in a small peach badge. */
@Composable
private fun CountBadge(count: Int, fonts: OrbitFonts, look: OrbitLook) {
    Text(
        if (count > 99) "99+" else count.toString(), style = fonts.label.copy(letterSpacing = 0.sp), color = androidx.compose.ui.graphics.Color(0xFF2A100A), maxLines = 1,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(look.accent).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
) {
    val dismiss = LocalCosmoPlayerDismiss.current
    var moreOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitRoundButton(CosmoIcons.ChevronDown, "Close the player", look.ink, dismiss)
        Text(
            "FROM · ${source.uppercase()}", style = fonts.label, color = look.ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpenOrigin).padding(vertical = 10.dp, horizontal = 4.dp),
        )
        if (chips) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { status() }
            PanelChips(panel, onPanel, fonts, look, closable = false)
        }
        Box {
            OrbitRoundButton(CosmoIcons.More, "More", look.ink, { moreOpen = true }, on = moreOpen, onInk = look.ground)
            OrbitMenu(moreOpen, { moreOpen = false }, more, fonts, look, header = "More")
            moreOverlay()
        }
    }
}

/**
 * Lyrics, Up next and Related as Home's chips, the one showing solid. With [closable] (the phone) a tap on the one showing closes
 * it; otherwise one is always showing.
 */
@Composable
internal fun PanelChips(panel: NowPlayingPanel, onPanel: (NowPlayingPanel) -> Unit, fonts: OrbitFonts, look: OrbitLook, closable: Boolean, modifier: Modifier = Modifier, icons: Boolean = true) {
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
            )
        }
    }
}

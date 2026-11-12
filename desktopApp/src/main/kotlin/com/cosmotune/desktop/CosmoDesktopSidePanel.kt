package com.cosmotune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.music.desktop.DesktopStrings
import com.cosmotune.music.ui.player.LocalSidePanelPlaying
import com.cosmotune.ui.orbit.ORBIT_SIDE_PANEL_SHAPE
import com.cosmotune.ui.orbit.OrbitSidePanelHeader
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitSidePanelFill

/**
 * The card of the side panel in Orbit: the lyrics or Up next stand in one flat panel beside the page instead of in a column cut off
 * by a rule. As a modifier chain, from the outside in: the margin (14 dp above and at the end, and the player bar's footprint below,
 * so the card ends clear of the bar), then the panel's corners of 28 and its flat fill ([orbitSidePanelFill]: the row's ink over the
 * page's ground). No glass and no blur (Orbit rule 4). The card has no padding of its own: the head ([CosmoDesktopSidePanelHeader])
 * and the content's inset ([cosmoDesktopSidePanelInset]) carry it. The column's width (360 dp) and its slide are upstream's,
 * untouched. It is a function and not an extension of `Modifier` because the one upstream line that takes it does so by its full
 * name, without an import.
 *
 * While the mode is Video ([com.cosmotune.video.host.desktopVideoShown]) the music side panel stands aside: the modifier measures
 * the column as usual but places nothing, so neither the card nor the lyrics or the queue inside it are drawn or answer a click.
 * The column keeps its width: it is set by an upstream modifier in front of this one, which a modifier behind it cannot undo.
 */
@Composable
internal fun cosmoDesktopSidePanelCard(): Modifier {
    if (com.cosmotune.video.host.desktopVideoShown() || com.cosmotune.video.host.desktopMangaShown() || com.cosmotune.video.host.desktopWatchShown()) return Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {}
    }
    val fill = orbitSidePanelFill(orbitLook())
    return Modifier
        .padding(top = PANEL_MARGIN, end = PANEL_MARGIN, bottom = ShellMetrics.desktopBarFootprintDp().dp)
        .clip(ORBIT_SIDE_PANEL_SHAPE)
        .background(fill)
}

/**
 * The head of the panel in Orbit: "Lyrics" or "Up next" in the wide section face and the round close button ([onClose]). The queue
 * is headed here too: Up next's groups follow under it.
 */
@Composable
internal fun CosmoDesktopSidePanelHeader(lyrics: Boolean, onClose: () -> Unit) {
    OrbitSidePanelHeader(
        title = if (lyrics) DesktopStrings["lyrics", "Lyrics"] else DesktopStrings["up_next", "Up next"],
        closeLabel = DesktopStrings["close", "Close"],
        onClose = onClose,
    )
}

/**
 * The content's inset inside the card: 20 dp at the sides for the lyrics (the head's own), 12 dp for Up next's rows (whose cards
 * carry their own 10 dp in), and 14 dp above the card's end.
 */
@Composable
internal fun cosmoDesktopSidePanelInset(lyrics: Boolean): Modifier {
    return Modifier.padding(start = if (lyrics) 20.dp else 12.dp, end = if (lyrics) 20.dp else 12.dp, bottom = 14.dp)
}

/**
 * What the panel holds, told whether the song plays ([LocalCosmoPlaying], which the window provides): Up next's playing row circles
 * its moon while it does, as Now Playing's. The panel's own call of the queue (in `DesktopApp.kt`) does not pass it.
 */
@Composable
internal fun CosmoDesktopSidePanelContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSidePanelPlaying provides LocalCosmoPlaying.current, content = content)
}

/**
 * The card as a thing of its own: [content] on the card while the panel is [open], nothing while it is not (and nothing while the
 * mode is Video). It fills what its parent gives it.
 */
@Composable
internal fun CosmoDesktopSidePanel(open: Boolean, content: @Composable () -> Unit) {
    if (!open || com.cosmotune.video.host.desktopVideoShown() || com.cosmotune.video.host.desktopMangaShown() || com.cosmotune.video.host.desktopWatchShown()) return
    Box(Modifier.fillMaxSize().then(cosmoDesktopSidePanelCard())) { content() }
}

private val PANEL_MARGIN = 14.dp

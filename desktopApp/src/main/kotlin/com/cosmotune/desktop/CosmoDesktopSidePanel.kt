package com.cosmotune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoShell
import com.music.bitchord.desktop.LocalDesktopHaze

/**
 * The card of the side panel (`.d2-panel`, `V3.deskPanel`): the lyrics or the queue stand in a glass card beside the page
 * instead of in a column cut off by a rule. As a modifier chain, from the outside in: the margin (14 dp above and at the end,
 * and the player bar's footprint below, so the card ends clear of the bar), then glass over the window's haze in a superellipse of
 * [CosmoRadii.xl]. The card has no padding of its own: the column's header and content already stand 14 to 18 dp in (the queue's
 * content 14, the lyrics' 18), which is the board's 14 dp; a card padding on top of them put the content 28 to 32 dp from the
 * edge. The column's width (360 dp), its slide and its close button are upstream's, untouched: this is only what its contents are
 * drawn on. It is a function and not an extension of `Modifier`
 * because the one upstream line that takes it does so by its full name, without an import.
 *
 * With the new look off the modifier is the identity, so `DesktopSidePanelColumn` looks exactly as it did.
 */
@Composable
internal fun cosmoDesktopSidePanelCard(): Modifier {
    if (LocalCosmoShell.current == null) return Modifier
    val shape = remember { SuperellipseShape(CosmoRadii.xl) }
    return Modifier
        .padding(top = PANEL_MARGIN, end = PANEL_MARGIN, bottom = ShellMetrics.desktopBarFootprintDp().dp)
        .cosmoGlass(LocalDesktopHaze.current, shape)
}

/** The card as a thing of its own: [content] on the card while the panel is [open], nothing while it is not. It fills what its parent gives it. */
@Composable
internal fun CosmoDesktopSidePanel(open: Boolean, content: @Composable () -> Unit) {
    if (!open) return
    Box(Modifier.fillMaxSize().then(cosmoDesktopSidePanelCard())) { content() }
}

private val PANEL_MARGIN = 14.dp

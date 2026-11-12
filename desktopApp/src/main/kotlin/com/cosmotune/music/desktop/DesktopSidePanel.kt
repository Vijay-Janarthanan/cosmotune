package com.cosmotune.music.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What the column beside the page is showing. */
internal enum class DesktopSidePanel { LYRICS, QUEUE }

/**
 * Apple Music's right-hand sidebar: the lyrics or the queue in a column beside the page, while the
 * player itself stays closed. The page narrows to make room rather than being covered, so whatever
 * was being browsed stays usable next to the words.
 */
@Composable
internal fun DesktopSidePanelColumn(
    panel: DesktopSidePanel?,
    onClose: () -> Unit,
    content: @Composable (DesktopSidePanel) -> Unit,
) {
    // Held through the exit, so the column slides out with what it had in it rather than empty.
    var shown by remember { mutableStateOf(panel) }
    if (panel != null) shown = panel
    AnimatedVisibility(
        visible = panel != null,
        enter = expandHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Start) +
            fadeIn(tween(SLIDE_MS)),
        exit = shrinkHorizontally(tween(SLIDE_MS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start) +
            fadeOut(tween(SLIDE_MS / 2)),
    ) {
        Row(Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight().then(com.cosmotune.desktop.cosmoDesktopSidePanelCard())) {
            Crossfade(
                targetState = shown ?: return@Row,
                animationSpec = tween(CROSSFADE_MS),
                label = "sidePanel",
            ) { which ->
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        com.cosmotune.desktop.CosmoDesktopSidePanelHeader(which == DesktopSidePanel.LYRICS, onClose)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .then(com.cosmotune.desktop.cosmoDesktopSidePanelInset(which == DesktopSidePanel.LYRICS)),
                        ) {
                            com.cosmotune.desktop.CosmoDesktopSidePanelContent { content(which) }
                        }
                    }
                }
            }
        }
    }
}

private val SIDE_PANEL_WIDTH = 360.dp
private const val SLIDE_MS = 260
private const val CROSSFADE_MS = 180

package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp

/**
 * The player for a window: the shared overlay that grows out of the player bar ([com.cosmotune.ui.shell.CosmoPlayerOverlay]) and goes
 * back the same ways the phone's does, or by Escape (which the window routes through [com.cosmotune.music.ui.player.PlayerBack] first,
 * so the lyrics or the queue close before the player does). The wide Now Playing has the same bar inside it, whose volume bar is named
 * in the desktop's own words. Nothing is drawn outside a CosmoLookTheme (no shell).
 */
@Composable
internal fun DesktopPlayerSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable BoxScope.(windowWidth: Dp, windowHeight: Dp) -> Unit,
) {
    val shell = com.cosmotune.ui.theme.LocalCosmoShell.current ?: return
    com.cosmotune.ui.shell.CosmoPlayerOverlay(visible, onDismiss, shell) { w, h ->
        CompositionLocalProvider(
            com.cosmotune.ui.nowplaying.LocalVisualsPresentation provides { dismiss, body -> DesktopVisualsPresentation(dismiss, body) },
            com.cosmotune.ui.shell.LocalPlayerVolumeLabel provides DesktopStrings["d_volume", "Volume"],
        ) { content(w, h) }
    }
}

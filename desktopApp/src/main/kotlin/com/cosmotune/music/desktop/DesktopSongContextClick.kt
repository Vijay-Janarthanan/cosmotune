package com.cosmotune.music.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.components.contextClick

/** Native-owned rows use the same pointer-anchored song menu as the shared pages. */
internal val LocalDesktopSongContextMenu = compositionLocalOf<((Song) -> Unit)?> { null }

@Composable
internal fun Modifier.desktopSongContextMenu(song: Song, enabled: Boolean = true): Modifier {
    val open = LocalDesktopSongContextMenu.current
    if (!enabled || open == null) return this
    val latestSong by rememberUpdatedState(song)
    val latestOpen by rememberUpdatedState(open)
    val action = remember { { latestOpen(latestSong) } }
    // contextClick handles only the secondary button and consumes it; the row's primary click still plays.
    return contextClick(action)
}

@Composable
internal fun rememberDesktopSongAction(action: (Song) -> Unit): (Song) -> Unit {
    val latest by rememberUpdatedState(action)
    return remember { { song: Song -> latest(song) } }
}

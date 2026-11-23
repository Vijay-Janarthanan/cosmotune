package com.cosmotune.music.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/** The root's names for Video's tray bridge, so the root itself names Video only on its hook-table lines. */
internal val DesktopTrayVideo = com.cosmotune.video.host.DesktopVideoTray

@Composable
internal fun rememberDesktopTrayVideo(musicPlaying: Boolean): State<com.cosmotune.video.host.DesktopVideoNow?> =
    com.cosmotune.video.host.rememberDesktopVideoNow(musicPlaying)

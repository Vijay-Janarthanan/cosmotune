package com.cosmotune.video.ui.player

import androidx.compose.runtime.Composable
import com.cosmotune.video.player.ExternalStream

/** Opens a stream in another player on this device. */
interface ExternalPlayer {
    /** Opens [stream] titled [title]; false when no player took it. */
    fun open(stream: ExternalStream, title: String): Boolean
}

@Composable
internal expect fun rememberExternalPlayer(): ExternalPlayer

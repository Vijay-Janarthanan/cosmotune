package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol

/** Another player on this device that can open a copy (VLC, mpv, MX Player, or the system's choice). */
internal class ExternalPlayer(val name: String, val open: (Stream, String) -> Unit)

/**
 * The other players this device offers for [stream] (desktop: VLC and mpv when installed, then the system's default; phone and
 * TV: the system's chooser of video apps, with the copy's headers and title passed the way MX Player and VLC read them).
 */
@Composable internal expect fun rememberExternalPlayers(): List<ExternalPlayer>

/** Why [stream] cannot open in another player, in the words the menu shows; null when it can. */
internal fun externalBlock(stream: Stream?): String? = when {
    stream == null -> "Nothing is playing yet."
    stream.isTorrent -> "Torrents play only in Cosmotune's own player."
    stream.protocol == StreamProtocol.YOUTUBE -> "YouTube copies play in Cosmotune's own player."
    stream.protocol == StreamProtocol.EXTERNAL -> "This copy opens on its own site."
    !stream.url.startsWith("http://") && !stream.url.startsWith("https://") && !stream.url.startsWith("file:") -> "This copy can't be opened elsewhere."
    else -> null
}

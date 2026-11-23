package com.cosmotune.video.ui.player

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.cast.dlna.DlnaState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.video.cast.VideoCasts
import com.cosmotune.video.player.VideoPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * "Play on a TV" in the player's settings (phone sheet, desktop menu, full screen): the TVs on this network, then for the
 * one chosen play or pause, 10 seconds back or on, its volume and "Stop playing on TV". The picture is the best copy with
 * sound inside; the page says which quality the TV gets.
 */
@Composable
internal fun PlayOnTvRows(playback: VideoPlayback, menu: Boolean, back: () -> Unit, done: () -> Unit) {
    val casting = remember(playback) { VideoCasts.of(playback) }
    val devices by casting.discovery.devices.collectAsState()
    val renderer by casting.renderer.collectAsState()
    val quality by casting.quality.collectAsState()
    val tv = renderer
    val remote by (tv?.state ?: remember { MutableStateFlow(DlnaState()) }).collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(casting) { casting.discovery.start(); onDispose { casting.discovery.stop() } }
    val why = casting.whyNot()

    @Composable
    fun note(text: String) = Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    CosmoActionRow(icon = CosmoIcons.ChevronLeft, label = "Play on a TV", menu = menu) { back() }
    if (tv == null) {
        note(why ?: "Choose a TV on the same Wi-Fi.")
        if (devices.isEmpty()) note("Searching for TVs…")
        devices.forEach { device ->
            CosmoActionRow(CosmoIcons.Devices, device.name, value = device.model, enabled = !busy && why == null, menu = menu) {
                busy = true; message = null
                scope.launch {
                    runCatching { casting.connect(device) }
                        .onFailure { message = it.message?.takeIf { text -> text.length < 90 } ?: "This TV could not play this video." }
                    busy = false
                }
            }
        }
    } else {
        note("Playing on ${tv.device.name}" + (quality?.let { " in $it" } ?: "") + ".")
        CosmoActionRow(if (remote.playing) CosmoIcons.Pause else CosmoIcons.Play, if (remote.playing) "Pause" else "Play", menu = menu) {
            scope.launch { runCatching { if (remote.playing) tv.pause() else tv.play() } }
        }
        CosmoActionRow(CosmoIcons.ChevronLeft, "Back 10 seconds", menu = menu) {
            scope.launch { runCatching { tv.seek((remote.positionMs - 10_000).coerceAtLeast(0)) } }
        }
        CosmoActionRow(CosmoIcons.ChevronRight, "Forward 10 seconds", menu = menu) {
            scope.launch { runCatching { tv.seek(remote.positionMs + 10_000) } }
        }
        val level = remote.volume
        CosmoActionRow(CosmoIcons.VolumeLow, "Quieter", value = level?.let { "${(it * 100).toInt()}%" }, enabled = level != null, menu = menu) {
            scope.launch { runCatching { tv.setVolume(((level ?: 0f) - 0.1f).coerceAtLeast(0f)) } }
        }
        CosmoActionRow(CosmoIcons.Volume, "Louder", enabled = level != null, menu = menu) {
            scope.launch { runCatching { tv.setVolume(((level ?: 0f) + 0.1f).coerceAtMost(1f)) } }
        }
        CosmoActionRow(CosmoIcons.Close, "Stop playing on TV", menu = menu) {
            scope.launch { casting.disconnect(); done() }
        }
    }
    message?.let { note(it) }
}

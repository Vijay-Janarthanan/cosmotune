package com.cosmotune.ui.extras

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.music.data.cast.dlna.DlnaCast
import com.cosmotune.music.data.cast.dlna.DlnaRenderer
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Play [song] on a DLNA / UPnP renderer (a smart TV, a network speaker): renderers found on the local network while this is
 * open, then play, pause, seek, volume and stop on the one chosen. Opened from a song's menu on both apps; plainly drawn, for
 * the player redesign to fold into its own cast picker.
 */
@Composable
fun DlnaCastDialog(song: Song?, onDismiss: () -> Unit) {
    val devices by DlnaCast.discovery.devices.collectAsState()
    val renderer by DlnaCast.renderer.collectAsState()
    val casting by DlnaCast.song.collectAsState()
    val error by DlnaCast.error.collectAsState()
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        DlnaCast.discovery.start()
        onDispose { DlnaCast.discovery.stop() }
    }
    val connected = renderer
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = "Play on a TV or speaker",
        message = "DLNA and UPnP renderers on this network. The song is served from this device, so keep it on the same network.",
        confirmLabel = if (connected != null) "Stop casting" else null,
        onConfirm = { scope.launch { DlnaCast.disconnect(stopPlayback = true) } },
        dismissLabel = "Close",
        onDismiss = onDismiss,
        maxWidth = 520.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connected == null) {
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text("Looking for renderers…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                devices.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = connecting == null) {
                            connecting = device.id
                            scope.launch {
                                if (DlnaCast.connect(device) && song != null) DlnaCast.cast(song)
                                connecting = null
                            }
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (connecting == device.id) CircularProgressIndicator(Modifier.size(20.dp))
                    }
                }
            } else {
                RendererControls(connected, casting, song)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RendererControls(renderer: DlnaRenderer, casting: Song?, asked: Song?) {
    val state by renderer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(renderer) {
        while (true) {
            position = renderer.positionNow()
            delay(500)
        }
    }
    Text("On ${renderer.device.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        casting?.let { "${it.title} · ${it.artist}" } ?: "Nothing sent yet",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (asked != null && asked.videoId != casting?.videoId) {
        TextButton(onClick = { scope.launch { DlnaCast.cast(asked) } }) { Text("Play ${asked.title} here") }
    }
    if (state.durationMs > 0) {
        Slider(
            value = dragging ?: (position.toFloat() / state.durationMs).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val target = ((dragging ?: 0f) * state.durationMs).toLong()
                dragging = null
                scope.launch { runCatching { renderer.seek(target) } }
            },
        )
        Text(
            "${DlnaRenderer.clock(position)} / ${DlnaRenderer.clock(state.durationMs)}" + if (state.buffering) " · loading" else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { scope.launch { runCatching { if (state.playing) renderer.pause() else renderer.play() } } }) {
            Text(if (state.playing) "Pause" else "Play")
        }
        if (state.lost) Text("The renderer stopped answering.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    state.volume?.let { volume ->
        var level by remember(renderer) { mutableStateOf(volume) }
        Text("Volume", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = level,
            onValueChange = { level = it },
            onValueChangeFinished = { scope.launch { runCatching { renderer.setVolume(level) } } },
        )
    }
}

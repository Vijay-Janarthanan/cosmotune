package com.cosmotune.ui.extras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.cast.dlna.DlnaCast
import com.cosmotune.music.data.cast.dlna.DlnaRenderer
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitOptionRow
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Play [song] on a DLNA / UPnP renderer (a smart TV, a network speaker): renderers found on the local network while this is
 * open, then play, pause, seek, volume and stop on the one chosen. Opened from a song's menu on both apps. An Orbit dialog: the
 * renderers are the kit's option rows, the one chosen gets a quiet card with its seek line, its play button and its volume line.
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
        val look = orbitLook()
        val fonts = orbitFonts()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected == null) {
                if (devices.isEmpty()) {
                    OrbitOptionRow(
                        title = "Looking for renderers…",
                        subtitle = null,
                        icon = null,
                        onClick = null,
                        discContent = {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = look.ink, strokeWidth = 2.dp)
                            }
                        },
                    )
                }
                devices.forEach { device ->
                    OrbitOptionRow(
                        title = device.name,
                        subtitle = device.model,
                        icon = Icons.Rounded.Cast,
                        onClick = if (connecting == null) ({
                            connecting = device.id
                            scope.launch {
                                if (DlnaCast.connect(device) && song != null) DlnaCast.cast(song)
                                connecting = null
                            }
                        }) else null,
                        dimmed = connecting != null && connecting != device.id,
                        trailing = if (connecting == device.id) ({
                            CircularProgressIndicator(Modifier.padding(start = 10.dp).size(18.dp), color = look.ink, strokeWidth = 2.dp)
                        }) else null,
                    )
                }
            } else {
                RendererControls(connected, casting, song)
            }
            error?.let { Text(it, style = fonts.body.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RendererControls(renderer: DlnaRenderer, casting: Song?, asked: Song?) {
    val state by renderer.state.collectAsState()
    val scope = rememberCoroutineScope()
    val look = orbitLook()
    val fonts = orbitFonts()
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(renderer) {
        while (true) {
            position = renderer.positionNow()
            delay(500)
        }
    }
    OrbitOptionRow(
        title = casting?.let { "${it.title} · ${it.artist}" } ?: "Nothing sent yet",
        subtitle = "On ${renderer.device.name}",
        icon = Icons.Rounded.CastConnected,
        onClick = null,
        now = casting != null,
    )
    if (asked != null && asked.videoId != casting?.videoId) {
        OrbitSheetButton("Play ${asked.title} here", { scope.launch { DlnaCast.cast(asked) } }, Modifier.fillMaxWidth())
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(look.row).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OrbitRoundButton(
                if (state.playing) CosmoIcons.Pause else CosmoIcons.Play,
                if (state.playing) "Pause" else "Play",
                look.ink,
                { scope.launch { runCatching { if (state.playing) renderer.pause() else renderer.play() } } },
                size = 44.dp,
            )
            Spacer(Modifier.width(14.dp))
            if (state.durationMs > 0) {
                Column(Modifier.weight(1f)) {
                    // Seeks where it is let go, as the slider did on release.
                    OrbitProgressLine(
                        progress = { (position.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                        ink = look.ink,
                        onSeek = { fraction -> scope.launch { runCatching { renderer.seek((fraction * state.durationMs).toLong()) } } },
                    )
                    OrbitLabel(
                        "${DlnaRenderer.clock(position)} / ${DlnaRenderer.clock(state.durationMs)}" + if (state.buffering) " · loading" else "",
                        fonts, look.dim,
                    )
                }
            } else Spacer(Modifier.weight(1f))
        }
        if (state.lost) Text("The renderer stopped answering.", style = fonts.body.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.error)
        state.volume?.let { volume ->
            var level by remember(renderer) { mutableStateOf(volume) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (level < 0.4f) CosmoIcons.VolumeLow else CosmoIcons.Volume, contentDescription = "Volume", tint = look.ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                // Sent to the renderer when it is let go, as the slider did.
                OrbitProgressLine(
                    progress = { level },
                    ink = look.ink,
                    modifier = Modifier.weight(1f),
                    onSeek = { level = it; scope.launch { runCatching { renderer.setVolume(it) } } },
                )
            }
        }
    }
}

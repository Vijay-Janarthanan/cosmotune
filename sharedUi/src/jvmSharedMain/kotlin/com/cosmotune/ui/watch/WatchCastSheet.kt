package com.cosmotune.ui.watch

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.*
import com.cosmotune.watch.WatchCasting
import com.cosmotune.watch.model.Stream
import kotlinx.coroutines.launch

@Composable
internal fun WatchCastSheet(casting: WatchCasting, stream: Stream?, fonts: OrbitFonts, look: OrbitLook, onDismiss: () -> Unit) {
    val devices by casting.discovery.devices.collectAsState()
    val renderer by casting.renderer.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(casting) { casting.discovery.start(); onDispose { casting.discovery.stop() } }
    // Google Cast devices, where the platform has them (Android phones with Play Services).
    val googleCast = remember { com.cosmotune.watch.WatchReceiverFinders.googleCast }
    val castDevices by (googleCast?.found ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    DisposableEffect(googleCast) { val stop = googleCast?.search(); onDispose { stop?.invoke() } }
    fun send(name: String, open: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            runCatching { open() }.onSuccess { message = "Connected to $name" }.onFailure { message = it.message?.takeIf { _ -> it is IllegalArgumentException } ?: "This receiver could not play this copy" }
            busy = false
        }
    }
    com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = onDismiss, title = "Play on a TV", dismissLabel = "Done",
        message = renderer?.let { "Playing on ${it.name}. Play, pause and seek are on the player." } ?: "Choose a TV on the same Wi-Fi.") {
        casting.whyNot(stream)?.let { Text(it, style = fonts.body, color = look.dim) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (devices.isEmpty() && castDevices.isEmpty()) Text("Searching for receivers…", style = fonts.body, color = look.dim)
            devices.forEach { device -> WatchCardChip(device.name, look.ink, fonts, enabled = !busy && casting.whyNot(stream) == null, onClick = {
                send(device.name) { casting.connect(device) }
            }) }
            castDevices.forEach { found -> WatchCardChip(found.name, look.ink, fonts, enabled = !busy && casting.whyNot(stream) == null, onClick = {
                send(found.name) { casting.connect(googleCast!!.open(found)) }
            }) }
            renderer?.let { tv ->
                val remote by tv.state.collectAsState()
                val level = remote.volume
                Text(if (level != null) "TV volume ${(level * 100).toInt()}%" else "TV volume", style = fonts.body, color = look.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchCardChip("Quieter", look.ink, fonts, enabled = !busy && level != null, onClick = {
                        scope.launch { runCatching { tv.setVolume(((level ?: 0f) - VOLUME_STEP).coerceAtLeast(0f)) } }
                    })
                    WatchCardChip("Louder", look.ink, fonts, enabled = !busy && level != null, onClick = {
                        scope.launch { runCatching { tv.setVolume(((level ?: 0f) + VOLUME_STEP).coerceAtMost(1f)) } }
                    })
                }
                WatchCardChip("Stop playing on TV", look.ink, fonts, enabled = !busy, onClick = {
                    scope.launch { casting.disconnect(); onDismiss() }
                })
            }
            message?.let { Text(it, style = fonts.body, color = look.dim) }
        }
    }
}

private const val VOLUME_STEP = 0.1f

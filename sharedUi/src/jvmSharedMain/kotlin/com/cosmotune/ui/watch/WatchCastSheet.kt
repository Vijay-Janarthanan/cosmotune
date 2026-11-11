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
    com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = onDismiss, title = "Cast video", dismissLabel = "Done",
        message = renderer?.let { "Playing on ${it.device.name}" } ?: "Choose a DLNA TV or receiver on the same network.") {
        casting.whyNot(stream)?.let { Text(it, style = fonts.body, color = look.dim) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (devices.isEmpty()) Text("Searching for receivers…", style = fonts.body, color = look.dim)
            devices.forEach { device -> WatchCardChip(device.name, look.ink, fonts, enabled = !busy && casting.whyNot(stream) == null, onClick = {
                busy = true; message = null
                scope.launch {
                    runCatching { casting.connect(device) }.onSuccess { message = "Connected to ${device.name}" }.onFailure { message = "This receiver could not play this copy" }
                    busy = false
                }
            }) }
            if (renderer != null) WatchCardChip("Stop casting", look.ink, fonts, enabled = !busy, onClick = {
                scope.launch { casting.disconnect(); onDismiss() }
            })
            message?.let { Text(it, style = fonts.body, color = look.dim) }
        }
    }
}

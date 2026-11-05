package com.cosmotune.music.ui.player

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.music.playback.AndroidListeningDevices
import com.cosmotune.music.playback.AudioOutputStatus
import com.cosmotune.music.playback.AudioRouting
import com.cosmotune.music.playback.cast.CastController
import com.cosmotune.music.wear.WearBridge
import com.cosmotune.ui.nowplaying.ListeningDeviceSheet

/** Shared sheet entry for Settings, stats and the output picker. */
@Composable
fun AndroidListeningDeviceEntry() {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Listening device · profile and connection") }
    if (open) AndroidListeningDeviceDialog { open = false }
}

@Composable
fun AndroidListeningDeviceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val output by AudioOutputStatus.current.collectAsState()
    val route by AndroidListeningDevices.route.collectAsState()
    val spatial by AndroidListeningDevices.spatial.collectAsState()
    val cast by CastController.state.collectAsState()
    val watch by WearBridge.watchOutput.collectAsState()
    LaunchedEffect(output.activeDeviceId) {
        val manager = context.getSystemService(AudioManager::class.java)
        AndroidListeningDevices.routeChanged(context, manager?.let { AudioRouting.infoFor(it, output.activeDeviceId) })
    }
    val facts = remember(output, route, spatial, cast, watch) {
        AndroidListeningDevices.facts(output).let { local ->
            if (cast.casting || watch.active) local.copy(routeKey = null, name = "Remote playback",
                identityDetail = "Return playback to this phone before saving or applying a listening-device profile.", measured = emptyList(),
                unavailable = "The remote receiver's active hardware format and codec are not measured here", tuningAvailable = false)
            else local
        }
    }
    ListeningDeviceSheet(facts, remember(context) { AndroidListeningDevices.store(context) },
        AndroidListeningDevices::captureEq, AndroidListeningDevices::applyEq,
        onOpenSystem = { runCatching { context.startActivity(Intent(if (output.routeKind == AudioRouting.Kind.BLUETOOTH) Settings.ACTION_BLUETOOTH_SETTINGS else Settings.ACTION_SOUND_SETTINGS)) } },
        onOpenGuide = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, onDismiss = onDismiss)
}

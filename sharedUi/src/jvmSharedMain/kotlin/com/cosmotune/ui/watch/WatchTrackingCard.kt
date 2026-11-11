package com.cosmotune.ui.watch

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.*
import com.cosmotune.watch.*
import kotlinx.coroutines.launch

@Composable
internal fun WatchTrackingCard(tracking: WatchTracking, fonts: OrbitFonts, look: OrbitLook) {
    val statuses by tracking.statuses.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var service by remember { mutableStateOf(WatchTracker.TRAKT) }
    var clientId by remember(service) { mutableStateOf("") }
    val status = statuses.firstOrNull { it.service == service } ?: WatchTrackingStatus(service)
    var connectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(tracking) { onDispose { connectionJob?.cancel() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OrbitLabel("Watch accounts", fonts, look.dim)
        Text("Sync saved titles and resume points for this viewer. Connecting is optional.", style = fonts.body, color = look.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchTracker.entries.forEach { tracker -> OrbitSelectChip(tracker.label, service == tracker, fonts, look,
                onClick = { service = tracker }, haptic = false) }
        }
        if (!status.connected) {
            OrbitTextField(clientId, { clientId = it.take(200) }, "Registered public client ID", fonts, look, Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchCardChip(if (status.busy) "Waiting for approval…" else "Connect ${service.label}", look.ink, fonts,
                    enabled = clientId.isNotBlank() && !status.busy, onClick = {
                        connectionJob = scope.launch { tracking.connect(service, clientId) }
                    })
                WatchCardChip("Register an app", look.ink, fonts, onClick = {
                    uri.openUri(if (service == WatchTracker.TRAKT) "https://app.trakt.tv/oauth/applications" else "https://simkl.com/settings/developer/")
                })
                if (status.busy) WatchCardChip("Cancel", look.ink, fonts, onClick = { connectionJob?.cancel() })
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchCardChip("Sync now", look.ink, fonts, enabled = !status.busy, onClick = { scope.launch { tracking.sync(service) } })
            WatchCardChip("Disconnect", look.ink, fonts, enabled = !status.busy, onClick = { scope.launch { tracking.disconnect(service) } })
        }
        status.userCode?.let { code -> Text("Approval code · $code", style = fonts.title, color = look.ink) }
        status.verificationUrl?.let { url ->
            WatchCardChip("Open approval page", look.ink, fonts, onClick = { uri.openUri(url) })
            LocalWatchQrCode.current?.invoke(url, 140.dp)
        }
        status.message?.let { Text(it, style = fonts.body, color = look.dim) }
    }
}

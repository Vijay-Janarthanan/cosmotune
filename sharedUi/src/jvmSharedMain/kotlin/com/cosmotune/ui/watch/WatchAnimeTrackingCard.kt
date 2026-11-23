package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.watch.AnimeTracker
import com.cosmotune.watch.AnimeTracking
import com.cosmotune.watch.AnimeTrackingStatus
import kotlinx.coroutines.launch

/**
 * AniList and MyAnimeList in Watch settings: the client id of the app registered with the service, Sign in (the browser
 * comes back to Cosmotune by itself; where it cannot, the address it ends on is pasted here), and who is connected.
 */
@Composable
internal fun WatchAnimeTrackingCard(tracking: AnimeTracking, fonts: OrbitFonts, look: OrbitLook) {
    val statuses by tracking.statuses.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var service by remember { mutableStateOf(AnimeTracker.ANILIST) }
    val status = statuses.firstOrNull { it.service == service } ?: AnimeTrackingStatus(service)
    var clientId by remember(service) { mutableStateOf("") }
    var returned by remember(service) { mutableStateOf("") }
    var signingIn by remember(service) { mutableStateOf(false) }
    var message by remember(service) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Finished anime episodes move your list on. It never goes backwards, and a show it cannot match is left alone.",
            style = fonts.body, color = look.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimeTracker.entries.forEach { tracker ->
                val on = statuses.firstOrNull { it.service == tracker }?.connected == true
                OrbitSelectChip(tracker.label + if (on) " · connected" else "", service == tracker, fonts, look, onClick = { service = tracker }, haptic = false)
            }
        }
        when {
            status.connected -> {
                Text("Connected${status.user?.let { " as $it" } ?: ""}.", style = fonts.body, color = look.ink)
                if (service == AnimeTracker.ANILIST) {
                    // Private entries: shows this profile adds or moves on are seen on its AniList only by the listener.
                    val repository = watchRepository()
                    val prefs by repository.preferences.collectAsState()
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Keep these entries private", style = fonts.body, color = look.ink, modifier = Modifier.weight(1f))
                        com.cosmotune.ui.orbit.OrbitSwitch(prefs.animePrivate, { on -> scope.launch { repository.setPreferences(prefs.copy(animePrivate = on)) } }, look,
                            "Keep these entries private")
                    }
                }
                WatchCardChip("Disconnect", look.ink, fonts, onClick = { scope.launch { tracking.disconnect(service) } })
            }
            status.clientId == null -> {
                OrbitLabel("Client ID", fonts, look.dim)
                OrbitTextField(clientId, { clientId = it.take(200) }, "From your ${service.label} app", fonts, look, Modifier.fillMaxWidth())
                Text("Register an app with ${service.label} once and give it this return address: ${service.redirect}", style = fonts.body, color = look.dim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchCardChip("Save client ID", look.ink, fonts, enabled = clientId.isNotBlank(), onClick = {
                        scope.launch { message = runCatching { tracking.setClientId(service, clientId) }.exceptionOrNull()?.message }
                    })
                    WatchCardChip("Register an app", look.ink, fonts, onClick = { uri.openUri(service.registerUrl) })
                }
            }
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchCardChip(if (status.busy) "Signing in…" else "Sign in to ${service.label}", look.ink, fonts, enabled = !status.busy, onClick = {
                        tracking.signInUrl(service)?.let { url -> signingIn = true; message = null; uri.openUri(url) }
                    })
                    WatchCardChip("Change client ID", look.ink, fonts, onClick = { scope.launch { tracking.setClientId(service, "") } })
                }
                if (signingIn) {
                    OrbitLabel("Didn't come back by itself?", fonts, look.dim)
                    OrbitTextField(returned, { returned = it.take(4_000) }, "Paste the address the browser ended on", fonts, look, Modifier.fillMaxWidth())
                    WatchCardChip("Finish signing in", look.ink, fonts, enabled = tracking.isReturn(returned), onClick = {
                        scope.launch { message = tracking.finish(returned); returned = "" ; signingIn = !statuses.any { it.service == service && it.connected } }
                    })
                }
            }
        }
        (message ?: status.message)?.let { Text(it, style = fonts.body, color = look.dim) }
    }
}

/** The browser came back from AniList or MyAnimeList: the sign-in finishes and says how it went. */
@Composable
internal fun AnimeSignInReturn(repository: com.cosmotune.watch.WatchRepository, link: String, onDone: () -> Unit) {
    var message by remember(link) { mutableStateOf<String?>(null) }
    LaunchedEffect(link) {
        message = repository.animeTracking?.let { runCatching { it.finish(link) }.getOrNull() } ?: "Anime lists are not offered here."
    }
    com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = onDone, title = "Anime lists", message = message ?: "Finishing sign-in…",
        confirmLabel = "Done", onConfirm = onDone)
}

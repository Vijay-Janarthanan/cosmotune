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
internal fun WatchDebridCard(repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook) {
    val preferences by repository.preferences.collectAsState()
    val configured by repository.configuredServices.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var token by remember(preferences.debrid) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OrbitLabel("Torrent service accounts", fonts, look.dim)
        Text("Optionally prepare the selected torrent through your own service account. Transfers use that service’s quota. Tokens stay on this device.", style = fonts.body, color = look.dim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitSelectChip("Built-in engine", preferences.debrid == null, fonts, look, onClick = { scope.launch { repository.setPreferences(preferences.copy(debrid = null)) } }, haptic = false)
            WatchDebrid.entries.forEach { service -> OrbitSelectChip(service.label, preferences.debrid == service, fonts, look,
                onClick = { scope.launch { repository.setPreferences(preferences.copy(debrid = service)); message = null } }, haptic = false) }
        }
        preferences.debrid?.let { service ->
            Text(if (service.key in configured) "${service.label} token saved" else "Add a token to enable ${service.label}. The built-in engine is used until then.", style = fonts.body, color = look.dim)
            WatchField("API token", token, { token = it.take(200) }, fonts, look, password = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchCardChip("Save token", look.ink, fonts, enabled = token.isNotBlank(), onClick = {
                    scope.launch { runCatching { repository.setServiceKey(service.key, token) }.onSuccess { token = ""; message = "Saved" }.onFailure { message = "The token could not be saved" } }
                })
                WatchCardChip("Account / token", look.ink, fonts, onClick = { uri.openUri(service.accountUrl) })
                if (service.key in configured) WatchCardChip("Remove", look.ink, fonts, onClick = { scope.launch { repository.setServiceKey(service.key, "") } })
            }
        }
        message?.let { Text(it, style = fonts.body, color = look.dim) }
    }
}

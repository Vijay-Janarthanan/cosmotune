package com.cosmotune.ui.watch

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.*
import com.cosmotune.watch.WatchRepository
import kotlinx.coroutines.launch

@Composable
internal fun WatchProfileGate(repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook) {
    val profiles by repository.profiles.collectAsState()
    val active by repository.activeProfile.collectAsState()
    var selected by remember(active) { mutableStateOf(active) }
    var pin by remember(selected) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            com.cosmotune.ui.kit.KitModeSwitcher()
            OrbitLabel("Watch profiles", fonts, look.dim)
            Text("Who’s watching?", style = fonts.section, color = look.ink)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile -> OrbitSelectChip(profile.name, selected == profile.id, fonts, look,
                    onClick = { selected = profile.id; message = null }, haptic = false) }
            }
            if (profiles.any { it.id == selected && it.locked }) WatchField("PIN", pin, { pin = it.filter(Char::isDigit).take(8) }, fonts, look, password = true)
            WatchCardChip(if (busy) "Unlocking…" else "Continue", look.ink, fonts, enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    if (repository.selectProfile(selected, pin)) { WatchNav.go(WatchTab.HOME); message = null } else message = "The PIN did not match"
                    busy = false
                }
            })
            message?.let { Text(it, style = fonts.body, color = look.dim) }
        }
    }
}

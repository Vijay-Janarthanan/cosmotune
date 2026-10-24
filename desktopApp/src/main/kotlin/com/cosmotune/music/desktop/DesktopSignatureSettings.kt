package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.canvas.ArtistSignatureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal object DesktopSignatureSettings {
    private val persistence = DesktopPersistence()
    val enabled = MutableStateFlow(persistence.boolean("signature_api_enabled", false))
    val url = MutableStateFlow(persistence.string("signature_api_url", ""))
    fun enable(value: Boolean) {
        enabled.value = value
        persistence.saveBoolean("signature_api_enabled", value)
    }
    fun save(value: String) {
        url.value = ArtistSignatureRepository.normalizeBaseUrl(value)
        persistence.saveString("signature_api_url", url.value)
        ArtistSignatureRepository.clear()
        enable(url.value.isNotBlank())
    }
}

@Composable
internal fun DesktopArtistSignatureSettings() {
    val enabled by DesktopSignatureSettings.enabled.collectAsStateWithLifecycle()
    val stored by DesktopSignatureSettings.url.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf(false) }
    var draft by remember(editor) { mutableStateOf(stored) }
    var busy by remember(editor) { mutableStateOf(false) }
    var error by remember(editor) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SettingsToggle("Artist signatures", "Show a configured provider's handwritten artist signatures", enabled, DesktopSignatureSettings::enable)
    SettingsNavigationRow("Signature API URL", if (stored.isBlank()) "Not configured" else "Configured", onClick = { editor = true })
    if (editor) AlertDialog(
        onDismissRequest = { if (!busy) editor = false },
        title = { Text("Artist signatures") },
        text = {
            Column {
                Text("Use an artist-signature API that provides /v1/health and /v1/signatures. Leaving the URL blank removes it.")
                OutlinedTextField(draft, { draft = it; error = null }, label = { Text("API URL") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (draft.isBlank()) { DesktopSignatureSettings.save(""); editor = false }
                else {
                    busy = true
                    scope.launch {
                        if (ArtistSignatureRepository.checkHealth(draft)) {
                            DesktopSignatureSettings.save(draft); editor = false
                        } else error = "Could not reach a signature API at that URL"
                        busy = false
                    }
                }
            }) { Text(if (busy) "Checking…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { editor = false }) { Text("Cancel") } },
    )
}

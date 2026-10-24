package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.subsonic.SubsonicClient
import com.cosmotune.music.data.subsonic.SubsonicCredential
import com.cosmotune.music.data.subsonic.SubsonicException
import com.cosmotune.providers.SubsonicCatalogue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** What the Subsonic editor hands back: the label, the normalised server address and the settings that hold the credential. */
data class SubsonicServerEdit(val label: String, val address: String, val settings: Map<String, String>) {
    override fun toString() = "SubsonicServerEdit(label=$label, address=$address)"
}

/**
 * Adds or edits a Subsonic/OpenSubsonic server. The password or API key is kept by the host with the source
 * configuration (Android encrypted preferences; Windows DPAPI on desktop) and is never shown again: leaving
 * the field empty while editing keeps the saved one. Saving checks the server first, so a stored server is
 * one that answered.
 */
@Composable
fun SubsonicServerDialog(isNew: Boolean, label: String, address: String, settings: Map<String, String>, http: OkHttpClient,
    onSave: (SubsonicServerEdit) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(label) }
    var server by remember { mutableStateOf(address) }
    var user by remember { mutableStateOf(settings[SubsonicCatalogue.KEY_USER].orEmpty()) }
    var useKey by remember { mutableStateOf(!settings[SubsonicCatalogue.KEY_API_KEY].isNullOrBlank()) }
    var secret by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun candidate(): SubsonicServerEdit? {
        val normalized = SubsonicClient.normalize(server) ?: return null
        val kept = if (useKey) settings[SubsonicCatalogue.KEY_API_KEY] else settings[SubsonicCatalogue.KEY_PASSWORD]
        val value = secret.ifEmpty { kept.orEmpty() }
        if (value.isEmpty() || (!useKey && user.isBlank())) return null
        val stored = if (useKey) mapOf(SubsonicCatalogue.KEY_API_KEY to value) + (user.trim().takeIf { it.isNotEmpty() }?.let { mapOf(SubsonicCatalogue.KEY_USER to it) } ?: emptyMap())
            else mapOf(SubsonicCatalogue.KEY_USER to user.trim(), SubsonicCatalogue.KEY_PASSWORD to value)
        return SubsonicServerEdit(name.trim(), normalized.toString().removeSuffix("/"), stored)
    }

    fun check(thenSave: Boolean) {
        val edit = candidate() ?: run { failed = true; message = "Enter the server address, your user name and a password or API key."; return }
        checking = true; message = null; failed = false
        scope.launch {
            try {
                val credential = requireNotNull(SubsonicCatalogue.credential(edit.settings))
                val info = SubsonicClient(edit.address, edit.settings[SubsonicCatalogue.KEY_USER].orEmpty(), credential, http).ping()
                val about = listOfNotNull(info.type, info.version.takeIf { it.isNotBlank() }).joinToString(" ")
                message = "Connected" + (if (about.isNotEmpty()) " · $about" else "") + (if (info.openSubsonic) " · OpenSubsonic" else "")
                if (thenSave) onSave(edit.copy(label = edit.label.ifBlank { info.type?.replaceFirstChar(Char::uppercase) ?: "" }))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SubsonicException) { failed = true; message = error.message }
            catch (error: IllegalArgumentException) { failed = true; message = error.message ?: "Check the server address." }
            catch (_: Exception) { failed = true; message = "Nothing answered at that address." }
            finally { checking = false }
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "Add a Subsonic server" else label.ifBlank { "Subsonic server" }) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Navidrome, Gonic, Airsonic, Ampache and other Subsonic or OpenSubsonic servers. Cosmotune searches, browses and streams your own files from it.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(server, { server = it; message = null }, Modifier.fillMaxWidth().testTag("subsonic-address"), singleLine = true,
                enabled = !checking, label = { Text("Server address") }, placeholder = { Text("https://music.example.com") })
            OutlinedTextField(user, { user = it; message = null }, Modifier.fillMaxWidth().testTag("subsonic-user"), singleLine = true,
                enabled = !checking, label = { Text(if (useKey) "User name (optional)" else "User name") })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !useKey, onClick = { useKey = false; secret = "" }, label = { Text("Password") })
                FilterChip(selected = useKey, onClick = { useKey = true; secret = "" }, label = { Text("API key") })
            }
            val saved = !isNew && !(if (useKey) settings[SubsonicCatalogue.KEY_API_KEY] else settings[SubsonicCatalogue.KEY_PASSWORD]).isNullOrEmpty()
            OutlinedTextField(secret, { secret = it; message = null }, Modifier.fillMaxWidth().testTag("subsonic-secret"), singleLine = true,
                enabled = !checking, visualTransformation = PasswordVisualTransformation(),
                label = { Text(if (useKey) "API key" else "Password") },
                supportingText = if (saved) ({ Text("Leave empty to keep the saved one") }) else null)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("subsonic-label"), singleLine = true,
                enabled = !checking, label = { Text("Name (optional)") })
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("subsonic-message")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { check(thenSave = false) }, enabled = !checking) { Text("Test") }
                if (onRemove != null) TextButton(onClick = onRemove, enabled = !checking) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(onClick = { check(thenSave = true) }, enabled = !checking) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

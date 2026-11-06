package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoDialogQuietButton
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

    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = if (isNew) "Add a Subsonic server" else label.ifBlank { "Subsonic server" },
        content = {
        val fonts = orbitFonts()
        val look = orbitLook()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Navidrome, Gonic, Airsonic, Ampache and other Subsonic or OpenSubsonic servers. Cosmotune searches, browses and streams your own files from it.",
                color = look.dim)
            OrbitFormField(server, { server = it; message = null }, "https://music.example.com", fieldModifier = Modifier.testTag("subsonic-address"),
                label = "Server address", enabled = !checking)
            OrbitFormField(user, { user = it; message = null }, if (useKey) "User name (optional)" else "User name", fieldModifier = Modifier.testTag("subsonic-user"),
                label = if (useKey) "User name (optional)" else "User name", enabled = !checking)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitChip("Password", fonts, look, onClick = { useKey = false; secret = "" }, selected = !useKey, leading = null)
                OrbitChip("API key", fonts, look, onClick = { useKey = true; secret = "" }, selected = useKey, leading = null)
            }
            val saved = !isNew && !(if (useKey) settings[SubsonicCatalogue.KEY_API_KEY] else settings[SubsonicCatalogue.KEY_PASSWORD]).isNullOrEmpty()
            OrbitFormField(secret, { secret = it; message = null }, if (useKey) "API key" else "Password", fieldModifier = Modifier.testTag("subsonic-secret"),
                enabled = !checking, secret = true, supporting = if (saved) "Leave empty to keep the saved one" else null)
            OrbitFormField(name, { name = it }, "Name (optional)", fieldModifier = Modifier.testTag("subsonic-label"),
                label = "Name (optional)", enabled = !checking)
            if (checking) OrbitBusyRing()
            message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else look.accent,
                style = fonts.body.copy(fontSize = 12.5.sp), modifier = Modifier.testTag("subsonic-message")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CosmoDialogQuietButton("Test", { check(thenSave = false) }, enabled = !checking)
                if (onRemove != null) CosmoDialogQuietButton("Remove", onRemove, enabled = !checking)
            }
        }
    },
        confirmLabel = "Save",
        onConfirm = { check(thenSave = true) },
        confirmEnabled = !checking,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
    )
}

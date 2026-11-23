package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.jellyfin.JellyfinLibrary
import com.cosmotune.music.data.jellyfin.JellyfinSession
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoDialogQuietButton
import kotlinx.coroutines.launch

/**
 * Signs in to the listener's own Jellyfin server, on the phone and the desktop alike. The password goes to that server only and is
 * not kept; the host keeps the [JellyfinSession] the server answers with. [onSignOut] is offered while one is saved.
 */
@Composable
fun JellyfinServerDialog(current: JellyfinSession?, onSignedIn: (JellyfinSession) -> Unit, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    var server by remember { mutableStateOf(current?.url.orEmpty()) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fonts = orbitFonts()
    val look = orbitLook()

    fun signIn() {
        if (JellyfinLibrary.normalizeUrl(server) == null || user.isBlank()) { message = "Enter the server address and your user name."; return }
        busy = true; message = null
        scope.launch {
            JellyfinLibrary.signIn(server, user, password)
                .onSuccess(onSignedIn)
                .onFailure { message = it.message?.takeIf { text -> text.endsWith(".") } ?: "Nothing answered at that address." }
            busy = false
        }
    }

    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = "Jellyfin server",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (current != null) "Signed in to ${current.url}. Its songs are in Local music." else
                    "Play the music on your own Jellyfin server. Your password goes to the server only and is not kept.", color = look.dim)
                OrbitFormField(server, { server = it; message = null }, "http://192.168.1.10:8096", fieldModifier = Modifier.testTag("jellyfin-address"),
                    label = "Server address", enabled = !busy)
                OrbitFormField(user, { user = it; message = null }, "User name", fieldModifier = Modifier.testTag("jellyfin-user"),
                    label = "User name", enabled = !busy)
                OrbitFormField(password, { password = it; message = null }, "Password", fieldModifier = Modifier.testTag("jellyfin-password"),
                    enabled = !busy, secret = true)
                if (busy) OrbitBusyRing()
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = fonts.body.copy(fontSize = 12.5.sp), modifier = Modifier.testTag("jellyfin-message")) }
                if (current != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CosmoDialogQuietButton("Sign out", onSignOut, enabled = !busy)
                }
            }
        },
        confirmLabel = "Sign in",
        onConfirm = ::signIn,
        confirmEnabled = !busy,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
    )
}

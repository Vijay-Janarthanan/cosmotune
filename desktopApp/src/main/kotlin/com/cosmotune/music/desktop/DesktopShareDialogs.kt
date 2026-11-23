package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoDialogQuietButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One field of a share's form: what it is called, what it holds, and whether it is a password. */
private class ShareField(val label: String, val hint: String, val secret: Boolean = false)

/**
 * The form for a network share, in the Subsonic server's layout: the fields, Test and Remove, then Save (which tests first,
 * so a share that cannot be reached is never saved silently).
 */
@Composable
private fun ShareDialog(
    title: String,
    intro: String,
    fields: List<ShareField>,
    initial: List<String>,
    test: suspend (List<String>) -> Result<Unit>,
    onSave: (List<String>) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var values by remember { mutableStateOf(initial) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun check(thenSave: Boolean) {
        checking = true; message = null; failed = false
        val snapshot = values
        scope.launch {
            try {
                test(snapshot).getOrThrow()
                message = "Connected"
                if (thenSave) onSave(snapshot)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed = true; message = error.message?.takeIf { it.length < 120 } ?: "Nothing answered at that address." }
            finally { checking = false }
        }
    }

    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            val fonts = orbitFonts()
            val look = orbitLook()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(intro, color = look.dim)
                fields.forEachIndexed { index, field ->
                    OrbitFormField(values[index], { typed -> values = values.toMutableList().also { it[index] = typed }; message = null },
                        field.hint, label = field.label, enabled = !checking, secret = field.secret)
                }
                if (checking) OrbitBusyRing()
                message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else look.accent, style = fonts.body.copy(fontSize = 12.5.sp)) }
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

/** Settings > Local music > WebDAV server. [onChanged] runs after a save or a removal so Local music lists the share again. */
@Composable
internal fun DesktopWebDavDialog(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val saved = remember { DesktopNetworkShares.webDav() }
    fun of(values: List<String>) = DesktopNetworkShares.WebDav(values[0], values[1], values[2])
    ShareDialog(
        title = "WebDAV server",
        intro = "Nextcloud, ownCloud, a NAS or any WebDAV server. Its songs join Local music, each folder as an album.",
        fields = listOf(ShareField("Server address", "https://cloud.example.com/remote.php/dav/files/me/Music"),
            ShareField("User name", "User name"), ShareField("Password", "Password", secret = true)),
        initial = listOf(saved.url, saved.username, saved.password),
        test = { DesktopNetworkShares.testWebDav(of(it)) },
        onSave = { DesktopNetworkShares.saveWebDav(of(it)); onChanged(); onDismiss() },
        onRemove = if (saved.configured) ({ DesktopNetworkShares.saveWebDav(DesktopNetworkShares.WebDav()); onChanged(); onDismiss() }) else null,
        onDismiss = onDismiss,
    )
}

/** Settings > Local music > SMB share. */
@Composable
internal fun DesktopSmbDialog(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val saved = remember { DesktopNetworkShares.smb() }
    fun of(values: List<String>) = DesktopNetworkShares.Smb(values[0], values[1], values[2], values[3], values[4])
    ShareDialog(
        title = "SMB share",
        intro = "A shared folder on Windows, Samba or a NAS. Its songs join Local music, each folder as an album.",
        fields = listOf(ShareField("Server", "192.168.1.20 or nas.local"), ShareField("Share", "Music"),
            ShareField("Folder (optional)", "Albums"), ShareField("User name", "User name"), ShareField("Password", "Password", secret = true)),
        initial = listOf(saved.host, saved.share, saved.folder, saved.username, saved.password),
        test = { DesktopNetworkShares.testSmb(of(it)) },
        onSave = { DesktopNetworkShares.saveSmb(of(it)); onChanged(); onDismiss() },
        onRemove = if (saved.configured) ({ DesktopNetworkShares.saveSmb(DesktopNetworkShares.Smb()); onChanged(); onDismiss() }) else null,
        onDismiss = onDismiss,
    )
}

package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.NowPlayingOut
import com.cosmotune.music.data.PrivateDns
import com.cosmotune.settings.Extras
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitMenuPill
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoAlertDialog
import kotlinx.coroutines.launch

/** The words of DNS over HTTPS and of Now playing for other apps, shared by the phone's and the desktop's settings and their search. */
object OutsideAppsText {
    const val DNS = "Private DNS"
    const val DNS_LINE = "Look up web addresses over HTTPS through a resolver you choose, so the network cannot block or change them."
    const val GROUP = "Now playing for other apps"
    const val WEBHOOK = "Webhook"
    const val WEBHOOK_LINE = "Send each song to a web address of your own (Home Assistant, a home server) as it starts, pauses or resumes."
    const val BROADCAST = "Tell other apps on this phone"
    const val BROADCAST_LINE = "Send the song with Android's standard music broadcasts, so Tasker, scrobblers and the like can follow it."
    const val FILE = "Now playing file"
    const val FILE_LINE = "Keep a text file with the song playing, for a text source in OBS or other streaming software."
}

/** Private DNS ([ExtrasSettings.privateDns]): Off, or the resolver, as a pill with the choices in its menu. */
@Composable
fun PrivateDnsRow(settings: ExtrasSettings = Extras.settings) {
    val chosen by settings.privateDns.collectAsState()
    var open by remember { mutableStateOf(false) }
    CosmoSettingsRow(
        icon = Icons.Rounded.Dns,
        title = OutsideAppsText.DNS,
        subtitle = OutsideAppsText.DNS_LINE,
        trailing = {
            OrbitMenuPill(
                text = chosen.label,
                open = open,
                onOpen = { open = true },
                onDismiss = { open = false },
                items = PrivateDns.entries.map { choice ->
                    OrbitMenuItem(choice.label, selected = choice == chosen) { settings.setPrivateDns(choice); open = false }
                },
                modifier = Modifier.padding(start = 8.dp).testTag("settings:private-dns"),
            )
        },
    )
}

/** The webhook ([ExtrasSettings.nowPlayingWebhook]): Off or On as a pill; a tap opens the address with Test, Save and Remove. */
@Composable
fun NowPlayingWebhookRow(settings: ExtrasSettings = Extras.settings) {
    val address by settings.nowPlayingWebhook.collectAsState()
    var editing by remember { mutableStateOf(false) }
    CosmoSettingsRow(
        icon = Icons.Rounded.Webhook,
        title = OutsideAppsText.WEBHOOK,
        subtitle = OutsideAppsText.WEBHOOK_LINE,
        onClick = { editing = true },
        trailing = {
            OrbitPillButton(if (address.isEmpty()) "Set up" else "On", onClick = { editing = true }, fonts = orbitFonts(), look = orbitLook(),
                modifier = Modifier.padding(start = 8.dp).testTag("settings:webhook"), height = 36.dp)
        },
    )
    if (editing) WebhookDialog(address, onDismiss = { editing = false }) { settings.setNowPlayingWebhook(it); editing = false }
}

@Composable
private fun WebhookDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current) }
    var note by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val valid = NowPlayingOut.validWebhook(text)
    CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = OutsideAppsText.WEBHOOK,
        message = "Cosmotune sends the song's title, artist, album, whether it plays, and a link, as JSON. Only to this address.",
        confirmLabel = "Save",
        confirmEnabled = valid || text.isBlank(),
        onConfirm = { onSave(if (text.isBlank()) "" else text) },
        dismissLabel = "Cancel",
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OrbitTextField(text, { text = it.take(2_000); note = null }, "https://…", fonts, look, Modifier.fillMaxWidth().testTag("settings:webhook-field"),
                error = text.isNotBlank() && !valid)
            if (text.isNotBlank() && !valid) Text("Type a web address that starts with http:// or https://.", style = fonts.body, color = look.dim)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitPillButton(if (testing) "Sending…" else "Send a test", onClick = {
                    testing = true
                    scope.launch {
                        note = if (NowPlayingOut.test(text)) "It answered: the webhook works." else "No answer from that address."
                        testing = false
                    }
                }, fonts = fonts, look = look, enabled = valid && !testing)
                if (current.isNotEmpty()) OrbitPillButton("Remove", onClick = { onSave("") }, fonts = fonts, look = look)
            }
            note?.let { Text(it, style = fonts.body, color = look.dim) }
        }
    }
}

/** The phone's music broadcasts ([ExtrasSettings.nowPlayingBroadcast]); off until turned on. */
@Composable
fun NowPlayingBroadcastRow(settings: ExtrasSettings = Extras.settings) {
    val on by settings.nowPlayingBroadcast.collectAsState()
    CosmoSettingsRow(
        icon = Icons.Rounded.Podcasts,
        title = OutsideAppsText.BROADCAST,
        subtitle = OutsideAppsText.BROADCAST_LINE,
        onClick = { settings.setNowPlayingBroadcast(!on) },
        trailing = { CosmoSwitch(checked = on, onCheckedChange = settings::setNowPlayingBroadcast) },
    )
}

/** The desktop's now-playing file ([ExtrasSettings.nowPlayingFile]) at [path]; off until turned on. */
@Composable
fun NowPlayingFileRow(path: String, settings: ExtrasSettings = Extras.settings) {
    val on by settings.nowPlayingFile.collectAsState()
    CosmoSettingsRow(
        icon = Icons.Rounded.Description,
        title = OutsideAppsText.FILE,
        subtitle = if (on) "${OutsideAppsText.FILE_LINE} It is $path" else OutsideAppsText.FILE_LINE,
        onClick = { settings.setNowPlayingFile(!on) },
        trailing = { CosmoSwitch(checked = on, onCheckedChange = settings::setNowPlayingFile) },
    )
}

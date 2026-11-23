package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioFollowLock
import com.cosmotune.podio.PodioSaved
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoAlertDialog

/** The PIN asked for before a new show is followed, or an OPML file imported, while following is locked. */
@Composable internal fun FollowLockDialog(m: PodioModel) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val show = m.lockedFollow
    fun submit() { if (m.unlockFollow(pin)) pin = "" else { wrong = true; pin = "" } }
    CosmoAlertDialog(onDismissRequest = m::cancelLockedFollow, title = "Enter the PIN",
        message = if (show?.title.isNullOrBlank()) "Following new shows is locked on this device." else "Following new shows is locked. Enter the PIN to follow ${show?.title}.",
        content = {
            Column(Modifier.testTag("podio:follow-lock"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitTextField(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, "PIN", fonts, look, Modifier.fillMaxWidth(),
                    error = wrong, secret = true, onDone = ::submit)
                if (wrong) Text("That PIN isn't right.", style = fonts.body.copy(fontSize = 13.sp), color = look.accent)
            }
        },
        confirmLabel = "Follow", confirmEnabled = PodioFollowLock.valid(pin), onConfirm = ::submit, dismissLabel = "Cancel")
}

/** Settings › Podio › Following lock: a PIN that following new shows asks for (letting shows go never does). */
@Composable internal fun ColumnScope.PodioFollowLockSettings(saved: PodioSaved, save: ((PodioSaved) -> PodioSaved) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var setting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    OrbitLabel("Following lock", fonts, look.dim, Modifier.padding(top = 8.dp))
    OrbitSwitchRow("Ask for a PIN to follow new shows", saved.followLock != null, { on -> if (on) setting = true else clearing = true },
        detail = "For a shared or a child's device; shows already followed stay", plain = true,
        switchModifier = Modifier.testTag("settings:podio:follow-lock"))
    if (setting || clearing) {
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        fun done() {
            if (setting) { val lock = PodioFollowLock.lock(pin); save { it.copy(followLock = lock) }; setting = false }
            else if (PodioFollowLock.matches(saved.followLock, pin)) { save { it.copy(followLock = null) }; clearing = false }
            else { wrong = true; pin = "" }
        }
        CosmoAlertDialog(onDismissRequest = { setting = false; clearing = false }, title = if (setting) "Choose a PIN" else "Enter the PIN",
            message = if (setting) "4 to 8 digits. Following a new show, or importing shows, will ask for it." else "Enter the PIN to turn the lock off.",
            content = {
                OrbitTextField(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, "PIN", fonts, look, Modifier.fillMaxWidth(),
                    error = wrong, secret = true, onDone = { if (PodioFollowLock.valid(pin)) done() })
                if (wrong) Text("That PIN isn't right.", style = fonts.body.copy(fontSize = 13.sp), color = look.accent)
            },
            confirmLabel = if (setting) "Lock" else "Unlock", confirmEnabled = PodioFollowLock.valid(pin), onConfirm = ::done, dismissLabel = "Cancel")
    }
}

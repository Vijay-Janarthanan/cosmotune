package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.orbit.*

/**
 * Adds a station of the listener's own (its stream, or an M3U or PLS playlist that names it), or changes a saved one: its name, its
 * address and its picture. [editing] is the saved station being changed; null adds one.
 */
@Composable internal fun StationDialog(m: PodioModel, editing: RadioStation?, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var name by remember(editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
    var address by remember(editing?.id) { mutableStateOf(editing?.url.orEmpty()) }
    var picture by remember(editing?.id) { mutableStateOf(editing?.artwork.orEmpty()) }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss, title = if (editing == null) "Add a station" else "Edit station",
        content = {
            Column(Modifier.testTag("podio:station-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitInputField(name, { name = it.take(120) }, "Name", modifier = Modifier.fillMaxWidth().testTag("podio:station-dialog:name"))
                OrbitInputField(address, { address = it.trim() }, "Stream address", modifier = Modifier.fillMaxWidth().testTag("podio:station-dialog:address"))
                Text("A stream, or an M3U or PLS playlist", style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim)
                OrbitInputField(picture, { picture = it.trim() }, "Picture address (optional)", modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                m.stationDialogError?.let { Text(it, style = fonts.body, color = look.accent) }
            }
        },
        confirmLabel = if (editing == null) "Add station" else "Save", confirmEnabled = address.isNotBlank() && !m.savingStation, busy = m.savingStation,
        onConfirm = { m.saveStation(name, address, picture, editing, onDismiss) }, dismissLabel = "Cancel", onDismiss = onDismiss,
    )
}

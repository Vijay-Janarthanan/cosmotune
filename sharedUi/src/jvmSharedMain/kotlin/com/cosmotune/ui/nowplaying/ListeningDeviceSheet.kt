package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cosmotune.audio.*
import com.cosmotune.music.playback.manualCurve
import com.cosmotune.music.playback.withFullHeadroom

/** One device-settings surface on phone and desktop; platform hosts own routing and EQ application. */
@Composable
fun ListeningDeviceSheet(facts: ListeningDeviceFacts, store: ListeningDeviceProfiles,
    captureEq: () -> ListeningEq, applyEq: (ListeningEq) -> Unit, onOpenSystem: (() -> Unit)?,
    onOpenGuide: (String) -> Unit, onDismiss: () -> Unit) {
    val profiles by store.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.routeKey == facts.routeKey }
    var model by remember(facts.routeKey) { mutableStateOf(profile?.knownModel ?: ListeningDeviceModel.CUSTOM) }
    var modelsOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var measurement by remember { mutableStateOf("") }
    var graphic by remember { mutableStateOf("") }
    var preview by remember(facts.routeKey, model) { mutableStateOf<Pair<ListeningEq, DeviceEqMeasurement>?>(null) }
    fun persist(value: ListeningDeviceProfile, apply: Boolean = false) {
        try { store.save(value); if (apply && facts.tuningAvailable) applyEq(value.appliedEq()); error = null }
        catch (_: Exception) { error = "The device profile could not be saved. Your current tuning has not been changed." }
    }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        overline = "Listening device",
        title = facts.name,
        message = facts.identityDetail,
        dismissLabel = "Done",
        onDismiss = onDismiss,
        maxWidth = 520.dp,
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Reported by the output", style = MaterialTheme.typography.labelLarge)
                if (facts.measured.isEmpty()) Text("No active output measurements are available yet.", style = MaterialTheme.typography.bodyMedium)
                facts.measured.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Unavailable: ${facts.unavailable}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text("Platform spatial audio", style = MaterialTheme.typography.labelLarge)
                Text(facts.spatial, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("Your device profile", style = MaterialTheme.typography.titleSmall)
                Text("Confirm the model yourself. Specifications below describe the product, not its current connection.", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { modelsOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(model.label) }
                    DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                        ListeningDeviceModel.entries.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.label) }, onClick = {
                            model = candidate; modelsOpen = false
                        }) }
                    }
                }
                Text(model.advertised, style = MaterialTheme.typography.bodyMedium)
                Text(model.setup, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (model.guide.isNotBlank()) TextButton(onClick = { onOpenGuide(model.guide) }) { Text("Manufacturer setup guide") }
                onOpenSystem?.let { open -> OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Open system sound / Bluetooth settings") } }
                if (!facts.tuningAvailable) Text("Tuning is bypassed by the current transport. Profiles can be saved, but applying one will not alter DoP or an encoded passthrough stream.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = facts.routeKey != null, onClick = {
                    facts.routeKey?.let { key -> persist(ListeningDeviceProfile(key, facts.name.take(120), model.name,
                        captureEq(), restoreOnConnect = profile?.restoreOnConnect ?: false, bypass = false)) }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (profile == null) "Save current EQ for this device" else "Update model and saved EQ") }
                if (profile != null) {
                    DeviceToggle("Restore saved EQ on reconnect", "Off by default. Applies only to this confirmed route.", profile.restoreOnConnect) {
                        persist(profile.copy(restoreOnConnect = it))
                    }
                    DeviceToggle("Flat EQ bypass", "Keeps the saved curve; bypasses its EQ and channel balance.", profile.bypass, facts.tuningAvailable) {
                        persist(profile.copy(bypass = it), apply = true)
                    }
                    OutlinedButton(enabled = facts.tuningAvailable, onClick = { applyEq(profile.appliedEq()) }, modifier = Modifier.fillMaxWidth()) { Text("Apply saved tuning now") }
                    Text("Saved balance: ${profile.eq.balance}; ${if (profile.eq.fullHeadroom) "full" else "standard"} headroom. Edit tuning and balance in Equalizer, then update this profile. Spatial audio is separate and is never enabled by a profile.", style = MaterialTheme.typography.bodySmall)
                    profile.measurement?.let { provenance -> Text("User-provided AutoEq source: ${provenance.measurement}. Seven-band approximation, not exact parametric correction.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onOpenGuide(provenance.sourceUrl) }) { Text("View recorded measurement source") } }
                    TextButton(onClick = { try { store.remove(profile.routeKey); error = null } catch (_: Exception) { error = "The profile could not be removed." } }) { Text("Forget this device profile") }
                }
                if (model in setOf(ListeningDeviceModel.SONY_WH1000XM5, ListeningDeviceModel.SAMSUNG_BUDS4_PRO)) {
                    TextButton(enabled = facts.routeKey != null, onClick = { importOpen = !importOpen }) { Text("Import a matching AutoEq GraphicEQ") }
                    if (importOpen) {
                        Text("Provide the exact model's measurement and source yourself. Imported points are approximated at this app's seven fixed bands and Q; narrow filters cannot be reproduced exactly. Compare at a comfortable volume. A source URL records provenance but does not verify that pasted values came from it.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(source, { source = it.take(1024); preview = null }, label = { Text("AutoEq GraphicEQ.txt source URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(measurement, { measurement = it.take(160); preview = null }, label = { Text("Measurement source / rig / conditions") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(graphic, { graphic = it.take(16_384); preview = null }, label = { Text("GraphicEQ: frequency gain; …") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            try { preview = importAutoEqGraphic(model, source, measurement, graphic, captureEq()); error = null }
                            catch (failure: Exception) { preview = null; error = failure.message ?: "The curve could not be validated." }
                        }) { Text("Validate and preview approximation") }
                        preview?.let { (eq, provenance) ->
                            EqualizerResponse(manualCurve(eq.bands).withFullHeadroom(), 48_000)
                            Text("Preview only · output is unchanged until you apply. Full headroom is enabled; loudness matching is not automatic.", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = facts.tuningAvailable && facts.routeKey != null, onClick = {
                                facts.routeKey?.let { key -> persist(ListeningDeviceProfile(key, facts.name.take(120), model.name,
                                    eq, profile?.restoreOnConnect ?: false, measurement = provenance), apply = true); preview = null }
                            }) { Text("Save and apply seven-band approximation") }
                        }
                    }
                }
                Text("No measurements or profiles are downloaded when a device connects. ANC, Bluetooth codec selection and headset firmware remain in system/vendor apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
    }
}

@Composable
private fun DeviceToggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

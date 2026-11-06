package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.audio.*
import com.cosmotune.music.playback.manualCurve
import com.cosmotune.music.playback.withFullHeadroom
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * One device-settings surface on phone and desktop; platform hosts own routing and EQ application. An Orbit dialog: the mono
 * overlines over each group, the model picked from the Orbit menu, the kit's quiet pills for every action inside (Done is the one
 * peach action), the Orbit switch and the Orbit field.
 */
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
            val fonts = orbitFonts()
            val look = orbitLook()
            val body = fonts.body
            val small = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
            val wide = Modifier.fillMaxWidth()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitLabel("Reported by the output", fonts, look.dim)
                if (facts.measured.isEmpty()) Text("No active output measurements are available yet.", style = body, color = look.ink)
                facts.measured.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(label, Modifier.weight(1f), style = body, color = look.dim)
                        Text(value, Modifier.weight(1f), style = body, color = look.ink)
                    }
                }
                Text("Unavailable: ${facts.unavailable}", style = small, color = look.dim)
                Spacer(Modifier.height(2.dp))
                OrbitLabel("Platform spatial audio", fonts, look.dim)
                Text(facts.spatial, style = body, color = look.ink)
                Spacer(Modifier.height(2.dp))
                Text("Your device profile", style = fonts.title, color = look.ink)
                Text("Confirm the model yourself. Specifications below describe the product, not its current connection.", style = small, color = look.dim)
                Box {
                    OrbitSheetButton(model.label, { modelsOpen = true }, wide, icon = CosmoIcons.ChevronDown)
                    OrbitMenu(
                        open = modelsOpen,
                        onDismiss = { modelsOpen = false },
                        items = ListeningDeviceModel.entries.map { candidate ->
                            OrbitMenuItem(candidate.label, selected = candidate == model) { model = candidate }
                        },
                        fonts = fonts,
                        look = look,
                        width = 300.dp,
                    )
                }
                Text(model.advertised, style = body, color = look.ink)
                Text(model.setup, style = small, color = look.dim)
                if (model.guide.isNotBlank()) OrbitSheetButton("Manufacturer setup guide", { onOpenGuide(model.guide) })
                onOpenSystem?.let { open -> OrbitSheetButton("Open system sound / Bluetooth settings", open, wide, icon = CosmoIcons.Settings) }
                if (!facts.tuningAvailable) Text("Tuning is bypassed by the current transport. Profiles can be saved, but applying one will not alter DoP or an encoded passthrough stream.", style = small, color = look.dim)
                OrbitSheetButton(
                    if (profile == null) "Save current EQ for this device" else "Update model and saved EQ",
                    {
                        facts.routeKey?.let { key -> persist(ListeningDeviceProfile(key, facts.name.take(120), model.name,
                            captureEq(), restoreOnConnect = profile?.restoreOnConnect ?: false, bypass = false)) }
                    },
                    wide,
                    enabled = facts.routeKey != null,
                    icon = CosmoIcons.Equaliser,
                )
                if (profile != null) {
                    DeviceToggle("Restore saved EQ on reconnect", "Off by default. Applies only to this confirmed route.", profile.restoreOnConnect, fonts, look) {
                        persist(profile.copy(restoreOnConnect = it))
                    }
                    DeviceToggle("Flat EQ bypass", "Keeps the saved curve; bypasses its EQ and channel balance.", profile.bypass, fonts, look, facts.tuningAvailable) {
                        persist(profile.copy(bypass = it), apply = true)
                    }
                    OrbitSheetButton("Apply saved tuning now", { applyEq(profile.appliedEq()) }, wide, enabled = facts.tuningAvailable)
                    Text("Saved balance: ${profile.eq.balance}; ${if (profile.eq.fullHeadroom) "full" else "standard"} headroom. Edit tuning and balance in Equalizer, then update this profile. Spatial audio is separate and is never enabled by a profile.", style = small, color = look.dim)
                    profile.measurement?.let { provenance -> Text("User-provided AutoEq source: ${provenance.measurement}. Seven-band approximation, not exact parametric correction.", style = small, color = look.dim)
                        OrbitSheetButton("View recorded measurement source", { onOpenGuide(provenance.sourceUrl) }) }
                    OrbitSheetButton("Forget this device profile", { try { store.remove(profile.routeKey); error = null } catch (_: Exception) { error = "The profile could not be removed." } })
                }
                if (model in setOf(ListeningDeviceModel.SONY_WH1000XM5, ListeningDeviceModel.SAMSUNG_BUDS4_PRO)) {
                    OrbitSheetButton("Import a matching AutoEq GraphicEQ", { importOpen = !importOpen }, enabled = facts.routeKey != null)
                    if (importOpen) {
                        Text("Provide the exact model's measurement and source yourself. Imported points are approximated at this app's seven fixed bands and Q; narrow filters cannot be reproduced exactly. Compare at a comfortable volume. A source URL records provenance but does not verify that pasted values came from it.", style = small, color = look.dim)
                        OrbitTextField(source, { source = it.take(1024); preview = null }, "AutoEq GraphicEQ.txt source URL", fonts, look, wide)
                        OrbitTextField(measurement, { measurement = it.take(160); preview = null }, "Measurement source / rig / conditions", fonts, look, wide)
                        OrbitTextField(graphic, { graphic = it.take(16_384); preview = null }, "GraphicEQ: frequency gain; …", fonts, look, wide, minLines = 3, maxLines = 5)
                        OrbitSheetButton("Validate and preview approximation", {
                            try { preview = importAutoEqGraphic(model, source, measurement, graphic, captureEq()); error = null }
                            catch (failure: Exception) { preview = null; error = failure.message ?: "The curve could not be validated." }
                        })
                        preview?.let { (eq, provenance) ->
                            EqualizerResponse(manualCurve(eq.bands).withFullHeadroom(), 48_000)
                            Text("Preview only · output is unchanged until you apply. Full headroom is enabled; loudness matching is not automatic.", style = small, color = look.dim)
                            OrbitSheetButton("Save and apply seven-band approximation", {
                                facts.routeKey?.let { key -> persist(ListeningDeviceProfile(key, facts.name.take(120), model.name,
                                    eq, profile?.restoreOnConnect ?: false, measurement = provenance), apply = true); preview = null }
                            }, enabled = facts.tuningAvailable && facts.routeKey != null)
                        }
                    }
                }
                Text("No measurements or profiles are downloaded when a device connects. ANC, Bluetooth codec selection and headset firmware remain in system/vendor apps.", style = small, color = look.dim)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = small) }
            }
    }
}

/** A setting of the profile: its title and line, and the Orbit switch at the end; the whole row is the one toggle. */
@Composable
private fun DeviceToggle(title: String, detail: String, checked: Boolean, fonts: OrbitFonts, look: OrbitLook, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.45f).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.title, color = look.ink)
            Text(detail, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = look.dim)
        }
        // The row is the toggle for a screen reader; the switch is what it looks like, and a tap on it does the same.
        Box(Modifier.clearAndSetSemantics {}) { OrbitSwitch(checked, onChange, look, title, enabled = enabled) }
    }
}

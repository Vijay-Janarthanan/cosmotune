package com.cosmotune.ui.providers

import com.cosmotune.providers.statusAgainst
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.providers.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBackHeader
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitCheckRow
import com.cosmotune.ui.orbit.OrbitErrorText
import com.cosmotune.ui.orbit.OrbitFormCard
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** The Sources entry that opens [ProviderPackagesDialog]: a quiet row with its icon tile, the name, what it is for and what is installed. */
@Composable
fun ProviderPackagesEntry(onPlay: ((List<com.cosmotune.music.data.model.Song>, Int) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val hub by ProviderHub.state.collectAsState()
    val manager = hub.manager
    val state = manager?.store?.state?.collectAsState()?.value
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(26.dp)).background(look.row)
        .clickable(role = Role.Button) { open = true }.testTag("providers:entry").padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(look.accent), contentAlignment = Alignment.Center) {
            Icon(CosmoIcons.Grid, contentDescription = null, tint = if (look.dark) androidx.compose.ui.graphics.Color(0xFF2A100A) else androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Provider packages", style = fonts.title.copy(fontSize = 14.5.sp), color = look.ink)
            Text("Discover music providers and manage their connections", style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim)
            state?.installed?.takeIf { it.isNotEmpty() }?.let { installed ->
                OrbitLabel("${installed.count { it.enabled }} enabled · ${installed.size} installed on this device", fonts, look.dim, Modifier.padding(top = 4.dp))
            }
        }
        Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.size(20.dp))
    }
    if (open) ProviderPackagesDialog(onDismiss = { open = false }, onPlay = onPlay)
}

/**
 * A provider page in its own window, Orbit's way: the page's ground, at most [maxWidth] wide (the window dims the rest on a wide
 * screen), the round back button and the mono [kind] at its head, then [content]. The words inside read in Orbit's body and ink.
 */
@Composable
internal fun ProviderPageWindow(onDismiss: () -> Unit, kind: String, tag: String, modifier: Modifier = Modifier, maxWidth: androidx.compose.ui.unit.Dp = 920.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides look.ink, LocalTextStyle provides fonts.body) {
                Column(modifier.fillMaxHeight().widthIn(max = maxWidth).fillMaxWidth().background(look.ground)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp).testTag(tag)) {
                    OrbitBackHeader(kind, onDismiss, Modifier.padding(top = 8.dp), trailing = trailing)
                    content()
                }
            }
        }
    }
}

/** A provider page's title, in the wide face. */
@Composable
internal fun ProviderPageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = orbitFonts().heroTitle, color = orbitLook().ink, maxLines = 2, modifier = modifier.padding(top = 4.dp, bottom = 6.dp).semantics { heading() })
}

/** A provider page's section heading. */
@Composable
internal fun ProviderSection(text: String, modifier: Modifier = Modifier) {
    Text(text, style = orbitFonts().section.copy(fontSize = 17.sp, lineHeight = 21.sp), color = orbitLook().ink, modifier = modifier.semantics { heading() })
}

/** Small print in the dim ink (a version, a line under a name). */
@Composable
internal fun ProviderNote(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, style = orbitFonts().body.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = orbitLook().dim, maxLines = maxLines, modifier = modifier)
}

/** A provider page's or dialog's button: the quiet chip's pill (40 dp), or the peach one when [primary]. */
@Composable
internal fun ProviderButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    OrbitPillButton(text, onClick, orbitFonts(), orbitLook(), modifier, primary = primary, enabled = enabled, icon = icon, height = 40.dp)
}

@Composable
fun ProviderPackagesDialog(onDismiss: () -> Unit, manager: ProviderManager? = null,
    onPlay: ((List<com.cosmotune.music.data.model.Song>, Int) -> Unit)? = null) {
    val hub by ProviderHub.state.collectAsState()
    val ready = manager ?: hub.manager
    ProviderPageWindow(onDismiss, kind = "Sources", tag = "providers:window") {
        if (ready == null) Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderPageTitle("Provider packages")
            if (hub.loading) OrbitBusyRing()
            else {
                Text(hub.error ?: "Provider storage is unavailable")
                ProviderButton("Retry", ProviderHub::retry)
            }
        } else key(ready) { ProviderPackageContent(ready, onPlay) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ProviderPackageContent(manager: ProviderManager,
    onPlay: ((List<com.cosmotune.music.data.model.Song>, Int) -> Unit)?) {
    val state by manager.store.state.collectAsState()
    val failures by manager.errors.collectAsState()
    val operations by manager.progress.collectAsState()
    val serviceReports by manager.serviceHealth.reports.collectAsState()
    val scope = rememberCoroutineScope()
    var work by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf(manager.store.recoveryMessage) }
    var review by remember { mutableStateOf<ProviderPackageReview?>(null) }
    var editing by remember { mutableStateOf<ProviderInstall?>(null) }
    var removing by remember { mutableStateOf<ProviderInstall?>(null) }
    var browsing by remember { mutableStateOf<ProviderInstall?>(null) }
    var details by remember { mutableStateOf<ProviderInstall?>(null) }
    var address by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf<ProviderRepositoryIndex?>(null) }
    var repositoryAddress by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val busy = work != null
    fun run(action: suspend () -> Unit) {
        if (work != null) return
        work = scope.launch(start = CoroutineStart.LAZY) {
            error = null
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "The provider action could not be completed. Check the package, connection and available storage." }
            finally { work = null }
        }
        work?.start()
    }
    val pick = rememberProviderPackageImport { _, open -> run {
        repositoryAddress = null
        review = manager.inspect(open)
    } }
    val heldReview = review
    DisposableEffect(heldReview) { onDispose { heldReview?.let(manager::discard) } }
    val fonts = orbitFonts()
    val look = orbitLook()

    Column(Modifier.weight(1f).fillMaxWidth().testTag("providers:root")) {
        ProviderPageTitle("Provider packages")
        Text("Packages and connections are saved on this device. A request failure does not disable a provider.", color = look.dim)
        val autoVerify by ProviderAutoVerification.autoOpen.collectAsState()
        OrbitSwitchRow("Open verification automatically", autoVerify, { ProviderAutoVerification.setAutoOpen(it) }, Modifier.padding(top = 14.dp),
            detail = "When a search or song needs a provider's human check, its page opens once, in front, and closes by itself when done.",
            switchModifier = Modifier.testTag("providers:auto-verify"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            OrbitPillButton("Browse Mornye providers", enabled = !busy, onClick = {
                val url = "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/refs/heads/main/registry.json"
                repositoryAddress = url
                state.repositories.find { it.address == url }?.index?.let { repository = it }
                run { repository = manager.refreshRepository(url) }
            }, fonts = fonts, look = look, primary = true, icon = CosmoIcons.Explore, modifier = Modifier.testTag("providers:discover"))
            OrbitPillButton("Import package", pick, fonts, look, enabled = !busy)
            if (repository != null) OrbitPillButton("Installed providers", { repository = null }, fonts, look)
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            OrbitBusyRing()
            ProviderButton("Cancel", { work?.cancel() })
        }
        error?.let { message -> OrbitErrorText(message, Modifier.padding(vertical = 8.dp)) }
        val index = repository
        if (index == null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.installed.isEmpty()) item {
                    OrbitFormCard(gap = 8.dp) {
                        Text("Add your first provider", style = fonts.title, color = look.ink)
                        Text("Browse the Mornye repository above, review a package, then install it. Packages installed on another device are not added here automatically.",
                            color = look.dim)
                    }
                }
                items(state.installed, key = { it.manifest.name }) { installed ->
                    OrbitFormCard(gap = 8.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProviderPackageAvatar(installed.manifest.title, installed, state.repositories.firstNotNullOfOrNull { saved ->
                                saved.index?.extensions?.firstOrNull { it.identity == installed.manifest.name }?.iconUrl
                            })
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(installed.manifest.title, style = fonts.title, color = look.ink)
                                OrbitLabel(installed.manifest.version, fonts, look.dim, Modifier.padding(vertical = 3.dp))
                                Text(when (providerAccessState(installed, state.auth[installed.manifest.name])) {
                                    ProviderAccessState.DISABLED -> "Disabled · saved connection retained"
                                    ProviderAccessState.CONNECTED -> "Enabled · connected"
                                    ProviderAccessState.RENEWAL_REQUIRED -> "Enabled · session renewal needed"
                                    ProviderAccessState.VERIFICATION_REQUIRED -> "Enabled · verification required"
                                    ProviderAccessState.CONFIGURATION_CHANGED -> "Enabled · connection update needed"
                                    ProviderAccessState.READY -> "Enabled · ready"
                                }, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
                                    modifier = Modifier.testTag("provider:${installed.manifest.name}:access"))
                            }
                            Spacer(Modifier.width(12.dp))
                            OrbitSwitch(installed.enabled, { enabled -> run { manager.setEnabled(installed.manifest.name, enabled) } }, look,
                                "Enable ${installed.manifest.title}", Modifier.testTag("provider:${installed.manifest.name}:enabled"), enabled = !busy)
                        }
                        if (installed.manifest.description.isNotBlank()) ProviderNote(installed.manifest.description, maxLines = 3)
                        failures[installed.manifest.name]?.let { OrbitErrorText(it) }
                        operations[installed.manifest.name]?.let { operation ->
                            ProviderNote(operation.status)
                            val fraction = operation.fraction
                            if (fraction == null) OrbitBusyRing()
                            else OrbitProgressLine(progress = { fraction }, ink = look.accent, moon = 4.dp)
                        }
                        ProviderAuthenticationEntry(manager, installed)
                        serviceReports[installed.manifest.name]?.takeIf { it.packageHash == installed.sha256 }?.let { report ->
                            ProviderServiceReportView(report, Modifier.padding(vertical = 4.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            ProviderButton("Details", { details = installed }, enabled = !busy)
                            if (installed.manifest.serviceHealth.isNotEmpty()) ProviderButton("Check services", { run {
                                manager.serviceHealth.check(installed, force = true)
                            } }, enabled = !busy && installed.enabled, modifier = Modifier.testTag("provider:${installed.manifest.name}:health"))
                            if (installed.enabled && "metadata_provider" in installed.manifest.type) ProviderButton("Browse",
                                { browsing = installed }, enabled = !busy, icon = CosmoIcons.Explore, modifier = Modifier.testTag("provider:${installed.manifest.name}:browse"))
                            ProviderButton("Settings", { editing = installed }, enabled = !busy, modifier = Modifier.testTag("provider:${installed.manifest.name}:settings"))
                            ProviderButton("Remove", { removing = installed }, enabled = !busy)
                        }
                    }
                }
                item {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProviderSection("Repositories")
                        OrbitFormField(address, { address = it }, "HTTPS repository address")
                        ProviderButton("Load repository", enabled = !busy && address.isNotBlank(), onClick = { run {
                            val url = address.trim(); repository = manager.refreshRepository(url); repositoryAddress = url
                        } })
                    }
                }
                items(state.repositories, key = { it.address }) { saved ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProviderButton(saved.title, enabled = !busy, onClick = { repository = saved.index; repositoryAddress = saved.address })
                        ProviderButton("Refresh", enabled = !busy, onClick = { run {
                            repository = manager.refreshRepository(saved.address); repositoryAddress = saved.address
                        } })
                        ProviderButton("Remove repository", enabled = !busy, onClick = { run {
                            manager.store.update { old -> old.copy(repositories = old.repositories.filterNot { it.address == saved.address }) }
                        } })
                    }
                }
            }
        } else {
            OrbitFormField(query, { query = it }, "Find a provider")
            val filtered = index.extensions.filter { it.identity.contains(query, true) || it.displayName.contains(query, true) }
            OrbitLabel("${filtered.size} packages", fonts, look.dim, Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.identity }) { item ->
                    OrbitFormCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProviderPackageAvatar(item.displayName.ifBlank { item.identity }, state.installed.find { it.manifest.name == item.identity }, item.iconUrl)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName.ifBlank { item.identity }, style = fonts.title, color = look.ink)
                                OrbitLabel(item.version, fonts, look.dim, Modifier.padding(vertical = 3.dp))
                                ProviderNote(item.description, maxLines = 3)
                            }
                            Spacer(Modifier.width(12.dp))
                            val status = item.statusAgainst(state.installed.find { it.manifest.name == item.identity })
                            ProviderButton(when (status) {
                                com.cosmotune.providers.ProviderListingStatus.NEW -> "Download"
                                com.cosmotune.providers.ProviderListingStatus.UPDATE -> "Review update"
                                com.cosmotune.providers.ProviderListingStatus.CURRENT -> "Installed"
                                com.cosmotune.providers.ProviderListingStatus.OLDER -> "Older than installed"
                            }, enabled = !busy && status != com.cosmotune.providers.ProviderListingStatus.CURRENT &&
                                status != com.cosmotune.providers.ProviderListingStatus.OLDER,
                                onClick = { run { review = manager.repositories.download(item) } })
                        }
                    }
                }
            }
        }
    }
    details?.let { installed -> ProviderPackageDetails(installed,
        serviceReports[installed.manifest.name]?.takeIf { it.packageHash == installed.sha256 }, onDismiss = { details = null }) }
    review?.let { candidate -> ProviderPackageTrust(candidate, busy,
        artworkDefault = state.installed.find { it.manifest.name == candidate.manifest.name }?.allowArtwork ?: true,
        onDismiss = { review = null }, onTrust = { artwork -> run {
        manager.trust(candidate, repositoryAddress, allowArtwork = artwork); review = null
    } }) }
    editing?.let { installed -> ProviderSettingsEditor(manager, installed, busy, error = error, run = ::run, onDismiss = { editing = null }) }
    browsing?.let { installed -> ProviderBrowserDialog(manager, installed, onDismiss = { browsing = null }, onPlay = onPlay) }
    removing?.let { installed -> com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = { removing = null },
        title = "Remove ${installed.manifest.title}?",
        message = "Its package and saved connection settings will be removed. Songs and playlists in your library stay in place.",
        confirmLabel = "Remove",
        onConfirm = { run { manager.remove(installed.manifest.name); removing = null } },
        confirmEnabled = !busy,
        dismissLabel = "Cancel",
        onDismiss = { removing = null },
    ) }
}

@Composable
private fun ProviderPackageTrust(review: ProviderPackageReview, busy: Boolean, artworkDefault: Boolean, onDismiss: () -> Unit, onTrust: (Boolean) -> Unit) {
    var trusted by remember(review.sha256) { mutableStateOf(false) }
    var artwork by remember(review.sha256) { mutableStateOf(artworkDefault) }
    val fonts = orbitFonts()
    val look = orbitLook()
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "Review ${review.manifest.title}",
        scroll = false,
        content = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OrbitLabel("${review.manifest.name} · ${review.manifest.version}", fonts, look.dim) }
            item { SelectionContainer { Text("SHA-256: ${review.sha256}", style = fonts.label.copy(letterSpacing = 0.sp), color = look.dim) } }
            item { Text(review.manifest.description) }
            item { Text("Requested network access", style = fonts.title, color = look.ink, modifier = Modifier.padding(top = 6.dp)) }
            item { Text(review.manifest.permissions.network.joinToString("\n").ifBlank { "None" }, color = look.dim) }
            item { Text("Storage: ${if (review.manifest.permissions.storage) "requested" else "none"} · Files: ${if (review.manifest.permissions.file) "requested" else "none"}") }
            item { ProviderNote("Packages run code. Only enable code from a publisher you trust.") }
            item { OrbitCheckRow("Download covers from public HTTPS addresses", artwork, { artwork = it }, enabled = !busy) }
            item { OrbitCheckRow("I trust this package", trusted, { trusted = it }, enabled = !busy) }
        }
    },
        confirmLabel = "Trust and install",
        onConfirm = { onTrust(artwork) },
        confirmEnabled = trusted && !busy,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        dismissEnabled = !busy,
    )
}

@Composable
private fun ProviderSettingsEditor(manager: ProviderManager, installed: ProviderInstall, busy: Boolean, error: String?,
    run: (suspend () -> Unit) -> Unit, onDismiss: () -> Unit) {
    var values by remember(installed.sha256) { mutableStateOf(installed.manifest.effectiveSettings(manager.store.state.value.settings[installed.manifest.name].orEmpty())) }
    var localNetwork by remember { mutableStateOf(installed.allowLocalNetwork) }
    var artwork by remember { mutableStateOf(installed.allowArtwork) }
    val valid = installed.manifest.settings.filter { it.type in setOf("number", "integer") }.all { field ->
        values[field.key] == JsonNull || (values[field.key] as? JsonPrimitive)?.doubleOrNull?.isFinite() == true
    }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = installed.manifest.title,
        scroll = false,
        content = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) item { OrbitErrorText(error) }
            items(installed.manifest.settings, key = { it.key }) { field ->
                ProviderSettingField(field, values[field.key] ?: field.default,
                    change = { next -> values = JsonObject(values + (field.key to next)) }, busy = busy,
                    onAction = { action -> run { manager.settingAction(installed.manifest.name, action.key) } })
                if (field.grantsNetwork) Text("Saving a custom URL lets this package contact that server.")
            }
            item { OrbitSwitchRow("Download covers from public HTTPS addresses", artwork, { artwork = it }, enabled = !busy) }
            item { OrbitSwitchRow("Allow local network", localNetwork, { localNetwork = it }, enabled = !busy,
                detail = "Allows this package to reach declared devices and servers on your network.") }
        }
    },
        confirmLabel = "Save",
        onConfirm = { run { manager.saveSettings(installed.manifest.name, values, localNetwork, artwork); onDismiss() } },
        confirmEnabled = valid && !busy,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
    )
}

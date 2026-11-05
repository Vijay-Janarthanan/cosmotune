package com.cosmotune.ui.providers

import com.cosmotune.providers.statusAgainst
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.providers.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun ProviderPackagesEntry(onPlay: ((List<com.cosmotune.music.data.model.Song>, Int) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val hub by ProviderHub.state.collectAsState()
    val manager = hub.manager
    val state = manager?.store?.state?.collectAsState()?.value
    OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        .testTag("providers:entry")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Provider packages", style = MaterialTheme.typography.titleMedium)
                Text("Discover music providers and manage their connections", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state?.installed?.takeIf { it.isNotEmpty() }?.let { installed ->
                    Text("${installed.count { it.enabled }} enabled · ${installed.size} installed on this device",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
        }
    }
    if (open) ProviderPackagesDialog(onDismiss = { open = false }, onPlay = onPlay)
}

@Composable
fun ProviderPackagesDialog(onDismiss: () -> Unit, manager: ProviderManager? = null,
    onPlay: ((List<com.cosmotune.music.data.model.Song>, Int) -> Unit)? = null) {
    val hub by ProviderHub.state.collectAsState()
    val ready = manager ?: hub.manager
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxHeight().widthIn(max = 920.dp).fillMaxWidth()) {
                if (ready == null) Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                    TextButton(onClick = onDismiss) { Text("Back") }
                    Text("Provider packages", style = MaterialTheme.typography.headlineMedium)
                    if (hub.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        Text(hub.error ?: "Provider storage is unavailable")
                        TextButton(onClick = ProviderHub::retry) { Text("Retry") }
                    }
                } else key(ready) { ProviderPackageContent(ready, onDismiss, onPlay) }
            }
        }
    }
}

@Composable
private fun ProviderPackageContent(manager: ProviderManager, onDismiss: () -> Unit,
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

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp).testTag("providers:root")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Back") }
            Text("Provider packages", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        }
        Text("Packages and connections are saved on this device. A request failure does not disable a provider.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val autoVerify by ProviderAutoVerification.autoOpen.collectAsState()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Open verification automatically", style = MaterialTheme.typography.titleSmall)
                Text("When a search or song needs a provider's human check, its page opens once, in front, and closes by itself when done.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(autoVerify, { ProviderAutoVerification.setAutoOpen(it) }, modifier = Modifier.testTag("providers:auto-verify")
                .semantics { contentDescription = "Open verification automatically" })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilledTonalButton(enabled = !busy, onClick = {
                val url = "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/refs/heads/main/registry.json"
                repositoryAddress = url
                state.repositories.find { it.address == url }?.index?.let { repository = it }
                run { repository = manager.refreshRepository(url) }
            }, modifier = Modifier.testTag("providers:discover")) {
                Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse Mornye providers")
            }
            OutlinedButton(onClick = pick, enabled = !busy) { Text("Import package") }
            if (repository != null) TextButton(onClick = { repository = null }) { Text("Installed providers") }
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(Modifier.weight(1f))
            TextButton(onClick = { work?.cancel() }) { Text("Cancel") }
        }
        error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        val index = repository
        if (index == null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.installed.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add your first provider", style = MaterialTheme.typography.titleMedium)
                        Text("Browse the Mornye repository above, review a package, then install it. Packages installed on another device are not added here automatically.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.installed, key = { it.manifest.name }) { installed ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProviderPackageAvatar(installed.manifest.title, installed, state.repositories.firstNotNullOfOrNull { saved ->
                                saved.index?.extensions?.firstOrNull { it.identity == installed.manifest.name }?.iconUrl
                            })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(installed.manifest.title, style = MaterialTheme.typography.titleMedium)
                                Text(installed.manifest.version, style = MaterialTheme.typography.labelMedium)
                                Text(when (providerAccessState(installed, state.auth[installed.manifest.name])) {
                                    ProviderAccessState.DISABLED -> "Disabled · saved connection retained"
                                    ProviderAccessState.CONNECTED -> "Enabled · connected"
                                    ProviderAccessState.RENEWAL_REQUIRED -> "Enabled · session renewal needed"
                                    ProviderAccessState.VERIFICATION_REQUIRED -> "Enabled · verification required"
                                    ProviderAccessState.CONFIGURATION_CHANGED -> "Enabled · connection update needed"
                                    ProviderAccessState.READY -> "Enabled · ready"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("provider:${installed.manifest.name}:access"))
                            }
                            Switch(installed.enabled, { enabled -> run { manager.setEnabled(installed.manifest.name, enabled) } },
                                enabled = !busy, modifier = Modifier.testTag("provider:${installed.manifest.name}:enabled")
                                    .semantics { contentDescription = "Enable ${installed.manifest.title}" })
                        }
                        if (installed.manifest.description.isNotBlank()) Text(installed.manifest.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        failures[installed.manifest.name]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        operations[installed.manifest.name]?.let { operation ->
                            Text(operation.status, style = MaterialTheme.typography.bodySmall)
                            val fraction = operation.fraction
                            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        ProviderAuthenticationEntry(manager, installed)
                        serviceReports[installed.manifest.name]?.takeIf { it.packageHash == installed.sha256 }?.let { report ->
                            ProviderServiceReportView(report, Modifier.padding(vertical = 8.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { details = installed }, enabled = !busy) { Text("Details") }
                            if (installed.manifest.serviceHealth.isNotEmpty()) TextButton(onClick = { run {
                                manager.serviceHealth.check(installed, force = true)
                            } }, enabled = !busy && installed.enabled, modifier = Modifier.testTag("provider:${installed.manifest.name}:health")) { Text("Check services") }
                            if (installed.enabled && "metadata_provider" in installed.manifest.type) TextButton(
                                onClick = { browsing = installed }, enabled = !busy, modifier = Modifier.testTag("provider:${installed.manifest.name}:browse")) { Text("Browse") }
                            TextButton(onClick = { editing = installed }, enabled = !busy, modifier = Modifier.testTag("provider:${installed.manifest.name}:settings")) { Text("Settings") }
                            TextButton(onClick = { removing = installed }, enabled = !busy) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
                item {
                    Text("Repositories", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(address, { address = it }, label = { Text("HTTPS repository address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    TextButton(enabled = !busy && address.isNotBlank(), onClick = { run {
                        val url = address.trim(); repository = manager.refreshRepository(url); repositoryAddress = url
                    } }) { Text("Load repository") }
                }
                items(state.repositories, key = { it.address }) { saved ->
                    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !busy, onClick = { repository = saved.index; repositoryAddress = saved.address }) { Text(saved.title) }
                        TextButton(enabled = !busy, onClick = { run {
                            repository = manager.refreshRepository(saved.address); repositoryAddress = saved.address
                        } }) { Text("Refresh") }
                        TextButton(enabled = !busy, onClick = { run {
                            manager.store.update { old -> old.copy(repositories = old.repositories.filterNot { it.address == saved.address }) }
                        } }) { Text("Remove repository") }
                    }
                }
            }
        } else {
            OutlinedTextField(query, { query = it }, label = { Text("Find a provider") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val filtered = index.extensions.filter { it.identity.contains(query, true) || it.displayName.contains(query, true) }
            Text("${filtered.size} packages", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered, key = { it.identity }) { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderPackageAvatar(item.displayName.ifBlank { item.identity }, state.installed.find { it.manifest.name == item.identity }, item.iconUrl)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName.ifBlank { item.identity }, style = MaterialTheme.typography.titleMedium)
                            Text(item.version, style = MaterialTheme.typography.labelMedium)
                            Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val status = item.statusAgainst(state.installed.find { it.manifest.name == item.identity })
                        TextButton(enabled = !busy && status != com.cosmotune.providers.ProviderListingStatus.CURRENT &&
                            status != com.cosmotune.providers.ProviderListingStatus.OLDER,
                            onClick = { run { review = manager.repositories.download(item) } }) {
                            Text(when (status) {
                                com.cosmotune.providers.ProviderListingStatus.NEW -> "Download"
                                com.cosmotune.providers.ProviderListingStatus.UPDATE -> "Review update"
                                com.cosmotune.providers.ProviderListingStatus.CURRENT -> "Installed"
                                com.cosmotune.providers.ProviderListingStatus.OLDER -> "Older than installed"
                            })
                        }
                    }
                    HorizontalDivider()
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
    removing?.let { installed -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${installed.manifest.title}?") },
        text = { Text("Its package and saved connection settings will be removed. Songs and playlists in your library stay in place.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { run { manager.remove(installed.manifest.name); removing = null } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

@Composable
private fun ProviderPackageTrust(review: ProviderPackageReview, busy: Boolean, artworkDefault: Boolean, onDismiss: () -> Unit, onTrust: (Boolean) -> Unit) {
    var trusted by remember(review.sha256) { mutableStateOf(false) }
    var artwork by remember(review.sha256) { mutableStateOf(artworkDefault) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Review ${review.manifest.title}") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${review.manifest.name} · ${review.manifest.version}") }
            item { SelectionContainer { Text("SHA-256: ${review.sha256}", style = MaterialTheme.typography.labelSmall) } }
            item { Text(review.manifest.description) }
            item { Text("Requested network access", style = MaterialTheme.typography.titleSmall) }
            item { Text(review.manifest.permissions.network.joinToString("\n").ifBlank { "None" }) }
            item { Text("Storage: ${if (review.manifest.permissions.storage) "requested" else "none"} · Files: ${if (review.manifest.permissions.file) "requested" else "none"}") }
            item { Text("Packages run code. Only enable code from a publisher you trust.", style = MaterialTheme.typography.bodySmall) }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(artwork, { artwork = it }, enabled = !busy)
                Text("Download covers from public HTTPS addresses", modifier = Modifier.weight(1f))
            } }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(trusted, { trusted = it }, enabled = !busy)
                Text("I trust this package", modifier = Modifier.weight(1f))
            } }
        }
    }, confirmButton = { TextButton(enabled = trusted && !busy, onClick = { onTrust(artwork) }) { Text("Trust and install") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(installed.manifest.title) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
            items(installed.manifest.settings, key = { it.key }) { field ->
                ProviderSettingField(field, values[field.key] ?: field.default,
                    change = { next -> values = JsonObject(values + (field.key to next)) }, busy = busy,
                    onAction = { action -> run { manager.settingAction(installed.manifest.name, action.key) } })
            }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Download covers from public HTTPS addresses", modifier = Modifier.weight(1f))
                Switch(artwork, { artwork = it }, enabled = !busy)
            } }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow local network", style = MaterialTheme.typography.titleSmall)
                    Text("Allows this package to reach declared devices and servers on your network.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(localNetwork, { localNetwork = it }, enabled = !busy)
            } }
        }
    }, confirmButton = { TextButton(enabled = valid && !busy, onClick = { run { manager.saveSettings(installed.manifest.name, values, localNetwork, artwork); onDismiss() } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

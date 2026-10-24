package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    TextButton(onClick = { open = true }) { Text("Provider packages") }
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
    val scope = rememberCoroutineScope()
    var work by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf(manager.store.recoveryMessage) }
    var review by remember { mutableStateOf<ProviderPackageReview?>(null) }
    var editing by remember { mutableStateOf<ProviderInstall?>(null) }
    var removing by remember { mutableStateOf<ProviderInstall?>(null) }
    var browsing by remember { mutableStateOf<ProviderInstall?>(null) }
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
        Text("Add compatible packages, choose their settings and manage repositories.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilledTonalButton(onClick = pick, enabled = !busy) { Text("Import package") }
            TextButton(enabled = !busy, onClick = { run {
                val url = "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/refs/heads/main/registry.json"
                repository = manager.refreshRepository(url); repositoryAddress = url
            } }) { Text("Mornye repository") }
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
                if (state.installed.isEmpty()) item { Text("No provider packages installed.", modifier = Modifier.padding(vertical = 20.dp)) }
                items(state.installed, key = { it.manifest.name }) { installed ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(installed.manifest.title, style = MaterialTheme.typography.titleMedium)
                                Text(installed.manifest.version, style = MaterialTheme.typography.labelMedium)
                            }
                            Switch(installed.enabled, { enabled -> run { manager.setEnabled(installed.manifest.name, enabled) } },
                                enabled = !busy, modifier = Modifier.testTag("provider:${installed.manifest.name}:enabled"))
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName.ifBlank { item.identity }, style = MaterialTheme.typography.titleMedium)
                            Text(item.version, style = MaterialTheme.typography.labelMedium)
                            Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(enabled = !busy, onClick = { run { review = manager.repositories.download(item) } }) {
                            Text(if (state.installed.any { it.manifest.name == item.identity }) "Review update" else "Download")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
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

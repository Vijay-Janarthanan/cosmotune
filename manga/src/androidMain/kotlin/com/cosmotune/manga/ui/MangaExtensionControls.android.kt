package com.cosmotune.manga.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.extensions.MangaExtensions
import com.cosmotune.manga.extensions.MangaExtensionReview
import com.cosmotune.manga.extensions.MangaExtensionSettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal actual fun MangaExtensionControls(repository: MangaRepository, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(repository) { MangaExtensions(context.applicationContext, repository.store, repository.context) }
    val state by repository.store.state.collectAsState()
    var review by remember { mutableStateOf<MangaExtensionReview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var settingsId by remember { mutableStateOf<String?>(null) }
    var storePicker by remember { mutableStateOf(false) }
    var installedPicker by remember { mutableStateOf(false) }
    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        settingsId?.let(repository::invalidateSource); settingsId = null
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try { review = manager.inspect { requireNotNull(context.contentResolver.openInputStream(uri)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onError(error.message ?: "Extension APK could not be inspected") }
            finally { loading = false }
        }
    }
    DisposableEffect(Unit) { onDispose { review?.stagedFile?.delete() } }
    TextButton(enabled = !loading, onClick = { import.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) { Text("Import source extension APK") }
    TextButton(enabled = !loading, onClick = { storePicker = true }) { Text("Browse extension stores") }
    if (storePicker) MangaExtensionStorePicker(repository, manager, onDismiss = { storePicker = false }, onReview = { review = it })
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    val extensions = state.sources.filter { it.kind == "mihon" }.groupBy { it.settings["package"].orEmpty() }
    if (extensions.isNotEmpty()) TextButton(onClick = { installedPicker = true }) { Text("Installed extensions (${extensions.size})") }
    if (installedPicker) AlertDialog(onDismissRequest = { installedPicker = false }, title = { Text("Installed extensions") },
        confirmButton = { TextButton(onClick = { installedPicker = false }) { Text("Done") } }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        extensions.forEach { (packageName, configs) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${configs.first().name} · ${configs.first().settings["version"]}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { scope.launch {
                    try { manager.remove(packageName); configs.forEach { repository.invalidateSource(it.id) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onError(error.message ?: "Extension could not be removed") }
                } }) { Text("Remove") }
            }
            configs.forEach { config -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${config.name} · ${config.language}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (config.settings["configurable"] == "true") TextButton(onClick = {
                    settingsId = config.id
                    configure.launch(Intent(context, MangaExtensionSettingsActivity::class.java).putExtra("sourceId", config.id))
                }) { Text("Settings") }
                Switch(config.enabled, { enabled -> scope.launch {
                    try { repository.store.source(config.copy(enabled = enabled)); repository.invalidateSource(config.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onError(error.message ?: "Source setting could not be saved") }
                } })
            } }
        }
    } })
    review?.let { candidate ->
        var accepted by remember(candidate.sha256) { mutableStateOf(false) }
        fun dismiss() { candidate.stagedFile.delete(); review = null }
        AlertDialog(onDismissRequest = { if (!loading) dismiss() }, title = { Text("Enable this source extension?") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${candidate.name} · ${candidate.version}", style = MaterialTheme.typography.titleMedium)
                Text(candidate.packageName, style = MaterialTheme.typography.labelSmall)
                Text("Extension APKs run code with Cosmotune's permissions. Enable an APK only if you trust its publisher. Each changed APK requires a new review.")
                Text("APK SHA-256\n${candidate.sha256}", style = MaterialTheme.typography.labelSmall)
                candidate.signers.forEach { Text("Signer certificate SHA-256\n$it", style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(accepted, { accepted = it }); Text("I trust this extension publisher") }
            } },
            confirmButton = { TextButton(enabled = accepted && !loading, onClick = { scope.launch {
                loading = true
                try { val added = manager.trust(candidate); added.forEach { repository.invalidateSource(it.id) }; review = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError(error.message ?: "Extension could not be enabled"); dismiss() }
                finally { loading = false }
            } }) { Text("Trust and enable") } },
            dismissButton = { TextButton(enabled = !loading, onClick = ::dismiss) { Text("Cancel") } },
        )
    }
}

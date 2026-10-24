package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.manga.data.*
import com.cosmotune.manga.extensions.MangaExtensions
import com.cosmotune.manga.extensions.MangaExtensionReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun MangaExtensionStorePicker(repository: MangaRepository, manager: MangaExtensions,
    onDismiss: () -> Unit, onReview: (MangaExtensionReview) -> Unit) {
    val scope = rememberCoroutineScope()
    val state by repository.store.state.collectAsState()
    val stores = remember { MangaExtensionStores() }
    var address by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<MangaExtensionStoreResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    fun fetch(url: String, expected: String? = null) {
        scope.launch {
            loading = true; error = null; result = null
            try {
                val loaded = stores.fetch(url, expected ?: state.extensionStores.find { it.indexUrl == url }?.signingKey)
                val saved = state.extensionStores.find { it.indexUrl == loaded.store.indexUrl }
                require(saved == null || saved.signingKey == loaded.store.signingKey) {
                    "The store's publisher key changed. Remove the saved store before choosing a different publisher."
                }
                result = loaded
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Extension store could not be loaded" }
            finally { loading = false }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Back") }
                    Text("Extension stores", style = MaterialTheme.typography.headlineSmall)
                }
                OutlinedTextField(address, { address = it }, label = { Text("HTTPS index.pb or repo.json address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = address.isNotBlank() && !loading, onClick = { fetch(address.trim()) }) { Text("Load store") }
                    TextButton(enabled = !loading, onClick = { address = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"; fetch(address) }) { Text("Keiyoushi") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val current = result
                if (current == null) {
                    if (state.extensionStores.isEmpty() && !loading) Text("Add a publisher's extension store or load Keiyoushi. Downloaded APKs are inspected before you choose whether to enable them.")
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.extensionStores, key = { it.indexUrl }) { saved -> Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = !loading, onClick = { address = saved.indexUrl; fetch(saved.indexUrl, saved.signingKey) }, modifier = Modifier.weight(1f)) { Text(saved.name) }
                            TextButton(enabled = !loading, onClick = { scope.launch {
                                try { repository.store.update { it.copy(extensionStores = it.extensionStores.filterNot { item -> item.indexUrl == saved.indexUrl }) } }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message }
                            } }) { Text("Remove") }
                        } }
                    }
                } else {
                    Text(current.store.name, style = MaterialTheme.typography.titleLarge)
                    Text("Publisher SHA-256: ${current.store.signingKey}", style = MaterialTheme.typography.labelSmall)
                    val saved = state.extensionStores.any { it.indexUrl == current.store.indexUrl && it.signingKey == current.store.signingKey }
                    TextButton(enabled = !saved, onClick = { scope.launch {
                        try { repository.store.update { old ->
                            require(old.extensionStores.none { it.indexUrl == current.store.indexUrl && it.signingKey != current.store.signingKey }) {
                                "Remove the saved store before changing its publisher key."
                            }
                            old.copy(extensionStores = old.extensionStores.filterNot { item -> item.indexUrl == current.store.indexUrl } + current.store)
                        } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    } }) { Text(if (saved) "Store saved" else "Save store") }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(query, { query = it }, label = { Text("Find extension") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(language, { language = it }, label = { Text("Language") }, singleLine = true, modifier = Modifier.width(100.dp))
                    }
                    val filtered = current.extensions.filter { (it.name.contains(query, true) || it.packageName.contains(query, true)) &&
                        (language.isBlank() || it.languages.any { lang -> lang.contains(language, true) }) }
                    Text("${filtered.size} extensions", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.packageName }) { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${item.version} · ${item.languages.joinToString()}" + if (item.contentWarning == 3) " · 18+" else "", style = MaterialTheme.typography.labelSmall)
                                }
                                val installed = state.sources.any { it.kind == "mihon" && it.settings["package"] == item.packageName }
                                TextButton(enabled = !loading && item.api in setOf("1.4", "1.6"), onClick = { scope.launch {
                                    loading = true; error = null
                                    try { val candidate = manager.inspectDownload(item, current.store); onReview(candidate); onDismiss() }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: "Extension download failed" }
                                    finally { loading = false }
                                } }) { Text(if (item.api !in setOf("1.4", "1.6")) "API ${item.api}" else if (installed) "Review update" else "Download") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

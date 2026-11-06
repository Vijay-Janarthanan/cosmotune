package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoDialog
import com.cosmotune.ui.sheets.CosmoDialogBody
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
    val look = orbitLook()
    CosmoDialog(onDismiss = onDismiss, maxWidth = 640.dp) {
        CosmoDialogBody(
            overline = "Manga", title = "Extension stores", message = null,
            confirmLabel = null, onConfirm = {}, dismissLabel = "Back", onDismiss = onDismiss,
            scroll = false,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OrbitInputField(address, { address = it }, "HTTPS index.pb or repo.json address", modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadButton("Load store", { fetch(address.trim()) }, enabled = address.isNotBlank() && !loading)
                    ReadTextButton("Keiyoushi", { address = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"; fetch(address) }, enabled = !loading)
                }
                if (loading) OrbitRing(size = 22.dp, color = look.accent, progress = null, spinning = true)
                error?.let { Text(it, style = ReadType.body(), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                val current = result
                if (current == null) {
                    if (state.extensionStores.isEmpty() && !loading) Text("Add a publisher's extension store or load Keiyoushi. Downloaded APKs are inspected before you choose whether to enable them.", style = ReadType.body(), color = look.dim)
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.extensionStores, key = { it.indexUrl }) { saved -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadTextButton(saved.name, { address = saved.indexUrl; fetch(saved.indexUrl, saved.signingKey) }, modifier = Modifier.weight(1f), enabled = !loading)
                            ReadTextButton("Remove", { scope.launch {
                                try { repository.store.removeExtensionStore(saved.indexUrl) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message }
                            } }, enabled = !loading)
                        } }
                    }
                } else {
                    Text(current.store.name, style = ReadType.titleMedium(), color = look.ink)
                    Text("Publisher SHA-256: ${current.store.signingKey}", style = ReadType.caption(), color = look.dim)
                    val saved = state.extensionStores.any { it.indexUrl == current.store.indexUrl && it.signingKey == current.store.signingKey }
                    ReadTextButton(if (saved) "Store saved" else "Save store", { scope.launch {
                        try { repository.store.saveExtensionStore(current.store) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    } }, enabled = !saved)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OrbitInputField(query, { query = it }, "Find extension", modifier = Modifier.weight(1f))
                        OrbitInputField(language, { language = it }, "Language", modifier = Modifier.width(110.dp))
                    }
                    val filtered = current.extensions.filter { (it.name.contains(query, true) || it.packageName.contains(query, true)) &&
                        (language.isBlank() || it.languages.any { lang -> lang.contains(language, true) }) }
                    Text("${filtered.size} extensions", style = ReadType.overline(), color = look.dim)
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.packageName }) { item ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = ReadType.rowTitle(), color = look.ink)
                                    Text("${item.version} · ${item.languages.joinToString()}" + if (item.contentWarning == 3) " · 18+" else "", style = ReadType.caption(), color = look.dim)
                                }
                                val installed = state.sources.any { it.kind == "mihon" && it.settings["package"] == item.packageName }
                                ReadTextButton(if (item.api !in setOf("1.4", "1.6")) "API ${item.api}" else if (installed) "Review update" else "Download", { scope.launch {
                                    loading = true; error = null
                                    try { val candidate = manager.inspectDownload(item, current.store); onReview(candidate); onDismiss() }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: "Extension download failed" }
                                    finally { loading = false }
                                } }, enabled = !loading && item.api in setOf("1.4", "1.6"))
                            }
                        }
                    }
                }
            }
        }
    }
}

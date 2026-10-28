package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cosmotune.manga.data.*
import kotlinx.coroutines.*

@Composable internal fun MangaVerificationSourcePicker(repository: MangaRepository) {
    val state by repository.store.state.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val enabled = state.sources.filter { it.enabled && it.kind != "komga" }
    if (enabled.isEmpty()) return
    Column {
        Box {
            TextButton(onClick = { expanded = true }) { Text("Website verification${enabled.find { it.id == selected }?.let { ": ${it.name}" }.orEmpty()}") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                enabled.forEach { source -> DropdownMenuItem(text = { Text(source.name) }, onClick = { selected = source.id; expanded = false }) }
            }
        }
        enabled.find { it.id == selected }?.let { key(it.id) { MangaVerificationControls(repository, it.id) } }
    }
}

@Composable internal fun MangaVerificationControls(repository: MangaRepository, sourceId: String, address: String? = null) {
    val scope = rememberCoroutineScope()
    var busy by remember(sourceId) { mutableStateOf(false) }
    var message by remember(sourceId) { mutableStateOf<String?>(null) }
    var active by remember(sourceId) { mutableStateOf(repository.context.verification.active(sourceId)) }
    val launch = rememberMangaVerificationLauncher { request, result ->
        scope.launch {
            try {
                val proof = result.getOrThrow()
                withContext(Dispatchers.IO) { repository.completeVerification(request, proof) }
                active = true; message = "Browser session received for up to 30 minutes. Retry the source to check it."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { repository.context.verification.reset(request.sourceId); active = false; message = failure.message ?: "Source verification failed." }
            finally { busy = false }
        }
    }
    Column {
        Row {
            TextButton(enabled = !busy, modifier = Modifier.testTag("manga:verify:$sourceId"), onClick = {
                busy = true; message = null
                scope.launch {
                    try { launch(withContext(Dispatchers.IO) { repository.beginVerification(sourceId, address) }) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { busy = false; message = failure.message ?: "Source browser unavailable." }
                }
            }) { Text(mangaText(if (busy) "Waiting for source verification…" else "Verify source website")) }
            if (active) TextButton(onClick = { repository.invalidateSource(sourceId); active = false; message = "Source verification cleared." }) { Text(mangaText("Reset verification")) }
        }
        Text("Complete the check yourself in an isolated browser. Only this website's cookies and browser identity are used temporarily. Some websites may reject embedded browsers.", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

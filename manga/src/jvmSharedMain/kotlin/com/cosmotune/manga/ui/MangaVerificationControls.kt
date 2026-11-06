package com.cosmotune.manga.ui

import com.cosmotune.ui.components.CosmoIcons
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
            ReadButton(mangaText("Website verification") + enabled.find { it.id == selected }?.let { ": ${it.name}" }.orEmpty(), { expanded = true }, kind = ReadKind.TONAL,
                icon = CosmoIcons.ChevronDown)
            com.cosmotune.ui.orbit.OrbitMenu(expanded, { expanded = false }, enabled.map { source ->
                com.cosmotune.ui.orbit.OrbitMenuItem(source.name, selected = source.id == selected) { selected = source.id }
            }, com.cosmotune.ui.orbit.orbitFonts(), com.cosmotune.ui.orbit.orbitLook(), header = mangaText("Verify a website"))
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
    val palette = com.cosmotune.ui.theme.LocalCosmoColors.current.palette
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadButton(mangaText(if (busy) "Waiting for source verification…" else "Verify source website"), enabled = !busy,
                kind = ReadKind.TONAL, modifier = Modifier.testTag("manga:verify:$sourceId"), onClick = {
                busy = true; message = null
                scope.launch {
                    try { launch(withContext(Dispatchers.IO) { repository.beginVerification(sourceId, address) }) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { busy = false; message = failure.message ?: "Source browser unavailable." }
                }
            })
            if (active) ReadTextButton(mangaText("Reset verification"), { repository.invalidateSource(sourceId); active = false; message = "Source verification cleared." })
        }
        Text("Complete the check yourself in an isolated browser. Only this website's cookies and browser identity are used temporarily. Some websites may reject embedded browsers.",
            style = ReadType.meta(), color = androidx.compose.ui.graphics.Color(palette.textSecondary))
        message?.let { Text(it, style = ReadType.meta(), color = androidx.compose.ui.graphics.Color(palette.textSecondary)) }
    }
}

package com.cosmotune.ui.listening

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ColumnScope.LibraryReviewPage(
    state: ListeningState, host: ListeningToolsHost, store: ListeningStore, busy: Boolean,
    run: (suspend () -> String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestHost by rememberUpdatedState(host)
    var task by remember { mutableStateOf<Job?>(null) }
    var report by remember { mutableStateOf<LibraryAuditReport?>(null) }
    var progress by remember { mutableStateOf<LibraryAuditProgress?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var verify by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var problemsOnly by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf<String?>(null) }
    var repairs by remember { mutableStateOf<List<SessionFileRepair>?>(null) }
    val working = busy || task != null
    val entries = report?.entries.orEmpty()
    val filtered = remember(entries, query, selectedFormat, problemsOnly) {
        entries.filter { entry ->
            (!problemsOnly || entry.state != LibraryFileState.READABLE) &&
                (selectedFormat == null || entry.format == selectedFormat) &&
                (query.isBlank() || listOf(entry.song.title, entry.song.artist, entry.song.albumName.orEmpty(), entry.song.playbackSource.orEmpty())
                    .any { it.contains(query.trim(), ignoreCase = true) })
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("listening:audit:list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Review local files", style = MaterialTheme.typography.headlineSmall)
            Text("Find missing or inaccessible audio and compare copies. Format labels come from file headers; a FLAC label does not establish the quality of its original recording.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Compare file bytes (up to 2 GiB)", Modifier.weight(1f))
                Switch(verify, { verify = it }, enabled = !working, modifier = Modifier.testTag("listening:audit:verify").semantics { contentDescription = "Compare file bytes" })
            }
            ToolButton("Scan local library", enabled = !working, tag = "listening:audit:scan", onClick = {
                task = scope.launch {
                    notice = null; progress = null; report = null
                    try {
                        val current = latestHost
                        val songs = current.library.songs + state.sessions.flatMap { session -> session.tracks.map { it.song() } }
                        report = auditLocalLibrary(songs, current.libraryAccess, verifyBytes = verify) { progress = it }
                    } catch (e: CancellationException) { notice = "Scan stopped"; throw e }
                    catch (e: Exception) { notice = e.message?.takeIf { !it.contains("://") } ?: "The scan could not finish. Your files are unchanged." }
                    finally { task = null }
                }
            })
            if (task != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                progress?.let { Text("${it.checked} / ${it.total} checked · ${it.hashedBytes / (1024 * 1024)} MiB compared", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                TextButton(onClick = { task?.cancel() }) { Text("Stop scan") }
            }
            notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
        report?.let { result ->
            item {
                Text("${result.entries.size} local entries · ${result.entries.count { it.state != LibraryFileState.READABLE }} need attention", style = MaterialTheme.typography.titleMedium)
                if (result.hashBudgetReached) Text("The comparison budget was reached. Uncompared files are not confirmed duplicates.")
                ToolButton("Export review report", enabled = !working, tag = "listening:audit:export", onClick = { run {
                    val text = withContext(Dispatchers.IO) { result.export() }
                    if (latestHost.exportFile("cosmotune-library-review.json", text)) "Review saved, including local file locations" else null
                } })
                Text("The report includes local file locations. Choose where you save it.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it.take(500) }, label = { Text("Title, artist, album or source") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("listening:audit:query"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Show files needing attention", Modifier.weight(1f))
                    Switch(problemsOnly, { problemsOnly = it }, modifier = Modifier.testTag("listening:audit:problems").semantics { contentDescription = "Show files needing attention" })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selectedFormat == null, { selectedFormat = null }, label = { Text("All formats") })
                    entries.mapNotNull { it.format }.distinct().sorted().forEach { format ->
                        FilterChip(selectedFormat == format, { selectedFormat = format }, label = { Text(format) })
                    }
                }
            }
            items(result.duplicates) { group ->
                val label = when (group.kind) {
                    LibraryDuplicateKind.SAME_FILE -> "Entries sharing one file"
                    LibraryDuplicateKind.IDENTICAL_BYTES -> "Identical file bytes"
                    LibraryDuplicateKind.METADATA_CANDIDATES -> "Similar metadata — review the recordings"
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(group.indices.joinToString("\n") { index -> result.entries[index].let { "${it.song.artist} · ${it.song.title}\n${it.identity.orEmpty()}" } })
            }
            items(filtered) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${entry.song.title} · ${entry.song.artist}", style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(entry.format ?: "Format not measured", entry.bytes?.let { "${it / 1024} KiB" }, entry.state.label()).joinToString(" · "))
                    entry.identity?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                }
            }
        }
        item {
            Text("Repair saved session locations", style = MaterialTheme.typography.titleMedium)
            Text("Find a readable local copy of the exact same recording. Preview every change; queue order, titles, likes and history stay intact.")
            ToolButton("Preview session repairs", enabled = !working, tag = "listening:audit:repair-preview", onClick = { run {
                val current = latestHost
                repairs = withContext(Dispatchers.IO) { previewSessionFileRepairs(store.state.value, current.library.songs, current.available) }
                if (repairs.isNullOrEmpty()) "No saved session locations can be repaired from this library" else "Review ${repairs!!.size} location changes below"
            } })
        }
        repairs?.takeIf { it.isNotEmpty() }?.let { preview ->
            items(preview) { change ->
                Text("${change.before.title}\n${change.before.path ?: change.before.uri}\n→ ${change.after.path ?: change.after.uri}")
            }
            item {
                ToolButton("Apply ${preview.size} reviewed repairs", enabled = !working, tag = "listening:audit:repair-apply", onClick = { run {
                    withContext(Dispatchers.IO) { store.update { applySessionFileRepairs(it, preview, latestHost.available) } }
                    repairs = null; "Saved session locations repaired"
                } })
                TextButton(onClick = { repairs = null }, enabled = !working) { Text("Discard preview") }
            }
        }
    }
}

private fun LibraryFileState.label() = when (this) {
    LibraryFileState.READABLE -> "Readable"
    LibraryFileState.EMPTY -> "Empty file"
    LibraryFileState.MISSING -> "Missing"
    LibraryFileState.ACCESS_REQUIRED -> "Permission required"
    LibraryFileState.UNSUPPORTED -> "Location not supported by this device"
    LibraryFileState.CHANGED -> "Changed during scan — rescan"
    LibraryFileState.FAILED -> "Could not read"
}

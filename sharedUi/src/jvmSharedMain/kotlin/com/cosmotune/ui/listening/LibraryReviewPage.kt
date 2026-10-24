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
import com.cosmotune.audio.*
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
    var analysis by remember { mutableStateOf<Map<String, LibraryAudioAnalysis>>(emptyMap()) }
    var analysisProgress by remember { mutableStateOf<LibraryAnalysisProgress?>(null) }
    var analysisSummary by remember { mutableStateOf<String?>(null) }
    var qualityFilter by remember { mutableStateOf<QualityFilter?>(null) }
    var genreFilter by remember { mutableStateOf<String?>(null) }
    var merge by remember { mutableStateOf<DuplicateMergePreview?>(null) }
    var trashCopies by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LibraryAuditEntry?>(null) }
    LaunchedEffect(host.analysisFile) {
        analysis = withContext(Dispatchers.IO) { runCatching { LibraryAnalysisStore(host.analysisFile).read() }.getOrDefault(emptyMap()) }
    }
    val working = busy || task != null
    val entries = report?.entries.orEmpty()
    fun measured(entry: LibraryAuditEntry) = entry.identity?.let { analysis[it] }?.takeIf { it.id == entry.song.videoId }
    val genres = remember(entries, analysis) { entries.mapNotNull { measured(it)?.genre }.distinct().sorted() }
    val filtered = remember(entries, query, selectedFormat, problemsOnly, analysis, qualityFilter, genreFilter) {
        entries.filter { entry ->
            val measurement = measured(entry)
            (!problemsOnly || entry.state != LibraryFileState.READABLE) &&
                (selectedFormat == null || entry.format == selectedFormat) &&
                (qualityFilter == null || qualityFilter!!.matches(measurement)) &&
                (genreFilter == null || measurement?.genre == genreFilter) &&
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
            Text("Measure loudness and audio quality", style = MaterialTheme.typography.titleMedium)
            Text("Decodes each local file once to measure EBU R128 loudness, ReplayGain, peak level, the decoded format and where high frequencies stop. Unchanged files are not decoded again. Playback uses the measurement when loudness normalization is on; files are never modified.")
            ToolButton("Analyse local audio", enabled = !working, tag = "listening:analysis:run", onClick = {
                task = scope.launch {
                    analysisSummary = null; analysisProgress = null
                    try {
                        val current = latestHost
                        val songs = current.library.songs + state.sessions.flatMap { session -> session.tracks.map { it.song() } }
                        val result = analyseLocalLibrary(songs, current.libraryAccess, current.pcmDecoder, LibraryAnalysisStore(current.analysisFile)) { analysisProgress = it }
                        analysis = withContext(Dispatchers.IO) { LibraryAnalysisStore(current.analysisFile).read() }
                        if (report == null) report = auditLocalLibrary(songs, current.libraryAccess)
                        analysisSummary = buildString {
                            append("${result.measured} measured, ${result.reused} unchanged")
                            if (result.unsupported > 0) append(", ${result.unsupported} not decodable on this device")
                            if (result.failed > 0) append(", ${result.failed} could not be read")
                        }
                    } catch (e: CancellationException) { analysisSummary = "Analysis stopped. Finished files were kept."; throw e }
                    catch (e: Exception) { analysisSummary = e.message?.takeIf { !it.contains("://") } ?: "The analysis could not finish. Your files are unchanged." }
                    finally { task = null }
                }
            })
            if (task != null) analysisProgress?.let { Text("${it.checked} / ${it.total} analysed", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            analysisSummary?.let { Text(it, Modifier.testTag("listening:analysis:summary").semantics { liveRegion = LiveRegionMode.Polite }) }
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(qualityFilter == null, { qualityFilter = null }, label = { Text("Any measured quality") })
                    QualityFilter.entries.forEach { option ->
                        FilterChip(qualityFilter == option, { qualityFilter = option }, label = { Text(option.label) }, modifier = Modifier.testTag("listening:quality:${option.name}"))
                    }
                }
                if (genres.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(genreFilter == null, { genreFilter = null }, label = { Text("All genres") })
                    genres.forEach { genre -> FilterChip(genreFilter == genre, { genreFilter = genre }, label = { Text(genre) }, modifier = Modifier.testTag("listening:genre:$genre")) }
                }
            }
            items(result.duplicates) { group ->
                val label = when (group.kind) {
                    LibraryDuplicateKind.SAME_FILE -> "Entries sharing one file"
                    LibraryDuplicateKind.IDENTICAL_BYTES -> "Identical file bytes"
                    LibraryDuplicateKind.METADATA_CANDIDATES -> "Similar metadata — review the recordings"
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (group.kind != LibraryDuplicateKind.IDENTICAL_BYTES) {
                    Text(group.indices.joinToString("\n") { index -> result.entries[index].let { "${it.song.artist} · ${it.song.title}\n${it.identity.orEmpty()}" } })
                } else group.indices.forEach { index ->
                    val entry = result.entries[index]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${entry.song.artist} · ${entry.song.title}\n${entry.identity.orEmpty()}", Modifier.weight(1f))
                        TextButton(enabled = !working, modifier = Modifier.testTag("listening:merge:keep:$index"), onClick = { run {
                            merge = withContext(Dispatchers.Default) { previewDuplicateMerge(store.state.value, result, group, index) }
                            null
                        } }) { Text("Keep this copy") }
                    }
                }
                // Only under the identical-bytes group it came from; the same file can also be a metadata candidate.
                merge?.takeIf { preview -> group.kind == LibraryDuplicateKind.IDENTICAL_BYTES &&
                    (preview.remove + preview.keep).all { member -> group.indices.any { result.entries[it] == member } } }?.let { preview ->
                    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp).testTag("listening:merge:preview"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Keep ${preview.keep.identity}. ${preview.repairs.size} saved session entries will point to it; titles, order and history stay.")
                            if (latestHost.trashFiles != null) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Also move ${preview.remove.size} other copies to the system trash", Modifier.weight(1f))
                                Switch(trashCopies, { trashCopies = it }, modifier = Modifier.testTag("listening:merge:trash").semantics { contentDescription = "Move other copies to the trash" })
                            }
                            preview.remove.forEach { Text("× ${it.identity}", style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ToolButton("Apply", enabled = !working, tag = "listening:merge:apply", onClick = { run {
                                    val current = latestHost
                                    withContext(Dispatchers.IO) { store.update { applySessionFileRepairs(it, preview.repairs, current.available) } }
                                    val trashed = if (trashCopies) current.trashFiles?.invoke(preview.remove.map { it.song }) ?: 0 else 0
                                    merge = null; trashCopies = false
                                    if (trashed > 0) report = null
                                    "Kept one copy; ${preview.repairs.size} session entries updated" + if (trashed > 0) ", $trashed copies moved to the trash. Scan again to refresh." else ""
                                } })
                                TextButton(onClick = { merge = null }, enabled = !working) { Text("Discard") }
                            }
                        }
                    }
                }
            }
            items(filtered) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${entry.song.title} · ${entry.song.artist}", style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(entry.format ?: "Format not measured", entry.bytes?.let { "${it / 1024} KiB" }, entry.state.label()).joinToString(" · "))
                    measured(entry)?.let { Text(it.describe(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("listening:analysis:entry")) }
                    entry.identity?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (entry.state == LibraryFileState.READABLE && entry.song.localPath?.let { java.io.File(it).isAbsolute } == true &&
                        (entry.format == "FLAC" || entry.format?.startsWith("ID3") == true || entry.song.localPath!!.endsWith(".mp3", true))) {
                        TextButton(enabled = !working, modifier = Modifier.testTag("listening:tags:edit"), onClick = { editing = if (editing == entry) null else entry }) { Text("Edit tags") }
                    }
                    if (editing == entry) TagEditor(entry, working, run) { path -> latestHost.onLocalFileChanged(path); editing = null }
                    HorizontalDivider()
                }
            }
        }
        item {
            var exception by remember { mutableStateOf("") }
            Text("Artist names that are one artist", style = MaterialTheme.typography.titleMedium)
            Text("Commas, ampersands and slashes normally separate collaborators. Add a name that contains them, such as a band or a composer trio, to keep it whole in matching and listening statistics.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(exception, { exception = it.take(120) }, label = { Text("Artist name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("listening:artist:exception"))
                TextButton(enabled = !working && exception.trim().length >= 2, modifier = Modifier.testTag("listening:artist:add"), onClick = { run {
                    val name = exception.trim()
                    withContext(Dispatchers.IO) { store.update { it.copy(preferences = it.preferences.copy(artistExceptions = (it.preferences.artistExceptions + name).distinctBy { n -> n.lowercase() }.take(200))) } }
                    exception = ""; "“$name” is kept as one artist"
                } }) { Text("Add") }
            }
            state.preferences.artistExceptions.forEach { name ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f))
                    TextButton(enabled = !working, onClick = { run {
                        withContext(Dispatchers.IO) { store.update { it.copy(preferences = it.preferences.copy(artistExceptions = it.preferences.artistExceptions - name)) } }; null
                    } }) { Text("Remove") }
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
            ToolButton("Preview lossless upgrades", enabled = !working, tag = "listening:audit:upgrades", onClick = { run {
                val current = latestHost
                repairs = withContext(Dispatchers.IO) { previewLosslessUpgrades(store.state.value, analysis, current.available) }
                if (repairs.isNullOrEmpty()) "No analysed lossless copies of saved lossy entries were found" else "Review ${repairs!!.size} upgrades below. Audio files are not changed."
            } })
            Text("Moved or renamed files are found by the fingerprint an earlier analysis recorded (exact size, length and loudness), only when one file matches.", style = MaterialTheme.typography.bodySmall)
            ToolButton("Find moved files", enabled = !working, tag = "listening:audit:moved", onClick = { run {
                val current = latestHost
                repairs = withContext(Dispatchers.IO) { previewMovedFileRepairs(store.state.value, analysis, current.available) }
                if (repairs.isNullOrEmpty()) "No moved files were found. Analyse your library before moving files to make this possible." else "Review ${repairs!!.size} location changes below"
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

internal enum class QualityFilter(val label: String) {
    LOSSLESS("Lossless decode"), LOSSY("Lossy decode"), SUSPECT("Possible lossy source"), UNMEASURED("Not analysed");
    fun matches(analysis: LibraryAudioAnalysis?) = when (this) {
        LOSSLESS -> analysis?.lossless == true
        LOSSY -> analysis != null && !analysis.lossless
        SUSPECT -> analysis?.possibleLossySource == true
        UNMEASURED -> analysis == null
    }
}

internal fun LibraryAudioAnalysis.describe(): String = listOfNotNull(
    integratedLufs?.let { "%.1f LUFS".format(java.util.Locale.ROOT, it) } ?: "Too quiet to measure loudness",
    replayGainDb?.let { "ReplayGain %+.1f dB".format(java.util.Locale.ROOT, it) },
    if (samplePeak > 0) "peak %.1f dBFS".format(java.util.Locale.ROOT, 20 * kotlin.math.log10(samplePeak)) else null,
    listOfNotNull(codec, if (sampleRate > 0) "%.1f kHz".format(java.util.Locale.ROOT, sampleRate / 1000.0) else null,
        bitsPerSample?.let { "$it-bit" }, bitrate?.let { "${it / 1000} kbps" }).joinToString(" ").ifBlank { null },
    cutoffHz?.let { "content to %.1f kHz".format(java.util.Locale.ROOT, it / 1000.0) },
    if (possibleLossySource) "possible lossy source" else null,
    genre,
).joinToString(" · ")

@Composable
private fun TagEditor(entry: LibraryAuditEntry, working: Boolean, run: (suspend () -> String?) -> Unit, saved: (String) -> Unit) {
    val path = entry.song.localPath!!
    var original by remember(path) { mutableStateOf<Map<String, String>?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    val values = remember(path) { mutableStateMapOf<String, String>() }
    LaunchedEffect(path) {
        runCatching { withContext(Dispatchers.IO) { LocalTagEditor.read(java.io.File(path)) } }
            .onSuccess { tags -> original = tags; values.putAll(tags) }
            .onFailure { failure = it.message ?: "These tags cannot be read." }
    }
    Column(Modifier.testTag("listening:tags:editor"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error); return@Column }
        if (original == null) { Text("Reading tags…"); return@Column }
        listOf("TITLE" to "Title", "ARTIST" to "Artist", "ALBUM" to "Album", "ALBUM_ARTIST" to "Album artist", "GENRE" to "Genre", "TRACK" to "Track", "YEAR" to "Year").forEach { (key, label) ->
            OutlinedTextField(values[key].orEmpty(), { values[key] = it.take(1000) }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("listening:tags:$key"))
        }
        Text("Writes a verified copy beside the file, then replaces it. The audio itself is not re-encoded.", style = MaterialTheme.typography.bodySmall)
        ToolButton("Save tags", enabled = !working, tag = "listening:tags:save", onClick = { run {
            val before = original.orEmpty()
            fun changed(key: String) = values[key].orEmpty().takeIf { it != before[key].orEmpty() }
            val tags = LocalTags(changed("TITLE"), changed("ARTIST"), changed("ALBUM"), changed("ALBUM_ARTIST"), changed("GENRE"), changed("TRACK"), changed("YEAR"))
            if (tags == LocalTags()) return@run "No tag changes to save"
            withContext(Dispatchers.IO) { LocalTagEditor.write(java.io.File(path), tags) }
            saved(path)
            "Tags saved. Your library shows them after it refreshes."
        } })
    }
}

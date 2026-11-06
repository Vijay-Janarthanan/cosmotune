package com.cosmotune.ui.listening

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.*
import com.cosmotune.ui.orbit.OrbitBusyRing
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
            Heading("Review local files", "Find missing or inaccessible audio and compare copies. Format labels come from file headers; a FLAC label does not establish the quality of its original recording.")
            ToggleRow("Compare file bytes (up to 2 GiB)", verify, tag = "listening:audit:verify", description = "Compare file bytes", enabled = !working) { verify = it }
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
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OrbitBusyRing()
                    progress?.let { Text("${it.checked} / ${it.total} checked · ${it.hashedBytes / (1024 * 1024)} MiB compared", Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite }) }
                    ToolButton("Stop scan", { task?.cancel() }, kind = ToolKind.QUIET)
                }
            }
            notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            ToolSection("Measure loudness and audio quality")
            Text("Decodes each local file once to measure EBU R128 loudness, ReplayGain track and album gain, peak level, the decoded format and where high frequencies stop. Unchanged files are not decoded again. Playback uses the measurement when loudness normalization is on; files are never modified.")
            ToolButton("Analyse local audio", enabled = !working, kind = ToolKind.QUIET, tag = "listening:analysis:run", onClick = {
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
                ToolSection("${result.entries.size} local entries · ${result.entries.count { it.state != LibraryFileState.READABLE }} need attention")
                if (result.hashBudgetReached) Text("The comparison budget was reached. Uncompared files are not confirmed duplicates.")
                ToolButton("Export review report", enabled = !working, kind = ToolKind.QUIET, tag = "listening:audit:export", onClick = { run {
                    val text = withContext(Dispatchers.IO) { result.export() }
                    if (latestHost.exportFile("cosmotune-library-review.json", text)) "Review saved, including local file locations" else null
                } })
                ToolNote("The report includes local file locations. Choose where you save it.")
                ToolField(query, { query = it.take(500) }, "Title, artist, album or source", Modifier.fillMaxWidth(), tag = "listening:audit:query")
                ToggleRow("Show files needing attention", problemsOnly, tag = "listening:audit:problems") { problemsOnly = it }
                ToolChipRow {
                    ToolChip("All formats", selectedFormat == null, { selectedFormat = null })
                    entries.mapNotNull { it.format }.distinct().sorted().forEach { format ->
                        ToolChip(format, selectedFormat == format, { selectedFormat = format })
                    }
                }
                ToolChipRow {
                    ToolChip("Any measured quality", qualityFilter == null, { qualityFilter = null })
                    QualityFilter.entries.forEach { option ->
                        ToolChip(option.label, qualityFilter == option, { qualityFilter = option }, Modifier.testTag("listening:quality:${option.name}"))
                    }
                }
                if (genres.isNotEmpty()) ToolChipRow {
                    ToolChip("All genres", genreFilter == null, { genreFilter = null })
                    genres.forEach { genre -> ToolChip(genre, genreFilter == genre, { genreFilter = genre }, Modifier.testTag("listening:genre:$genre")) }
                }
            }
            items(result.duplicates) { group ->
                val label = when (group.kind) {
                    LibraryDuplicateKind.SAME_FILE -> "Entries sharing one file"
                    LibraryDuplicateKind.IDENTICAL_BYTES -> "Identical file bytes"
                    LibraryDuplicateKind.METADATA_CANDIDATES -> "Similar metadata — review the recordings"
                }
                ToolCard {
                ToolTitle(label)
                if (group.kind != LibraryDuplicateKind.IDENTICAL_BYTES) {
                    ToolNote(group.indices.joinToString("\n") { index -> result.entries[index].let { "${it.song.artist} · ${it.song.title}\n${it.identity.orEmpty()}" } })
                } else group.indices.forEach { index ->
                    val entry = result.entries[index]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToolNote("${entry.song.artist} · ${entry.song.title}\n${entry.identity.orEmpty()}", Modifier.weight(1f))
                        ToolButton("Keep this copy", kind = ToolKind.QUIET, enabled = !working, tag = "listening:merge:keep:$index", onClick = { run {
                            merge = withContext(Dispatchers.Default) { previewDuplicateMerge(store.state.value, result, group, index) }
                            null
                        } })
                    }
                }
                // Only under the identical-bytes group it came from; the same file can also be a metadata candidate.
                merge?.takeIf { preview -> group.kind == LibraryDuplicateKind.IDENTICAL_BYTES &&
                    (preview.remove + preview.keep).all { member -> group.indices.any { result.entries[it] == member } } }?.let { preview ->
                    ToolCard(Modifier.testTag("listening:merge:preview"), raised = true) {
                            Text("Keep ${preview.keep.identity}. ${preview.repairs.size} saved session entries will point to it; titles, order and history stay.")
                            if (latestHost.trashFiles != null) ToggleRow("Also move ${preview.remove.size} other copies to the system trash", trashCopies,
                                tag = "listening:merge:trash", description = "Move other copies to the trash") { trashCopies = it }
                            preview.remove.forEach { ToolNote("× ${it.identity}") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ToolButton("Apply", enabled = !working, tag = "listening:merge:apply", onClick = { run {
                                    val current = latestHost
                                    withContext(Dispatchers.IO) { store.update { applySessionFileRepairs(it, preview.repairs, current.available) } }
                                    val trashed = if (trashCopies) current.trashFiles?.invoke(preview.remove.map { it.song }) ?: 0 else 0
                                    merge = null; trashCopies = false
                                    if (trashed > 0) report = null
                                    "Kept one copy; ${preview.repairs.size} session entries updated" + if (trashed > 0) ", $trashed copies moved to the trash. Scan again to refresh." else ""
                                } })
                                ToolButton("Discard", { merge = null }, enabled = !working, kind = ToolKind.QUIET)
                            }
                    }
                }
                }
            }
            items(filtered) { entry ->
                ToolCard {
                    ToolTitle("${entry.song.title} · ${entry.song.artist}")
                    Text(listOfNotNull(entry.format ?: "Format not measured", entry.bytes?.let { "${it / 1024} KiB" }, entry.state.label()).joinToString(" · "))
                    measured(entry)?.let { ToolNote(it.describe(), Modifier.testTag("listening:analysis:entry")) }
                    entry.identity?.let { ToolNote(it) }
                    if (entry.state == LibraryFileState.READABLE && entry.song.localPath?.let { java.io.File(it).isAbsolute } == true &&
                        (entry.format == "FLAC" || entry.format?.startsWith("ID3") == true || entry.format == "OPUS" || entry.format == "OGG" ||
                            entry.format?.startsWith("MP4") == true || entry.song.localPath!!.endsWith(".mp3", true))) {
                        ToolButton("Edit tags", { editing = if (editing == entry) null else entry }, enabled = !working, kind = ToolKind.QUIET, tag = "listening:tags:edit")
                    }
                    if (editing == entry) TagEditor(entry, working, run) { path -> latestHost.onLocalFileChanged(path); editing = null }
                }
            }
        }
        item {
            var exception by remember { mutableStateOf("") }
            ToolSection("Artist names that are one artist")
            Text("Commas, ampersands and slashes normally separate collaborators. Add a name that contains them, such as a band or a composer trio, to keep it whole in matching and listening statistics.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolField(exception, { exception = it.take(120) }, "Artist name", Modifier.weight(1f), tag = "listening:artist:exception")
                ToolButton("Add", enabled = !working && exception.trim().length >= 2, kind = ToolKind.QUIET, tag = "listening:artist:add", onClick = { run {
                    val name = exception.trim()
                    withContext(Dispatchers.IO) { store.update { it.copy(preferences = it.preferences.copy(artistExceptions = (it.preferences.artistExceptions + name).distinctBy { n -> n.lowercase() }.take(200))) } }
                    exception = ""; "“$name” is kept as one artist"
                } })
            }
            state.preferences.artistExceptions.forEach { name ->
                ToolLine(name, Modifier.padding(vertical = 3.dp)) {
                    ToolButton("Remove", enabled = !working, kind = ToolKind.QUIET, onClick = { run {
                        withContext(Dispatchers.IO) { store.update { it.copy(preferences = it.preferences.copy(artistExceptions = it.preferences.artistExceptions - name)) } }; null
                    } })
                }
            }
        }
        item {
            ToolSection("Repair saved session locations")
            Text("Find a readable local copy of the exact same recording. Preview every change; queue order, titles, likes and history stay intact.")
            ToolButton("Preview session repairs", enabled = !working, tag = "listening:audit:repair-preview", onClick = { run {
                val current = latestHost
                repairs = withContext(Dispatchers.IO) { previewSessionFileRepairs(store.state.value, current.library.songs, current.available) }
                if (repairs.isNullOrEmpty()) "No saved session locations can be repaired from this library" else "Review ${repairs!!.size} location changes below"
            } })
            ToolButton("Preview lossless upgrades", enabled = !working, kind = ToolKind.QUIET, tag = "listening:audit:upgrades", onClick = { run {
                val current = latestHost
                repairs = withContext(Dispatchers.IO) { previewLosslessUpgrades(store.state.value, analysis, current.available) }
                if (repairs.isNullOrEmpty()) "No analysed lossless copies of saved lossy entries were found" else "Review ${repairs!!.size} upgrades below. Audio files are not changed."
            } })
            host.requestLosslessCopies?.let { request ->
                ToolButton("Get lossless copies from sources", enabled = !working, kind = ToolKind.QUIET, tag = "listening:audit:lossless-copies", onClick = { run {
                    val wanted = withContext(Dispatchers.IO) { losslessCopyRequests(store.state.value, analysis) }
                    if (wanted.isEmpty()) "No analysed lossy entries need a lossless copy"
                    else "Queued ${request(wanted.map { it.song() })} lossless downloads. Each is labelled from its own bytes; analyse the library again, then preview upgrades."
                } })
            }
            ToolNote("Moved or renamed files are found by the fingerprint an earlier analysis recorded (exact size, length and loudness), only when one file matches.")
            ToolButton("Find moved files", enabled = !working, kind = ToolKind.QUIET, tag = "listening:audit:moved", onClick = { run {
                val current = latestHost
                repairs = withContext(Dispatchers.IO) { previewMovedFileRepairs(store.state.value, analysis, current.available) }
                if (repairs.isNullOrEmpty()) "No moved files were found. Analyse your library before moving files to make this possible." else "Review ${repairs!!.size} location changes below"
            } })
        }
        repairs?.takeIf { it.isNotEmpty() }?.let { preview ->
            items(preview) { change ->
                ToolCard { Text("${change.before.title}\n${change.before.path ?: change.before.uri}\n→ ${change.after.path ?: change.after.uri}") }
            }
            item {
                ToolButton("Apply ${preview.size} reviewed repairs", enabled = !working, tag = "listening:audit:repair-apply", onClick = { run {
                    withContext(Dispatchers.IO) { store.update { applySessionFileRepairs(it, preview, latestHost.available) } }
                    repairs = null; "Saved session locations repaired"
                } })
                ToolButton("Discard preview", { repairs = null }, enabled = !working, kind = ToolKind.QUIET)
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
    albumGainDb?.let { "album %+.1f dB".format(java.util.Locale.ROOT, it) },
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
        failure?.let { ToolError(it); return@Column }
        if (original == null) { Text("Reading tags…"); return@Column }
        listOf("TITLE" to "Title", "ARTIST" to "Artist", "ALBUM" to "Album", "ALBUM_ARTIST" to "Album artist", "GENRE" to "Genre", "TRACK" to "Track", "YEAR" to "Year").forEach { (key, label) ->
            ToolField(values[key].orEmpty(), { values[key] = it.take(1000) }, label, Modifier.fillMaxWidth(), tag = "listening:tags:$key", labelled = true)
        }
        ToolNote("Writes a verified copy beside the file, then replaces it. The audio itself is not re-encoded.")
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

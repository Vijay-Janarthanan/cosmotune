package com.cosmotune.ui.listening

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.listening.*
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.theme.LocalCosmoShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ListeningTab(val label: String) {
    SESSIONS("Sessions"), SMART("Smart playlists"), REPLAY("Replay playlists"),
    RELEASES("New releases"), CONNECTION("Connection"), SETUP("Getting started"), BACKUP("Backup & transfer"), LIBRARY("Library review"), REMOTE("Phone remote")
}

/** A single adaptive workspace. All buttons perform host operations; data is shared across platforms. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListeningTools(open: Boolean, host: ListeningToolsHost, onDismiss: () -> Unit, maxWidth: Dp = 960.dp) {
    val latestHost by rememberUpdatedState(host)
    var loaded by remember(host.file) { mutableStateOf<Result<ListeningStore>?>(null) }
    var loadRevision by remember { mutableIntStateOf(0) }
    var setupDismissed by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(ListeningTab.SESSIONS) }
    LaunchedEffect(host.file, loadRevision) {
        loaded = withContext(Dispatchers.IO) { runCatching { ListeningStore(host.file) } }
    }
    val store = loaded?.getOrNull()
    val state = store?.state?.collectAsState()?.value
    val firstRun = state != null && !state.preferences.setupComplete && !host.existingUser && !setupDismissed
    LaunchedEffect(firstRun) { if (firstRun) tab = ListeningTab.SETUP }
    if (!open && !firstRun) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val run: (suspend () -> String?) -> Unit = { work ->
        if (!busy) scope.launch {
            busy = true; notice = null; error = false
            try { notice = work() }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { error = true; notice = "The request timed out. Please try again." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = true; notice = e.message?.takeIf { !it.contains("://") } ?: "Could not finish. Please try again." }
            finally { busy = false }
        }
    }
    val close = {
        setupDismissed = true
        onDismiss()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = maxWidth).fillMaxWidth().fillMaxHeight(0.94f).testTag("listening:panel"), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), tonalElevation = 4.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Your listening", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        TextButton(onClick = close, modifier = Modifier.testTag("listening:close")) { Text("Close") }
                    }
                    Text("Keep the queue. Find your next favourite.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListeningTab.entries.filter { it != ListeningTab.REMOTE || host.remote != null }.forEach { destination ->
                            FilterChip(selected = tab == destination, onClick = { tab = destination; notice = null },
                                label = { Text(destination.label) }, modifier = Modifier.testTag("listening:tab:${destination.name}"))
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("listening:busy"))
                    notice?.let {
                        Text(it, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (loaded == null) {
                        Text("Opening your listening library…", Modifier.padding(vertical = 24.dp))
                    } else if (store == null || state == null) {
                        Text("Your saved listening data could not be opened. The original file has been kept for recovery.", Modifier.padding(vertical = 24.dp))
                        ToolButton("Retry", { loadRevision++ })
                    } else when (tab) {
                        ListeningTab.SESSIONS -> SessionsPage(state, host, store, busy, run, close)
                        ListeningTab.SMART -> SmartPage(state, host, store, busy, run, close)
                        ListeningTab.REPLAY -> ReplayPage(host, busy, run)
                        ListeningTab.RELEASES -> ReleasesPage(state, host, store, busy, run, close)
                        ListeningTab.CONNECTION -> ConnectionPage(state, host, store, busy, run)
                        ListeningTab.SETUP -> SetupPage(state.preferences, host.languageChoices, busy, run,
                            onComplete = { preferences, lowData ->
                                withContext(Dispatchers.IO) { store.update { it.copy(preferences = preferences.copy(setupComplete = true)) } }
                                latestHost.applySetup(preferences, lowData)
                                setupDismissed = true
                            },
                            onSkip = {
                                withContext(Dispatchers.IO) { store.update { it.copy(preferences = it.preferences.copy(setupComplete = true)) } }
                                setupDismissed = true
                            },
                            onImport = { close(); latestHost.importMusic() })
                        ListeningTab.BACKUP -> BackupPage(host, store, busy, run)
                        ListeningTab.LIBRARY -> LibraryReviewPage(state, host, store, busy, run)
                        ListeningTab.REMOTE -> host.remote?.let { PhoneRemotePage(it, busy, run) }
                            ?: Text("Remote controls are unavailable in this window.")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.SessionsPage(state: ListeningState, host: ListeningToolsHost, store: ListeningStore, busy: Boolean, run: (suspend () -> String?) -> Unit, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Heading("Pick up where you left off", "Save your queue, position, repeat and shuffle settings. Opening a session replaces the current queue and leaves playback paused.")
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Session name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("listening:session:name"))
            ToolButton(if (editing == null) "Save current queue" else "Save name", enabled = !busy && name.isNotBlank(), tag = "listening:session:save", onClick = {
                run {
                    val q = host.queue()
                    withContext(Dispatchers.IO) {
                        if (editing == null) store.saveSession(name, q.songs, q.index, q.positionMs, q.repeat, q.shuffle)
                        else store.renameSession(editing!!, name)
                    }
                    name = ""; editing = null; "Session saved"
                }
            })
        }
        if (state.sessions.isEmpty()) item { EmptyHint("No saved sessions", "Add a few songs to the queue, then give this session a name.", host.findMusic, close) }
        items(state.sessions, key = { it.id }) { session ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session.name, style = MaterialTheme.typography.titleMedium)
                Text("${session.tracks.size} tracks · ${session.tracks[session.index].title} · ${stamp(session.savedAt)}", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("Open session", enabled = !busy, onClick = {
                        run {
                            val available = withContext(Dispatchers.IO) { session.playable(host.available) }
                                ?: error("These local files are unavailable on this device. Your saved session is unchanged.")
                            host.restore(available)
                            val missing = session.tracks.size - available.tracks.size
                            if (missing > 0) "$missing unavailable tracks skipped. Session opened paused." else "Session opened paused"
                        }
                    })
                    ToolButton("Rename", { editing = session.id; name = session.name }, kind = CosmoButtonKind.TEXT, enabled = !busy)
                    ToolButton(if (confirmDelete == session.id) "Confirm delete" else "Delete", {
                        if (confirmDelete != session.id) confirmDelete = session.id else run {
                            withContext(Dispatchers.IO) { store.deleteSession(session.id) }; confirmDelete = null; "Session deleted"
                        }
                    }, kind = CosmoButtonKind.TEXT, enabled = !busy)
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.SmartPage(state: ListeningState, host: ListeningToolsHost, store: ListeningStore, busy: Boolean, run: (suspend () -> String?) -> Unit, close: () -> Unit) {
    var draft by remember { mutableStateOf(SmartPlaylist(name = "")) }
    var preview by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val songs = remember(draft, host.library) { draft.songs(host.library) }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Heading("A library that keeps up", "Rules update from the music already in your library and recent history. Export a snapshot to keep a fixed playlist.")
            OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(80)) }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("listening:smart:name"))
            ToggleRow("Liked tracks", draft.likedOnly) { draft = draft.copy(likedOnly = it) }
            ToggleRow("Downloaded on this device", draft.downloadedOnly) { draft = draft.copy(downloadedOnly = it) }
            ToggleRow("Not in recent history", draft.excludeRecent) { draft = draft.copy(excludeRecent = it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartOrder.entries.forEach { order ->
                    FilterChip(draft.order == order, { draft = draft.copy(order = order) }, label = { Text(when(order) { SmartOrder.TITLE -> "Title"; SmartOrder.ARTIST -> "Artist"; SmartOrder.RECENTLY_ADDED -> "Recently added" }) })
                }
            }
            Text("${songs.size} matching tracks", Modifier.padding(vertical = 8.dp).testTag("listening:smart:count"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("Preview", { preview = !preview }, kind = CosmoButtonKind.TONAL)
                ToolButton("Save rules", enabled = !busy && draft.name.isNotBlank(), tag = "listening:smart:save", onClick = { run {
                    withContext(Dispatchers.IO) { store.saveSmart(draft) }; draft = SmartPlaylist(name = ""); "Smart playlist saved"
                } })
            }
            if (preview) songs.take(10).forEach { Text("${it.title} · ${it.artist}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        if (state.smartPlaylists.isEmpty()) item { EmptyHint("Your first smart playlist", "Choose a rule, preview its tracks, then save it. No account is needed.", host.findMusic, close) }
        items(state.smartPlaylists.sortedByDescending { it.pinned }, key = { it.id }) { rule ->
            val matching = remember(rule, host.library) { rule.songs(host.library) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (rule.pinned) "Pinned · " else "") + rule.name, style = MaterialTheme.typography.titleMedium)
                Text("${matching.size} matching tracks", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("Play", { run { host.play(matching); close(); null } }, enabled = !busy && matching.isNotEmpty())
                    ToolButton("Save as playlist", enabled = !busy && matching.isNotEmpty(), kind = CosmoButtonKind.TONAL, onClick = { run { host.createPlaylist(rule.name, matching); "Playlist saved to your library" } })
                    ToolButton("Edit", { draft = rule; preview = true }, kind = CosmoButtonKind.TEXT, enabled = !busy)
                    ToolButton(if (rule.pinned) "Unpin" else "Pin", kind = CosmoButtonKind.TEXT, enabled = !busy, onClick = { run { withContext(Dispatchers.IO) { store.saveSmart(rule.copy(pinned = !rule.pinned)) }; null } })
                    ToolButton(if (deleting == rule.id) "Confirm delete" else "Delete", kind = CosmoButtonKind.TEXT, enabled = !busy, onClick = {
                        if (deleting != rule.id) deleting = rule.id else run { withContext(Dispatchers.IO) { store.deleteSmart(rule.id) }; deleting = null; "Smart playlist deleted" }
                    })
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ColumnScope.ReplayPage(host: ListeningToolsHost, busy: Boolean, run: (suspend () -> String?) -> Unit) {
    var period by remember { mutableStateOf(ReplayWindow.MONTH) }
    var result by remember { mutableStateOf<ReplayTracks?>(null) }
    var created by remember { mutableStateOf<Set<String>>(emptySet()) }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Heading("Your favourites, on repeat", "Make a local playlist from the tracks you listened to most this month or this year.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayWindow.entries.forEach { window -> FilterChip(period == window, { period = window; result = null }, label = { Text(if (window == ReplayWindow.MONTH) "This month" else "This year") }) }
            }
            ToolButton("Load listening history", enabled = !busy, tag = "listening:replay:load", onClick = { run { result = withContext(Dispatchers.IO) { host.replay(period) }; if (result!!.songs.isEmpty()) "Play some music first. This period has no listening history yet." else null } })
        }
        result?.let { replay ->
            item {
                Text("${replay.title} · ${replay.songs.size} tracks", style = MaterialTheme.typography.titleMedium)
                ToolButton(if (replay.title in created) "Playlist saved" else "Create playlist", enabled = !busy && replay.songs.isNotEmpty() && replay.title !in created, tag = "listening:replay:create", onClick = { run {
                    host.createPlaylist(replay.title, replay.songs.distinctBy { it.videoId }); created = created + replay.title; "Playlist saved to your library"
                } })
            }
            items(replay.songs.take(50), key = { it.videoId }) { song -> Text("${song.title} · ${song.artist}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ReleasesPage(state: ListeningState, host: ListeningToolsHost, store: ListeningStore, busy: Boolean, run: (suspend () -> String?) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<WatchedArtist>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Heading("Stay close to your artists", "Follow artists here to check for newly discovered albums and singles. The first successful check sets a baseline. Checks run only when you ask.")
            OutlinedTextField(query, { query = it; searched = false; found = emptyList() }, label = { Text("Find an artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ToolButton("Search artists", enabled = !busy && query.trim().length >= 2, onClick = { run { found = ListeningNetwork.artists(query); searched = true; if (found.isEmpty()) "No matching artists. Try a different spelling." else null } })
        }
        if (searched) items(found, key = { "search:${it.id}" }) { artist ->
            val watched = state.artists.any { it.id == artist.id }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(artist.name, Modifier.weight(1f))
                ToolButton(if (watched) "Following" else "Follow", enabled = !busy && !watched, onClick = { run {
                    withContext(Dispatchers.IO) { store.watchArtist(artist.id, artist.name) }
                    val releases = ListeningNetwork.releases(artist)
                    withContext(Dispatchers.IO) { store.acceptReleases(artist.id, releases) }
                    "Following ${artist.name}. Future checks will show newly discovered releases."
                } })
            }
        }
        item {
            ToolButton("Check for releases", enabled = !busy && state.artists.isNotEmpty(), onClick = { run {
                var failures = 0
                for (artist in state.artists) {
                    try {
                        val releases = ListeningNetwork.releases(artist)
                        withContext(Dispatchers.IO) { store.acceptReleases(artist.id, releases) }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { failures++ }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { failures++ }
                }
                if (failures > 0) "$failures artist checks failed. Saved releases were kept; retry when connected." else "Release checks complete"
            } })
        }
        items(state.artists, key = { "artist:${it.id}" }) { artist ->
            Column {
                Text(artist.name, style = MaterialTheme.typography.titleMedium)
                Text(artist.checkedAt?.let { "Last checked ${stamp(it)}" } ?: "First check pending", style = MaterialTheme.typography.bodySmall)
                ToolButton("Stop following", kind = CosmoButtonKind.TEXT, enabled = !busy, onClick = { run { withContext(Dispatchers.IO) { store.unwatchArtist(artist.id) }; "Artist removed from this inbox" } })
            }
        }
        if (state.inbox.isEmpty()) item { Text("No newly discovered releases yet. Your saved inbox will stay available offline.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.inbox, key = { "release:${it.artistId}:${it.id}" }) { release ->
            Column {
                Text((if (!release.read) "New · " else "") + release.title, style = MaterialTheme.typography.titleMedium)
                Text(release.artist, style = MaterialTheme.typography.bodySmall)
                ToolButton("Open release", enabled = !busy, onClick = { run {
                    withContext(Dispatchers.IO) { store.markRead(release.id) }; host.openRelease(release.id, release.title); close(); null
                } })
            }
        }
    }
}

@Composable
private fun ColumnScope.ConnectionPage(state: ListeningState, host: ListeningToolsHost, store: ListeningStore, busy: Boolean, run: (suspend () -> String?) -> Unit) {
    val diagnostics = state.diagnostics
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Heading("Know what is getting through", "Test catalogue search and a short media read separately. This contacts the music provider and media host. It does not change playback.")
            if (host.sourceSummary.isNotBlank()) Text(host.sourceSummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolButton("Run connection checks", enabled = !busy, tag = "listening:diagnostics:run", onClick = { run {
                val queue = host.queue()
                val result = host.connectionCheck(queue.songs.getOrNull(queue.index))
                withContext(Dispatchers.IO) { store.recordDiagnostics(result) }
                null
            } })
        }
        diagnostics?.let { report ->
            item { Text("Last checked ${stamp(report.checkedAt)}", style = MaterialTheme.typography.bodySmall) }
            items(report.checks, key = { it.name }) { check ->
                Column {
                    Text("${check.name} · ${if (check.ok) "Connected" else "Needs attention"}", style = MaterialTheme.typography.titleMedium, color = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text(check.detail)
                }
            }
            item {
                Text("Report preview", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(report.report(), style = MaterialTheme.typography.bodySmall) }
                ToolButton("Save this report", enabled = !busy, onClick = { run {
                    if (host.exportFile("cosmotune-connection.txt", report.report())) "Report saved" else null
                } })
            }
        }
    }
}

@Composable
private fun ColumnScope.SetupPage(preferences: ListeningPreferences, languageChoices: List<Pair<String, String>>, busy: Boolean, run: (suspend () -> String?) -> Unit,
    onComplete: suspend (ListeningPreferences, Boolean) -> Unit, onSkip: suspend () -> Unit, onImport: () -> Unit) {
    var local by remember { mutableStateOf(preferences.preferLocal) }
    var lowData by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(preferences.contentLanguage) }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Heading("Make room for your music", "Start without an account. These choices stay on this device and can be changed in Settings.")
            ToggleRow("Open local music after setup", local) { local = it }
            Text("App language", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                languageChoices.forEach { (tag, label) ->
                    FilterChip(language == tag, { language = tag }, label = { Text(label) })
                }
            }
            ToggleRow("Use less data", lowData) { lowData = it }
            if (lowData) Text("Apply lower streaming quality and turn off animated canvas. Download quality stays unchanged.", style = MaterialTheme.typography.bodySmall)
            ToolButton("Apply choices", enabled = !busy, onClick = { run { onComplete(ListeningPreferences(true, language, local), lowData); "Your choices are saved" } })
            TextButton(onClick = onImport, enabled = !busy) { Text("Import an existing music library") }
            TextButton(onClick = { run { onSkip(); "You can return here anytime" } }, enabled = !busy) { Text("Skip setup") }
        }
    }
}

@Composable
private fun ColumnScope.BackupPage(host: ListeningToolsHost, store: ListeningStore, busy: Boolean, run: (suspend () -> String?) -> Unit) {
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Heading("Take your listening with you", "Transfer saved sessions, smart-playlist rules and your release inbox between Android, Windows, Linux and macOS. This file includes track metadata and local file paths, so choose where you save it. Account credentials and audio files are excluded.")
            ToolButton("Export listening backup", enabled = !busy, onClick = { run {
                val data = withContext(Dispatchers.IO) { store.export() }
                if (host.exportFile("cosmotune-listening.json", data)) "Backup saved" else null
            } })
            ToolButton("Import listening backup", enabled = !busy, kind = CosmoButtonKind.TONAL, onClick = { run {
                val data = host.importFile() ?: return@run null
                withContext(Dispatchers.IO) { store.import(data) }
                "Backup merged. Existing local entries were kept. Missing local files are skipped when you open a session."
            } })
            Text("Imports add missing entries by identity; importing the same file again does not duplicate them. Local edits take precedence. Use your platform’s existing library import for music playlists and media.", style = MaterialTheme.typography.bodySmall)
            Heading("Local playlists", "Export local audio files as a UTF-8 M3U8 playlist. File order and repeated tracks are preserved. Online-only songs, missing files and device-only content links are skipped; no stream addresses or account credentials are exported.")
            ToolButton("Export local queue to M3U8", enabled = !busy, tag = "listening:m3u8:queue", onClick = { run {
                val songs = host.queue().songs
                val export = withContext(Dispatchers.IO) { exportLocalM3u8("Queue", songs, localLibrary = host.library.songs) }
                if (host.exportFile("cosmotune-queue.m3u8", export.text)) "Playlist saved: ${export.included} local tracks, ${export.skipped} skipped" else null
            } })
            ToolButton("Export local library to M3U8", enabled = !busy, tag = "listening:m3u8:library", onClick = { run {
                val export = withContext(Dispatchers.IO) { exportLocalM3u8("Library", host.library.songs) }
                if (host.exportFile("cosmotune-library.m3u8", export.text)) "Playlist saved: ${export.included} local tracks, ${export.skipped} skipped" else null
            } })
            Text("The playlist refers to the current file locations. Copy the music and adjust paths when moving it to another device. Keep a listening backup for source metadata and sessions.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Heading(title: String, detail: String) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}

@Composable
private fun EmptyHint(title: String, detail: String, action: () -> Unit, close: () -> Unit) {
    Heading(title, detail)
    ToolButton("Find music", { close(); action() }, kind = CosmoButtonKind.TONAL)
}

@Composable
internal fun ToolButton(text: String, onClick: () -> Unit, enabled: Boolean = true, kind: CosmoButtonKind = CosmoButtonKind.PRIMARY, tag: String = "") {
    val modifier = Modifier.padding(vertical = 4.dp).then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier)
    if (LocalCosmoShell.current != null) CosmoButton(text, onClick, modifier, kind, enabled = enabled)
    else Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
}

private fun stamp(millis: Long): String = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

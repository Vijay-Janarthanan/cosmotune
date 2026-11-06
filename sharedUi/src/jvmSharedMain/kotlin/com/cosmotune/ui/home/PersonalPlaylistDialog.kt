package com.cosmotune.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.recommendations.*
import kotlinx.coroutines.launch

/** Both native roots save the reviewed order as an ordinary editable local playlist. */
@Composable
fun PersonalPlaylistDialog(songs: List<Song>, seed: Song?, onDismiss: () -> Unit,
    save: suspend (String, List<Song>) -> Unit) {
    val choices = remember(songs, seed) { (listOfNotNull(seed) + songs).asSequence().filter { !it.isVideo && it.videoId.isNotBlank() && !it.videoId.startsWith("podio:") }
        .distinctBy { it.videoId }.take(2_000).toList() }
    var selected by remember(choices) { mutableStateOf(choices.firstOrNull()) }
    var kind by remember { mutableStateOf(MusicPlaylistKind.DAILY_MIX) }
    var choosingKind by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Daily mix") }
    var size by remember { mutableFloatStateOf(30f) }
    var discover by remember { mutableStateOf(true) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(emptyList<Song>()) }
    var result by remember { mutableStateOf<MusicPlaylistPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val region by DiscoveryPreferences.state.collectAsState()
    val learning by LocalPreferenceLearning.status.collectAsState()
    val scope = rememberCoroutineScope()
    fun move(from: Int, to: Int) { preview = preview.toMutableList().apply { add(to, removeAt(from)) } }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = "Make my playlist",
        scroll = false,
        content = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a mix, review its sources, then edit the order before saving. Ranking stays on this device; online pools use your enabled catalogues.", style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { choosingKind = true }, enabled = !busy, modifier = Modifier.testTag("playlist:kind")) {
                    Text(kind.title); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(choosingKind, { choosingKind = false }) {
                    MusicPlaylistKind.entries.forEach { option -> DropdownMenuItem(text = { Text(option.title) }, onClick = {
                        kind = option; title = option.title; choosingKind = false; preview = emptyList(); result = null
                    }) }
                }
            }
            OutlinedTextField(title, { title = it.take(128) }, label = { Text("Playlist name") }, enabled = !busy, singleLine = true)
            if (kind == MusicPlaylistKind.SEED) Box {
                OutlinedButton(onClick = { choosing = true }, enabled = !busy && choices.isNotEmpty()) {
                    Text(selected?.let { "Based on ${it.title} · ${it.artist}" } ?: "Play or save a song to choose a starting point", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(choosing, { choosing = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    choices.take(100).forEach { song -> DropdownMenuItem(text = { Text("${song.title} · ${song.artist}") },
                        onClick = { selected = song; choosing = false; preview = emptyList() }) }
                }
            }
            Text("Up to ${size.toInt()} songs")
            Slider(size, { size = it; preview = emptyList() }, valueRange = 10f..50f, steps = 3, enabled = !busy)
            if (kind != MusicPlaylistKind.REDISCOVER) Row {
                Checkbox(discover, { discover = it; preview = emptyList() }, enabled = !busy)
                Text("Include available online discovery pools", modifier = Modifier.padding(top = 12.dp))
            }
            OutlinedButton(enabled = !busy && (kind != MusicPlaylistKind.SEED || selected != null), modifier = Modifier.testTag("playlist:generate"), onClick = {
                busy = true; message = null
                scope.launch {
                    try {
                        val existing = MusicCandidateService.context()
                        MusicCandidateService.updateContext(existing.copy(library = (choices + existing.library).distinctBy { it.videoId }))
                        result = MusicCandidateService.preview(kind, selected, size.toInt(), discover && kind != MusicPlaylistKind.REDISCOVER, region)
                        preview = result!!.songs
                        message = "${preview.size} recordings ready. Small or unavailable catalogues may produce fewer songs than requested."
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Could not build this mix. Your saved playlists are unchanged; try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Finding recordings…" else "Preview playlist") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            result?.let { generated ->
                Text(generated.explanation, style = MaterialTheme.typography.bodySmall)
                if (generated.unavailablePools.isNotEmpty()) Text("Some online pools were unavailable. This preview uses the sources that answered.", style = MaterialTheme.typography.bodySmall)
            }
            preview.forEachIndexed { index, song ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${song.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            result?.recordings?.firstOrNull { it.song.videoId == song.videoId }?.let { recording ->
                                Text(recording.explanation.ifBlank { "Available catalogue recording" } + if (recording.alternatives.size > 1) " · ${recording.alternatives.size} source listings" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { preview = preview.filterIndexed { row, _ -> row != index } }, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove ${song.title}") }
                    }
                    Row {
                        IconButton(onClick = { move(index, index - 1) }, enabled = !busy && index > 0) { Icon(Icons.Outlined.ArrowUpward, "Move ${song.title} up") }
                        IconButton(onClick = { move(index, index + 1) }, enabled = !busy && index < preview.lastIndex) { Icon(Icons.Outlined.ArrowDownward, "Move ${song.title} down") }
                        TextButton(onClick = { LocalPreferenceLearning.onFeedback(song, MusicFeedback.LESS_LIKE_THIS); preview = preview.filter { it.videoId != song.videoId } }, enabled = !busy && learning.ready && learning.enabled && learning.error == null) {
                            Text("Less like this")
                        }
                    }
                }
            }
        }
    },
        confirmLabel = "Save locally",
        onConfirm = {
            busy = true
            scope.launch {
                try { save(title.trim(), preview); onDismiss() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Could not save the playlist. Your edited preview is still here." }
                finally { busy = false }
            }
        },
        confirmEnabled = !busy && preview.isNotEmpty() && title.isNotBlank(),
        dismissLabel = "Close",
        onDismiss = onDismiss,
    )
}

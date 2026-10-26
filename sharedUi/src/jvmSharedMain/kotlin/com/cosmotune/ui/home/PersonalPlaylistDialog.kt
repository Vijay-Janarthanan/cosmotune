package com.cosmotune.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.recommendations.LocalRecommendations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PersonalPlaylistDialog(songs: List<Song>, seed: Song?, onDismiss: () -> Unit,
    save: suspend (String, List<Song>) -> Unit) {
    val choices = remember(songs, seed) { (listOfNotNull(seed) + songs).filter { !it.isVideo && it.videoId.isNotBlank() }.distinctBy { it.videoId }.take(2_000) }
    var selected by remember(choices) { mutableStateOf(choices.firstOrNull()) }
    var title by remember { mutableStateOf("Made for me") }
    var size by remember { mutableFloatStateOf(30f) }
    var discover by remember { mutableStateOf(true) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(emptyList<Song>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Make my playlist") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Rank your library and available catalogue suggestions with the tiny local model and listening preferences. Preview the results, then save an editable playlist.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it.take(128) }, label = { Text("Playlist name") }, enabled = !busy)
            Box {
                OutlinedButton(onClick = { choosing = true }, enabled = !busy && choices.isNotEmpty()) {
                    Text(selected?.let { "Based on ${it.title} · ${it.artist}" } ?: "Play or save a song to choose a starting point")
                }
                DropdownMenu(choosing, { choosing = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    choices.take(100).forEach { song -> DropdownMenuItem(text = { Text("${song.title} · ${song.artist}") },
                        onClick = { selected = song; choosing = false; preview = emptyList() }) }
                }
            }
            Text("Up to ${size.toInt()} songs")
            Slider(size, { size = it; preview = emptyList() }, valueRange = 10f..50f, steps = 3, enabled = !busy)
            Row { Checkbox(discover, { discover = it; preview = emptyList() }, enabled = !busy); Text("Include new catalogue discoveries", modifier = Modifier.padding(top = 12.dp)) }
            OutlinedButton(enabled = !busy && selected != null, modifier = Modifier.testTag("playlist:generate"), onClick = {
                val startingPoint = selected ?: return@OutlinedButton
                busy = true; message = null
                scope.launch {
                    try {
                        preview = LocalRecommendations.playlist(startingPoint, choices, size.toInt(), discover) { query ->
                            YtMusicRepository.search(query, SearchFilter.SONGS).getOrDefault(emptyList()).mapNotNull { (it as? SearchResult.Track)?.song }
                        }
                        message = "${preview.size} songs ready. Small libraries may produce fewer songs than requested."
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Could not build suggestions. Try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Working…" else "Preview playlist") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            preview.forEachIndexed { index, song -> Text("${index + 1}. ${song.title} · ${song.artist}", style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && preview.isNotEmpty() && title.isNotBlank(), modifier = Modifier.testTag("playlist:save"), onClick = {
            busy = true
            scope.launch {
                try { save(title.trim(), preview); onDismiss() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Could not save the playlist. Your preview is still here." }
                finally { busy = false }
            }
        }) { Text("Save locally") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

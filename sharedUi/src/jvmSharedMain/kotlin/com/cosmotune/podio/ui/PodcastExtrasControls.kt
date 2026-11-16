package com.cosmotune.podio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.*
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.*
import kotlinx.coroutines.CancellationException

/** Fetch publisher extras only when requested, keeping errors and retry attached to that episode. */
@Composable
internal fun PodcastExtrasControls(episode: PodcastEpisode, catalogue: PodioCatalogue, onSeek: (Long) -> Unit) {
    val fonts = orbitFonts(); val look = orbitLook()
    var selected by remember(episode.id) { mutableStateOf<Int?>(null) }
    var attempt by remember(episode.id) { mutableIntStateOf(0) }
    var chapters by remember(episode.id) { mutableStateOf<List<PodcastChapter>>(emptyList()) }
    var cues by remember(episode.id) { mutableStateOf<List<PodcastCue>>(emptyList()) }
    var error by remember(episode.id) { mutableStateOf<String?>(null) }
    var loading by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(episode.id, selected, attempt) {
        val kind = selected ?: return@LaunchedEffect
        loading = true; error = null
        try {
            if (kind == -1) chapters = catalogue.chapters(episode)
            else cues = catalogue.transcript(episode.transcripts[kind])
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The publisher's extra information could not be loaded." }
        finally { loading = false }
    }
    if (episode.chaptersUrl == null && episode.transcripts.isEmpty()) return
    OrbitChipRow(0.dp) {
        if (episode.chaptersUrl != null) OrbitSelectChip("Chapters", false, fonts, look, { selected = -1 }, haptic = false)
        episode.transcripts.forEachIndexed { index, source ->
            OrbitSelectChip("Transcript" + source.language.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), false,
                fonts, look, { selected = index }, haptic = false)
        }
    }
    selected?.let { kind ->
        KitSheet({ selected = null }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(if (kind == -1) "Chapters" else "Transcript", style = fonts.heroTitle, color = look.ink) }
                item { Text("Provided by the podcast publisher · tap to play from here", style = fonts.body, color = look.dim) }
                when {
                    loading -> item { Text("Loading…", style = fonts.body, color = look.dim) }
                    error != null -> item {
                        Text(error!!, style = fonts.body, color = look.dim)
                        OrbitSelectChip("Try again", false, fonts, look, { attempt++ }, haptic = false)
                    }
                    kind == -1 && chapters.isNotEmpty() -> itemsIndexed(chapters) { _, chapter ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSeek(chapter.startMs); selected = null }
                            .padding(vertical = 12.dp)) {
                            Text(PodioFormat.duration(chapter.startMs / 1000), style = fonts.body, color = look.dim)
                            Text(chapter.title, style = fonts.title, color = look.ink)
                        }
                    }
                    kind != -1 && cues.isNotEmpty() -> itemsIndexed(cues) { _, cue ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSeek(cue.startMs); selected = null }.padding(vertical = 12.dp)) {
                            Text(listOfNotNull(PodioFormat.duration(cue.startMs / 1000), cue.speaker).joinToString(" · "), style = fonts.body, color = look.dim)
                            Text(cue.text, style = fonts.body, color = look.ink)
                        }
                    }
                    else -> item { Text("The publisher has not supplied any entries.", style = fonts.body, color = look.dim) }
                }
            }
        }
    }
}

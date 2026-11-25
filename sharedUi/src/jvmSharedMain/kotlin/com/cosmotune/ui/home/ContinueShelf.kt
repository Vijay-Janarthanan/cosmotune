package com.cosmotune.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.PodioCarBrowse
import com.cosmotune.podio.PodioItem
import com.cosmotune.podio.PodioLibrary
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchRoute
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.TitleType
import java.io.File

class ContinueEntry(
    val title: String,
    val detail: String,
    val action: () -> Unit,
)

@Composable
fun watchContinueEntries(repository: WatchRepository, openWatch: () -> Unit): List<ContinueEntry> {
    val unlocked by repository.profileUnlocked.collectAsState()
    val entries by repository.continueWatching.collectAsState()
    if (!unlocked) return emptyList()
    return entries.asSequence().filter { it.episode != null || it.summary.type == TitleType.FILM }.take(2).map { entry ->
        val episode = entry.episode
        val series = entry.summary
        val target = if (episode != null) PlayTarget.EpisodeOf(episode.key, series.ids, series.name,
            episode.season, episode.number, episode.ids, episode.name, series.year) else repository.targetOf(series)
        ContinueEntry(series.name, listOfNotNull("Watch", entry.episodeLabel, "${entry.remainingMin} min left").joinToString(" · ")) {
            WatchNav.go(WatchTab.HOME)
            WatchNav.open(WatchRoute.Play(target, series, episode = episode))
            openWatch()
        }
    }.toList()
}

@Composable
fun podioContinueEntries(storage: File, playingId: String?, resume: (PodioItem, Long) -> Unit): List<ContinueEntry> {
    val library = remember(storage) { PodioLibrary(storage) }
    val queue = remember(library) { library.queue() }
    val saved by library.state.collectAsState()
    val queued by queue.state.collectAsState()
    return PodioCarBrowse.forYou(saved, queued).asSequence().filter { it.id != playingId }.mapNotNull { item ->
        val position = PodioCarBrowse.resumeAt(saved, item.id, item.durationSeconds * 1000L)
        if (position <= 0) null else ContinueEntry(item.title, "Podio · ${item.author}") { resume(item, position) }
    }.take(2).toList()
}

@Composable
internal fun ContinueShelf(entries: List<ContinueEntry>, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Continue", style = fonts.section, color = look.ink)
        for (entry in entries) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(entry.title, style = fonts.title, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.detail, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                KitTextButton("Resume", entry.action, Modifier.semantics { contentDescription = "Resume ${entry.title}" })
            }
        }
    }
}


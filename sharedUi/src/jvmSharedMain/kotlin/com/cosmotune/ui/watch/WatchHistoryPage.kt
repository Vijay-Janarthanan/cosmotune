package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.watch.HistoryFilter
import com.cosmotune.watch.WatchHistory
import com.cosmotune.watch.model.WatchProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * History (Aniyomi's idea): what this profile played, newest first under its day, with a filter (films, episodes, finished, left
 * part way). Select picks several to take out of the history or to mark as watched; a row opens its title.
 */
@Composable
internal fun WatchHistoryPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = actions.repository
    var changes by remember { mutableIntStateOf(0) }
    val history by produceState<List<WatchProgress>?>(null, changes) { value = runCatching { repository.history() }.getOrDefault(emptyList()) }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).toEpochDay()
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    val shown = WatchHistory.filter(history.orEmpty(), filter)
    val days = WatchHistory.byDay(shown, today, { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }) { LocalDate.ofEpochDay(it).format(dayFormat) }
    val picked = { shown.filter { it.key.value in selected } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        frame.header?.let { header -> item { header() } }
        item {
            Column(Modifier.padding(horizontal = frame.side).padding(top = 20.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitPageHeading("History", history?.let { if (it.size == 1) "1 title played" else "${it.size} played" } ?: "Reading what you played…", fonts, look)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryFilter.entries.forEach { f -> OrbitSelectChip(f.label, f == filter, fonts, look, onClick = { filter = f; selected.clear() }, haptic = false) }
                }
                if (shown.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selecting) {
                        WatchCardChip(if (selected.isEmpty()) "Remove" else "Remove ${selected.size}", look.ink, fonts, enabled = selected.isNotEmpty(), icon = CosmoIcons.Close, onClick = {
                            val gone = picked()
                            actions.launch { gone.forEach { repository.clearProgress(it.key) }; selected.clear(); selecting = false; changes++ }
                        })
                        WatchCardChip(if (selected.isEmpty()) "Mark watched" else "Mark ${selected.size} watched", look.ink, fonts, enabled = selected.isNotEmpty(), icon = CosmoIcons.Check, onClick = {
                            val marked = picked()
                            actions.launch {
                                marked.forEach { repository.setWatched(it.key, it.summary, true, System.currentTimeMillis(), it.episodeName) }
                                selected.clear(); selecting = false; changes++
                            }
                        })
                        Spacer(Modifier.weight(1f))
                        WatchCardChip("Done", look.ink, fonts, onClick = { selected.clear(); selecting = false })
                    } else {
                        Spacer(Modifier.weight(1f))
                        WatchCardChip("Select", look.ink, fonts, onClick = { selecting = true }, icon = CosmoIcons.Check)
                    }
                }
            }
        }
        if (history != null && shown.isEmpty()) item {
            WatchMessage("History", if (filter == HistoryFilter.ALL) "Nothing played yet" else "Nothing here", "Films and episodes you play show here, newest first.",
                fonts, look, Modifier.padding(horizontal = frame.side))
        }
        days.forEach { (day, entries) ->
            item(key = "day:$day") { OrbitLabel(day, fonts, look.dim, Modifier.padding(horizontal = frame.side).padding(start = 6.dp, top = 8.dp)) }
            items(entries, key = { it.key.value }) { p -> HistoryRow(p, p.key.value in selected, selecting, frame.side, fonts, look) {
                if (selecting) { if (p.key.value in selected) selected.remove(p.key.value) else selected.add(p.key.value) } else p.summary?.let(actions::open)
            } }
        }
        item { Spacer(Modifier.height(36.dp)) }
    }
}

@Composable
private fun HistoryRow(p: WatchProgress, picked: Boolean, selecting: Boolean, side: androidx.compose.ui.unit.Dp, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    val s = p.summary ?: return
    val time = remember(p.updatedAtMs) { Instant.ofEpochMilli(p.updatedAtMs).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")) }
    val episode = p.key.season?.let { season -> listOfNotNull("S$season · E${p.key.episode}", p.episodeName).joinToString(" · ") }
    val state = when { p.completed -> "Finished"; p.durationMs > 0 -> leftText(p.remainingMs); else -> null }
    WatchRowFrame(look, Modifier.padding(horizontal = side), height = 88.dp, highlighted = picked, onClick = onClick) {
        Box(Modifier.size(48.dp, 72.dp)) {
            WatchPicture(s.posterUrl ?: s.backdropUrl, s.key.value, Modifier.fillMaxSize(), radius = 12.dp)
            p.fraction.takeIf { it > 0f && !p.completed }?.let { WatchPictureProgress(it, look.accent) }
        }
        Column(Modifier.weight(1f)) {
            Text(s.name, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(episode, time, state).joinToString(" · "), style = fonts.body, color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selecting) Box(Modifier.size(26.dp).clip(CircleShape).background(if (picked) look.accent else look.ink.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (picked) androidx.compose.material3.Icon(CosmoIcons.Check, "Selected", tint = look.ground, modifier = Modifier.size(16.dp))
        }
    }
}

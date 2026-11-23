@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodcastShow
import com.cosmotune.podio.PodioSmartList
import com.cosmotune.podio.PodioSmartLists
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*

/** The episodes [list] keeps from Following's latest episodes. */
internal fun smartListEpisodes(m: PodioModel, env: PodioEnv, list: PodioSmartList): List<InboxEpisode> =
    PodioSmartLists.match(list, m.inbox.map { it.episode to it.show }, env.saved, env.downloaded.map { it.id }.toSet()).map { InboxEpisode(it.first, it.second) }

/**
 * Following's Lists: the listener's lists as chips with their counts and New list; under them what the chosen one keeps, its
 * rules in words and Edit. With no list yet, a ready one ("Under 30 minutes") is one tap away.
 */
internal fun LazyListScope.smartListItems(m: PodioModel, env: PodioEnv, side: Dp, fonts: OrbitFonts, look: OrbitLook, chosen: String?,
    onChoose: (String) -> Unit, onEdit: (PodioSmartList?) -> Unit) {
    val lists = env.saved.smartLists
    val list = lists.firstOrNull { it.id == chosen } ?: lists.firstOrNull()
    item(key = "lists") {
        OrbitChipRow(side, Modifier.padding(top = 10.dp)) {
            lists.forEach { each ->
                OrbitSelectChip(each.name, each.id == list?.id, fonts, look, { onChoose(each.id) }, haptic = false, count = smartListEpisodes(m, env, each).size,
                    modifier = Modifier.testTag("podio:list:${each.id}"))
            }
            if (lists.size < PodioSmartLists.MAX) OrbitSelectChip("New list", false, fonts, look, { onEdit(null) }, icon = CosmoIcons.Plus, haptic = false,
                modifier = Modifier.testTag("podio:list:new"))
        }
    }
    if (list == null) {
        item(key = "lists-empty") {
            Column(Modifier.padding(horizontal = side + 4.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Make a list that fills itself, like new episodes under 30 minutes, or what you have downloaded.", style = fonts.body, color = look.dim)
                OrbitSelectChip("Add \"Under 30 minutes\"", false, fonts, look, {
                    m.save { PodioSmartLists.put(it, PodioSmartList("quick-" + System.currentTimeMillis(), "Under 30 minutes", maxMinutes = 30)) }
                }, icon = CosmoIcons.Plus, haptic = false)
            }
        }
        return
    }
    val episodes = smartListEpisodes(m, env, list)
    item(key = "list-head") {
        Row(Modifier.fillMaxWidth().padding(start = side + 4.dp, end = side, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(PodioSmartLists.describe(list), style = fonts.body.copy(fontSize = 13.sp), color = look.dim, modifier = Modifier.weight(1f))
            PodioTextAction("Edit", look, fonts, { onEdit(list) }, Modifier.testTag("podio:list:edit"))
        }
    }
    if (episodes.isEmpty()) item(key = "list-none") {
        Text("Nothing here right now. It fills as your shows bring new episodes.", style = fonts.body, color = look.dim,
            modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 12.dp))
    }
    items(episodes, key = { "l-" + it.episode.id }) {
        EpisodeItemRow(m, env, it, withShow = true, fonts, look, Modifier.padding(horizontal = side))
    }
}

/** Makes or changes a list: its name, what it keeps (unplayed, started, downloaded), the length, the age and the shows. */
@Composable internal fun SmartListDialog(initial: PodioSmartList?, shows: List<PodcastShow>, onDismiss: () -> Unit, onSave: (PodioSmartList) -> Unit, onDelete: (() -> Unit)?) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var list by remember(initial?.id) { mutableStateOf(initial ?: PodioSmartList("list-" + System.currentTimeMillis(), "")) }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss, title = if (initial == null) "New list" else "Edit list",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitInputField(list.name, { list = list.copy(name = it.take(60)) }, "Name", modifier = Modifier.fillMaxWidth().testTag("podio:list:name"))
                OrbitLabel("Keep", fonts, look.dim, Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrbitSelectChip("Unplayed", list.unplayed, fonts, look, { list = list.copy(unplayed = !list.unplayed) }, haptic = false)
                    OrbitSelectChip("Started", list.started, fonts, look, { list = list.copy(started = !list.started) }, haptic = false)
                    OrbitSelectChip("Downloaded", list.downloaded, fonts, look, { list = list.copy(downloaded = !list.downloaded) }, haptic = false)
                }
                OrbitLabel("Words", fonts, look.dim, Modifier.padding(top = 8.dp))
                OrbitInputField(list.words, { list = list.copy(words = it.take(200)) }, "interview -trailer", modifier = Modifier.fillMaxWidth().testTag("podio:list:words"))
                Text("Episodes that say these words in their title or notes; a word after - leaves those out.",
                    style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim)
                OrbitLabel("Length", fonts, look.dim, Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PodioSmartLists.LENGTHS.forEach { minutes ->
                        OrbitSelectChip(if (minutes == 0) "Any" else "$minutes min or less", list.maxMinutes == minutes, fonts, look, { list = list.copy(maxMinutes = minutes) }, haptic = false)
                    }
                }
                OrbitLabel("Released", fonts, look.dim, Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PodioSmartLists.AGES.forEach { days ->
                        OrbitSelectChip(when (days) { 0 -> "Any time"; 1 -> "Today"; 7 -> "This week"; else -> "This month" }, list.withinDays == days, fonts, look,
                            { list = list.copy(withinDays = days) }, haptic = false)
                    }
                }
                if (shows.isNotEmpty()) {
                    OrbitLabel("Shows", fonts, look.dim, Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrbitSelectChip("Every show", list.shows.isEmpty(), fonts, look, { list = list.copy(shows = emptySet()) }, haptic = false)
                        shows.forEach { show ->
                            val on = show.id in list.shows
                            OrbitSelectChip(show.title.ifBlank { "Podcast" }.take(32), on, fonts, look,
                                { list = list.copy(shows = if (on) list.shows - show.id else list.shows + show.id) }, haptic = false)
                        }
                    }
                }
                if (onDelete != null) PodioTextAction("Delete this list", look, fonts, onDelete, Modifier.padding(top = 8.dp))
            }
        },
        confirmLabel = "Save", onConfirm = { onSave(list) }, dismissLabel = "Cancel", onDismiss = onDismiss,
    )
}

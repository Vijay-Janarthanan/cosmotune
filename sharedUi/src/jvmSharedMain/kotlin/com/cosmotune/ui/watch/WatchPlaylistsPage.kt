package com.cosmotune.ui.watch

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*
import com.cosmotune.watch.model.TitleSummary

@Composable
internal fun WatchPlaylistsPage(id: String?, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val playlists by actions.repository.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    var editName by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        frame.header?.let { header -> item { header() } }
        item {
            Column(Modifier.padding(horizontal = frame.side).padding(top = 20.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitPageHeading(playlist?.name ?: "Watch playlists", if (id == null) "Your films and series, arranged your way" else "${playlist?.items?.size ?: 0} saved titles", fonts, look)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchCardChip(if (id == null) "New playlist" else "Rename", look.ink, fonts, onClick = { name = playlist?.name.orEmpty(); editName = true })
                    if (playlist != null) WatchCardChip("Delete playlist", look.ink, fonts, onClick = { remove = true })
                }
                error?.let { Text(it, style = fonts.body, color = look.dim) }
            }
        }
        if (id == null) {
            if (playlists.isEmpty()) item { WatchMessage("Playlists", "Start a collection", "Add films and series from the More menu on a title page.", fonts, look, Modifier.padding(horizontal = frame.side)) }
            items(playlists, key = { it.id }) { entry ->
                WatchRowFrame(look, Modifier.padding(horizontal = frame.side), height = 84.dp, onClick = { WatchNav.open(WatchRoute.Playlists(entry.id)) }) {
                    WatchPicture(entry.items.firstOrNull()?.posterUrl, entry.id, Modifier.size(52.dp, 68.dp), radius = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${entry.items.size} titles", style = fonts.body, color = look.dim)
                    }
                    WatchRoundButton(CosmoIcons.ChevronRight, "Open ${entry.name}", look, onClick = { WatchNav.open(WatchRoute.Playlists(entry.id)) })
                }
            }
        } else {
            if (playlist?.items.isNullOrEmpty()) item { WatchMessage("Playlist", "No titles here yet", "Use Add to playlist on a film or series page.", fonts, look, Modifier.padding(horizontal = frame.side)) }
            items(playlist?.items.orEmpty(), key = { it.key.value }) { title ->
                var menu by remember { mutableStateOf(false) }
                WatchRowFrame(look, Modifier.padding(horizontal = frame.side), height = 88.dp, onClick = { actions.open(title) }) {
                    WatchPicture(title.posterUrl, title.key.value, Modifier.size(48.dp, 72.dp), radius = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(title.name, style = fonts.title, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(posterLine(title).orEmpty(), style = fonts.body, color = look.dim)
                    }
                    WatchRoundButton(CosmoIcons.Play, "Play ${title.name}", look, onClick = { actions.play(title) })
                    Box {
                        WatchRoundButton(CosmoIcons.More, "Options for ${title.name}", look, onClick = { menu = true })
                        OrbitMenu(menu, { menu = false }, fonts = fonts, look = look, items = listOf(OrbitMenuItem("Remove from playlist") {
                            actions.launch { runCatching { actions.repository.setPlaylistItem(id, title, false) }.onFailure { error = "The playlist could not be updated" } }
                        }))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(36.dp)) }
    }
    if (editName) com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { editName = false }, title = if (id == null) "New Watch playlist" else "Rename playlist",
        confirmLabel = "Save", dismissLabel = "Cancel", confirmEnabled = name.isNotBlank(), onConfirm = { actions.launch {
            runCatching { if (id == null) actions.repository.createPlaylist(name) else actions.repository.renamePlaylist(id, name) }.onFailure { error = "The playlist could not be saved" }
            editName = false
        } }) { WatchField("Playlist name", name, { name = it.take(100) }, fonts, look) }
    if (remove) com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { remove = false }, title = "Delete this playlist?", message = "Its titles remain in your other playlists and My list.",
        confirmLabel = "Delete", dismissLabel = "Cancel", onConfirm = { actions.launch {
            runCatching { id?.let { actions.repository.deletePlaylist(it) } }.onSuccess { WatchNav.back() }.onFailure { error = "The playlist could not be deleted" }
            remove = false
        } })
}

@Composable
internal fun WatchPlaylistPicker(title: TitleSummary, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, onDismiss: () -> Unit) {
    val playlists by actions.repository.playlists.collectAsState()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = onDismiss, title = "Add to Watch playlist", message = title.name, dismissLabel = "Done") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            playlists.forEach { playlist ->
                val included = playlist.items.any { it.key == title.key }
                OrbitSelectChip(playlist.name, included, fonts, look, onClick = { actions.launch {
                    runCatching { actions.repository.setPlaylistItem(playlist.id, title, !included) }.onFailure { error = "The playlist could not be updated" }
                } }, haptic = false)
            }
            WatchField("New playlist name", name, { name = it.take(100) }, fonts, look)
            WatchCardChip("Create and add", look.ink, fonts, enabled = name.isNotBlank(), onClick = { actions.launch {
                runCatching { actions.repository.createPlaylist(name)?.let { actions.repository.setPlaylistItem(it, title, true); name = "" } }.onFailure { error = "The playlist could not be created" }
            } }, icon = CosmoIcons.Plus)
            error?.let { Text(it, style = fonts.body, color = look.dim) }
        }
    }
}

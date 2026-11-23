package com.cosmotune.ui.watch

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    var editCover by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf("") }
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
                    if (playlist != null) WatchCardChip("Cover", look.ink, fonts, onClick = { cover = playlist.posterUrl.orEmpty(); editCover = true })
                    if (playlist != null) WatchCardChip("Delete playlist", look.ink, fonts, onClick = { remove = true })
                }
                if (id == null && playlists.size > 1 || (playlist?.items?.size ?: 0) > 1) Text("Drag the handle to change the order.", style = fonts.body, color = look.dim)
                error?.let { Text(it, style = fonts.body, color = look.dim) }
            }
        }
        if (id == null) {
            if (playlists.isEmpty()) item { WatchMessage("Playlists", "Start a collection", "Add films and series from the More menu on a title page.", fonts, look, Modifier.padding(horizontal = frame.side)) }
            itemsIndexed(playlists, key = { _, it -> it.id }) { index, entry ->
                val drag = remember(entry.id) { mutableFloatStateOf(0f) }
                WatchRowFrame(look, Modifier.padding(horizontal = frame.side).reorderShift(drag), height = 84.dp, onClick = { WatchNav.open(WatchRoute.Playlists(entry.id)) }) {
                    if (playlists.size > 1) ReorderHandle(entry.name, index, playlists.size, 94.dp, drag, look) { to -> actions.launch { actions.repository.movePlaylist(entry.id, to) } }
                    WatchPicture(entry.cover, entry.id, Modifier.size(52.dp, 68.dp), radius = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${entry.items.size} titles", style = fonts.body, color = look.dim)
                    }
                    WatchRoundButton(CosmoIcons.ChevronRight, "Open ${entry.name}", look, onClick = { WatchNav.open(WatchRoute.Playlists(entry.id)) })
                }
            }
        } else {
            if (playlist?.items.isNullOrEmpty()) item { WatchMessage("Playlist", "No titles here yet", "Use Add to playlist on a film or series page.", fonts, look, Modifier.padding(horizontal = frame.side)) }
            val list = playlist?.items.orEmpty()
            itemsIndexed(list, key = { _, it -> it.key.value }) { index, title ->
                var menu by remember { mutableStateOf(false) }
                val drag = remember(title.key) { mutableFloatStateOf(0f) }
                val move: (Int) -> Unit = { to -> actions.launch { runCatching { actions.repository.movePlaylistItem(id, index, to) }.onFailure { error = "The playlist could not be updated" } } }
                WatchRowFrame(look, Modifier.padding(horizontal = frame.side).reorderShift(drag), height = 88.dp, onClick = { actions.open(title) }) {
                    if (list.size > 1) ReorderHandle(title.name, index, list.size, 98.dp, drag, look, move)
                    WatchPicture(title.posterUrl, title.key.value, Modifier.size(48.dp, 72.dp), radius = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(title.name, style = fonts.title, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(posterLine(title).orEmpty(), style = fonts.body, color = look.dim)
                    }
                    WatchRoundButton(CosmoIcons.Play, "Play ${title.name}", look, onClick = { actions.play(title) })
                    Box {
                        WatchRoundButton(CosmoIcons.More, "Options for ${title.name}", look, onClick = { menu = true })
                        OrbitMenu(menu, { menu = false }, fonts = fonts, look = look, items = listOf(
                            OrbitMenuItem("Move up", null, enabled = index > 0) { move(index - 1) },
                            OrbitMenuItem("Move down", null, enabled = index < list.lastIndex) { move(index + 1) },
                            OrbitMenuItem("Remove from playlist") {
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
    if (editCover && id != null) com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { editCover = false }, title = "Playlist cover",
        message = "The address of a picture on the web. Leave it empty to use the first title's poster.",
        confirmLabel = "Save", dismissLabel = "Cancel", onConfirm = { actions.launch {
            val saved = runCatching { actions.repository.setPlaylistPoster(id, cover.ifBlank { null }) }.getOrDefault(false)
            error = if (saved) null else "That is not a picture address"
            editCover = false
        } }) { WatchField("Picture address", cover, { cover = it.take(2_000) }, fonts, look, placeholder = "https://…") }
    if (remove) com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { remove = false }, title = "Delete this playlist?", message = "Its titles remain in your other playlists and My list.",
        confirmLabel = "Delete", dismissLabel = "Cancel", onConfirm = { actions.launch {
            runCatching { id?.let { actions.repository.deletePlaylist(it) } }.onSuccess { WatchNav.back() }.onFailure { error = "The playlist could not be deleted" }
            remove = false
        } })
}

/** The row follows the finger while its handle is dragged. */
private fun Modifier.reorderShift(drag: MutableFloatState): Modifier =
    this.zIndex(if (drag.floatValue != 0f) 1f else 0f).graphicsLayer { translationY = drag.floatValue }

/** A drag handle: the row moves with it and lands, on release, as many places up or down as it was dragged ([step] a row and its gap). */
@Composable
private fun ReorderHandle(name: String, index: Int, count: Int, step: androidx.compose.ui.unit.Dp, drag: MutableFloatState, look: OrbitLook, onMove: (Int) -> Unit) {
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { step.toPx() }
    val latestIndex by rememberUpdatedState(index)
    val latestMove by rememberUpdatedState(onMove)
    WatchRoundButton(CosmoIcons.List, "Drag to move $name", look, onClick = {}, modifier = Modifier.pointerInput(count) {
        detectVerticalDragGestures(
            onDragEnd = {
                val places = kotlin.math.round(drag.floatValue / stepPx).toInt()
                drag.floatValue = 0f
                val to = (latestIndex + places).coerceIn(0, count - 1)
                if (to != latestIndex) latestMove(to)
            },
            onDragCancel = { drag.floatValue = 0f },
        ) { change, dy -> change.consume(); drag.floatValue = (drag.floatValue + dy).coerceIn(-latestIndex * stepPx, (count - 1 - latestIndex) * stepPx) }
    })
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

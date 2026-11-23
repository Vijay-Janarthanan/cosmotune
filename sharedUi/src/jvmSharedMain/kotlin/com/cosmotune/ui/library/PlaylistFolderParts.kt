package com.cosmotune.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.PlaylistFolder
import com.cosmotune.listening.PlaylistFolders
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitCheckRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoAlertDialog

/*
 * Playlist folders in Library, Playlists (first seen in OuterTune, see NOTICE.md): folders as rows above the loose playlists, a
 * folder opened as its own short page with a way back, and the dialogs that name a folder and choose what it holds. The folders
 * are this device's own, kept in the extras settings; a playlist sits in one folder at most.
 */

/** What the Playlists chip needs to draw and change the folders. */
internal class FolderPage(
    val folders: List<PlaylistFolder>,
    val open: String?,
    val onOpen: (String?) -> Unit,
    val onNew: () -> Unit,
    val onRename: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onChoose: (String) -> Unit,
)

/** The dialog the page has open over the folders, if any. */
internal sealed interface FolderDialog {
    data object New : FolderDialog
    data class Rename(val name: String) : FolderDialog
    data class Choose(val name: String) : FolderDialog
}

internal fun playlistsText(count: Int): String = if (count == 1) "1 playlist" else "$count playlists"

/** One folder as a row: the folder mark, its name over how many playlists it holds, and a more button with Rename and Delete. */
@Composable
internal fun PlaylistFolderRow(name: String, count: Int, fonts: OrbitFonts, look: OrbitLook, side: Dp, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    OrbitSongRow(
        title = name,
        subtitle = if (count == 0) "Empty folder" else playlistsText(count),
        cover = null,
        fonts = fonts,
        look = look,
        onClick = onOpen,
        onLongClick = { menu = true },
        modifier = Modifier.padding(horizontal = side).fillMaxWidth().testTag("music:library:folder"),
        discContent = {
            Box(Modifier.size(44.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = look.ink, modifier = Modifier.size(20.dp))
            }
        },
        trailing = {
            Box {
                CosmoIconButton(CosmoIcons.MoreVertical, "More for $name", onClick = { menu = true }, size = 40.dp, tint = look.dim)
                OrbitMenu(
                    open = menu,
                    onDismiss = { menu = false },
                    items = listOf(
                        OrbitMenuItem("Rename folder", onClick = onRename),
                        OrbitMenuItem("Delete folder", detail = "Its playlists stay in your library", onClick = onDelete),
                    ),
                    fonts = fonts,
                    look = look,
                    header = name,
                    width = 260.dp,
                )
            }
        },
    )
}

/** The head of an opened folder: back to every playlist, the folder's name and count, and Choose playlists. */
@Composable
internal fun PlaylistFolderBar(name: String, count: Int, fonts: OrbitFonts, look: OrbitLook, side: Dp, onBack: () -> Unit, onChoose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 14.dp).testTag("music:library:folder-bar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CosmoIconButton(CosmoIcons.ArrowLeft, "All playlists", onClick = onBack, size = 40.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(name, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (count == 0) "Empty folder" else playlistsText(count), style = fonts.body, color = look.dim, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        OrbitPillButton("Choose playlists", onClick = onChoose, fonts = fonts, look = look, icon = CosmoIcons.Plus, modifier = Modifier.testTag("music:library:folder-choose"))
    }
}

/** New folder or Rename folder: one name field; Save is off while the name is blank or another folder's. */
@Composable
internal fun PlaylistFolderNameDialog(folders: List<PlaylistFolder>, renaming: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var name by remember(renaming) { mutableStateOf(renaming.orEmpty()) }
    val tidy = name.trim()
    val taken = folders.any { it.name != renaming && it.name.equals(tidy, ignoreCase = true) }
    val full = renaming == null && folders.size >= PlaylistFolders.MAX
    val ok = tidy.isNotEmpty() && !taken && !full
    CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = if (renaming == null) "New folder" else "Rename folder",
        message = when {
            full -> "You have ${PlaylistFolders.MAX} folders, the most there can be."
            taken -> "You already have a folder called $tidy."
            else -> "Folders keep your playlists in groups on this device."
        },
        confirmLabel = "Save",
        confirmEnabled = ok,
        onConfirm = { onSave(tidy) },
        dismissLabel = "Cancel",
    ) {
        OrbitTextField(
            name, { name = it.take(PlaylistFolders.MAX_NAME) }, "Folder name", fonts, look,
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("music:library:folder-name"),
            onDone = { if (ok) onSave(tidy) },
        )
    }
}

/**
 * Choose playlists for the folder [name]: every playlist with a tick, those already in it ticked; one in another folder says so
 * and moves here when ticked.
 */
@Composable
internal fun PlaylistFolderChooseDialog(name: String, playlists: List<ShelfItem>, folders: List<PlaylistFolder>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val choosable = remember(playlists) { playlists.filter { it.browseId != null }.distinctBy { it.browseId } }
    var chosen by remember(name) { mutableStateOf(folders.firstOrNull { it.name == name }?.playlistIds.orEmpty().toSet()) }
    CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = "Choose playlists",
        message = if (choosable.isEmpty()) "Make or save a playlist first." else "Ticked playlists go in $name.",
        confirmLabel = "Save",
        onConfirm = { onSave(choosable.mapNotNull { it.browseId }.filter { it in chosen }) },
        dismissLabel = "Cancel",
        scroll = false,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (item in choosable) {
                val id = item.browseId ?: continue
                val elsewhere = PlaylistFolders.folderOf(folders, id)?.takeIf { it != name }
                OrbitCheckRow(
                    title = if (elsewhere != null) "${item.title} · in $elsewhere" else item.title,
                    checked = id in chosen,
                    onChange = { on -> chosen = if (on) chosen + id else chosen - id },
                    modifier = Modifier.testTag("music:library:folder-pick"),
                )
            }
        }
    }
}

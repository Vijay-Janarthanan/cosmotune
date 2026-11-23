package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioBookmark
import com.cosmotune.podio.PodioBookmarks
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*

/**
 * One bookmark: its time in peach, the note (or the episode, in the list of every bookmark), and at its end Share (the episode's
 * address at that time) and Remove. A tap plays the episode from there; a hold writes a note.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun BookmarkRow(mark: PodioBookmark, withEpisode: Boolean, fonts: OrbitFonts, look: OrbitLook, onPlay: () -> Unit, onNote: () -> Unit,
    onShare: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(look.row)
        .combinedClickable(onClick = onPlay, onLongClick = onNote, onClickLabel = "Play from here")
        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp).testTag("podio:bookmark:${mark.key}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(PodioFormat.clock(mark.positionMs), style = fonts.label.copy(fontSize = 13.sp), color = look.accent, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
        // 12 dp between the words and the row's buttons, 8 dp between the buttons.
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            val first = if (withEpisode) mark.episodeTitle.ifBlank { "Episode" } else mark.note.ifBlank { "Bookmark" }
            Text(first, style = fonts.body, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val second = if (withEpisode) PodioFormat.episodeMeta(mark.showTitle, mark.note) else ""
            if (second.isNotBlank()) Text(second, style = fonts.body.copy(fontSize = 13.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (mark.url.isNotBlank()) OrbitRoundButton(CosmoIcons.Share, "Copy the link at this time", look.ink, onShare, size = 36.dp)
        OrbitRoundButton(CosmoIcons.Close, "Remove bookmark", look.ink, onRemove, size = 36.dp)
    }
}

/** Writes a bookmark's note. */
@Composable internal fun BookmarkNoteDialog(mark: PodioBookmark, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(mark.key) { mutableStateOf(mark.note) }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss, title = "Bookmark at ${PodioFormat.clock(mark.positionMs)}",
        content = { OrbitInputField(text, { text = it.take(300) }, "A note (optional)", modifier = Modifier.fillMaxWidth()) },
        confirmLabel = "Save", onConfirm = { onSave(text) }, dismissLabel = "Cancel", onDismiss = onDismiss,
    )
}

/** [marks] as rows with the note dialog, each acting through [m]. */
@Composable internal fun BookmarkList(m: PodioModel, marks: List<PodioBookmark>, withEpisode: Boolean, modifier: Modifier = Modifier, onPlayed: () -> Unit = {}) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var noting by remember { mutableStateOf<PodioBookmark?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        marks.forEach { mark ->
            BookmarkRow(mark, withEpisode, fonts, look, onPlay = { m.playBookmark(mark); onPlayed() }, onNote = { noting = mark },
                onShare = { m.host.copyText(PodioBookmarks.linkAt(mark.url, mark.positionMs)); m.notice = "Copied the episode's link at ${PodioFormat.clock(mark.positionMs)}." },
                onRemove = { m.removeBookmark(mark) })
        }
    }
    noting?.let { mark -> BookmarkNoteDialog(mark, { noting = null }) { note -> m.save { PodioBookmarks.note(it, mark.key, note) }; noting = null } }
}

package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.podio.PodcastChapter
import com.cosmotune.settings.AbLoop
import com.cosmotune.settings.Extras
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.settings.SongMark
import com.cosmotune.settings.SongMarks
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.settings.CosmoSettingsChip
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.sheets.CosmoAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bookmarks (Namida's idea): keep moments and parts of a song, repeat a part from A to B, and jump to a local file's own
 * chapters. The loop itself is kept by the player on each device ([ExtrasSettings.abLoop]), so it holds with this closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SongMarksDialog(
    song: Song,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    settings: ExtrasSettings = Extras.settings,
) {
    val id = song.videoId
    val all by settings.songMarks.collectAsState()
    val marks = all[id].orEmpty()
    val loop by settings.abLoop.collectAsState()
    val mine = loop?.takeIf { it.songId == id }
    var name by remember(id) { mutableStateOf("") }
    val chapters by produceState(emptyList<PodcastChapter>(), id) {
        value = withContext(Dispatchers.IO) { runCatching { PlayerPlatform.host.localChapters(song) }.getOrDefault(emptyList()) }
    }
    fun keep(mark: SongMark) { settings.addSongMark(id, mark.copy(label = name.trim())); name = "" }

    CosmoAlertDialog(onDismissRequest = onDismiss, title = "Bookmarks", confirmLabel = "Done", onConfirm = onDismiss, content = {
        OrbitInputField(name, { name = it.take(60) }, "Name for the next mark (optional)", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Spacer(Modifier.height(12.dp))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CosmoSettingsChip("Mark this moment", false, { keep(SongMark(positionMs())) }, Modifier.testTag("marks:moment"))
        }
        Spacer(Modifier.height(16.dp))
        // A–B repeat: A here, play on, B here; the part then repeats until it is stopped or another song plays.
        CosmoSettingsRow(icon = CosmoIcons.Repeat, title = "Repeat a part", subtitle = when {
            mine == null -> "Set A where the part starts, let it play, then set B where it ends."
            mine.endMs == null -> "A is at ${SongMarks.time(mine.startMs)}. Set B where the part ends."
            else -> "Repeating ${SongMarks.time(mine.startMs)} to ${SongMarks.time(mine.endMs ?: 0)}."
        })
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                mine == null -> CosmoSettingsChip("Set A here", false, { settings.setAbLoop(AbLoop(id, positionMs(), null)) }, Modifier.testTag("marks:a"))
                mine.endMs == null -> {
                    // Closer than a second to A is not a part yet: the tap waits for the song to move on.
                    CosmoSettingsChip("Set B here", false, { AbLoop.of(id, mine.startMs, positionMs())?.let(settings::setAbLoop) }, Modifier.testTag("marks:b"))
                    CosmoSettingsChip("Cancel", false, { settings.setAbLoop(null) })
                }
                else -> {
                    CosmoSettingsChip("Keep this part", false, { keep(SongMark(mine.startMs, mine.endMs)) })
                    CosmoSettingsChip("Stop repeating", true, { settings.setAbLoop(null) }, Modifier.testTag("marks:stop"))
                }
            }
        }
        if (marks.isEmpty() && chapters.isEmpty()) {
            CosmoSettingsRow(title = "No bookmarks yet", subtitle = "Marks you keep for this song are listed here. Tap one to go back to it.")
        }
        marks.forEachIndexed { index, mark ->
            val end = mark.endMs
            CosmoSettingsRow(
                icon = if (end != null) CosmoIcons.Repeat else CosmoIcons.Timer,
                title = mark.label.ifBlank { if (end != null) "Part ${index + 1}" else "Moment ${index + 1}" },
                subtitle = if (end != null) "${SongMarks.time(mark.startMs)} to ${SongMarks.time(end)}, tap to repeat" else SongMarks.time(mark.startMs),
                onClick = {
                    settings.setAbLoop(end?.let { AbLoop.of(id, mark.startMs, it) })
                    onSeek(mark.startMs)
                },
                trailing = { CosmoIconButton(CosmoIcons.Close, "Remove this bookmark", { settings.removeSongMark(id, mark) }, size = 40.dp) },
            )
        }
        if (chapters.isNotEmpty()) {
            CosmoSettingsRow(icon = CosmoIcons.Lyrics, title = "Chapters in the file")
            chapters.forEach { chapter ->
                CosmoSettingsRow(title = chapter.title.ifBlank { SongMarks.time(chapter.startMs) }, subtitle = SongMarks.time(chapter.startMs),
                    onClick = { onSeek(chapter.startMs) })
            }
        }
    })
}

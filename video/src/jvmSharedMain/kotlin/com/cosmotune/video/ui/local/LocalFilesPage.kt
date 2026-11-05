package com.cosmotune.video.ui.local

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.local.LocalRecent
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.player.ScrubberGeometry
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_local_clear
import com.cosmotune.video.resources.video_local_dimmed
import com.cosmotune.video.resources.video_local_empty
import com.cosmotune.video.resources.video_local_missing
import com.cosmotune.video.resources.video_local_open
import com.cosmotune.video.resources.video_local_recent
import com.cosmotune.video.resources.video_local_remove
import com.cosmotune.video.resources.video_local_resume
import com.cosmotune.video.resources.video_local_title
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.video.ui.library.LibraryHeader
import com.cosmotune.video.ui.library.LibraryList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * The page of [com.cosmotune.video.nav.VideoRoute.LocalFiles]: the files the local player has opened, newest first, under a heading with
 * the button that opens another (or several: they play one after the other). A row shows the title and where the file was left; a file
 * that is no longer on the device is dimmed, and a tap on it removes it with a notice; the cross on a row forgets it. [contentPadding]
 * and [wide] are the nav host's.
 */
@Composable
fun LocalFilesPage(contentPadding: PaddingValues, wide: Boolean) {
    val store = rememberLocalRecentStore()
    val access = rememberLocalFileAccess()
    val opener = rememberLocalVideoOpener()
    val picker = rememberVideoFilePicker(onVideos = { opener.open(it) }, onSubtitle = {})
    val recents by store.entries.collectAsState()
    val notice = LocalPlayerSession.missingNotice
    // Every file is taken to be there until the folder listing says otherwise (off the main thread: a network drive can be slow).
    val present by produceState(recents.map { it.id }.toSet(), recents) {
        value = withContext(Dispatchers.IO) { recents.filter { access.exists(it.source) }.map { it.id }.toSet() }
    }

    LibraryList(contentPadding, wide) { cell ->
        item(key = "heading") {
            LibraryHeader(stringResource(Res.string.video_local_title), wide, cell.padding(bottom = HEADING_BOTTOM)) {
                CosmoIconButton(
                    CosmoIcons.Video,
                    stringResource(Res.string.video_local_open),
                    { picker.openVideos() },
                    style = CosmoIconButtonStyle.TONAL,
                    size = 40.dp,
                )
            }
        }
        if (notice) {
            item(key = "notice") {
                CosmoListRow(
                    title = stringResource(Res.string.video_local_missing),
                    onClick = { LocalPlayerSession.missingNotice = false },
                    modifier = cell,
                    leading = CosmoRowLeading.Icon(CosmoIcons.Close),
                )
            }
        }
        if (recents.isEmpty()) {
            item(key = "empty") { EmptyText(stringResource(Res.string.video_local_empty), cell) }
        } else {
            item(key = "recent") { CosmoSectionHeader(stringResource(Res.string.video_local_recent), cell) }
            items(recents, key = { it.id }) { entry ->
                RecentRow(entry, present = entry.id in present, onClick = { opener.openRecent(entry) }, onRemove = { store.remove(entry.id) }, modifier = cell)
            }
            item(key = "clear") {
                CosmoListRow(
                    title = stringResource(Res.string.video_local_clear),
                    onClick = { store.clear() },
                    modifier = cell,
                    leading = CosmoRowLeading.Icon(CosmoIcons.Close),
                )
            }
        }
    }
}

/** One recent file: a video tile, its title, where it was left (or that it is gone), and a cross that forgets it. */
@Composable
internal fun RecentRow(entry: LocalRecent, present: Boolean, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    CosmoListRow(
        title = entry.title,
        onClick = onClick,
        modifier = modifier.alpha(if (present) 1f else DIMMED_ALPHA),
        subtitle = recentSubtitle(entry, present),
        leading = CosmoRowLeading.Icon(CosmoIcons.Video),
        trailing = {
            CosmoIconButton(
                CosmoIcons.Close,
                stringResource(Res.string.video_local_remove),
                onRemove,
                style = CosmoIconButtonStyle.PLAIN,
                size = 36.dp,
            )
        },
    )
}

/** "Resume at 12:34" for a file left part-way, its length for one not started, "Not on this device any more" for one that is gone. */
@Composable
internal fun recentSubtitle(entry: LocalRecent, present: Boolean): String? = when {
    !present -> stringResource(Res.string.video_local_dimmed)
    entry.positionMs >= LocalVideo.MIN_RESUME_MS && (entry.durationMs <= 0 || entry.positionMs < entry.durationMs - LocalVideo.END_MARGIN_MS) ->
        stringResource(Res.string.video_local_resume, ScrubberGeometry.timeLabel(entry.positionMs))
    entry.durationMs > 0 -> ScrubberGeometry.timeLabel(entry.durationMs)
    else -> null
}

@Composable
private fun EmptyText(text: String, modifier: Modifier) {
    val palette = LocalCosmoColors.current.palette
    Text(text = text, color = Color(palette.textSecondary), modifier = modifier.padding(vertical = EMPTY_PAD))
}

private val HEADING_BOTTOM = 10.dp
private val EMPTY_PAD = 24.dp
private const val DIMMED_ALPHA = 0.45f

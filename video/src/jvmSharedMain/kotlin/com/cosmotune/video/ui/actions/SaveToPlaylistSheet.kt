package com.cosmotune.video.ui.actions

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.CosmoPickRow
import com.cosmotune.ui.sheets.CosmoSheetGap
import com.cosmotune.ui.sheets.CosmoSheetHeading
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.library.LocalPlaylist
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_count_videos
import com.cosmotune.video.resources.video_new_playlist
import com.cosmotune.video.resources.video_save_to_playlist
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Save to a playlist" (spec 5.12): a sheet (layer C, part two's sheet surface) with the heading, a "New playlist" row ([onNewPlaylist]:
 * the host closes this sheet and opens [NewPlaylistDialog]) and your playlists as rows with a check: a tap saves [item] to that
 * playlist, or takes it out when it is there, at once (a video often belongs in more than one). [onDismiss] closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToPlaylistSheet(item: VideoItem, onNewPlaylist: () -> Unit, onDismiss: () -> Unit) {
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SaveToPlaylistBody(item, onNewPlaylist)
    }
}

/** The sheet's content without its host (the renders lay it on the sheet surface directly). */
@Composable
internal fun ColumnScope.SaveToPlaylistBody(item: VideoItem, onNewPlaylist: () -> Unit) {
    val library = LocalVideoGraph.current.library
    val playlists by library.playlists.collectAsState()
    CosmoSheetHeading(stringResource(Res.string.video_save_to_playlist))
    CosmoActionRow(icon = CosmoIcons.Plus, label = stringResource(Res.string.video_new_playlist), onClick = onNewPlaylist)
    if (playlists.isNotEmpty()) {
        CosmoSheetGap(menu = false)
        // Capped, so a long list scrolls inside the sheet instead of pushing it past the screen.
        LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
            items(playlists, key = { it.id }) { playlist ->
                val saved = playlist.holds(item.id)
                CosmoPickRow(
                    title = playlist.name,
                    subtitle = pluralStringResource(Res.plurals.video_count_videos, playlist.items.size, playlist.items.size),
                    imageUrl = playlist.items.firstOrNull()?.thumbnailUrl,
                    selected = saved,
                    onClick = { toggleInPlaylist(library, playlist.id, item, saved) },
                )
            }
        }
    }
    Spacer(Modifier.height(SHEET_END))
}

/** Whether this playlist holds [videoId]. */
internal fun LocalPlaylist.holds(videoId: String): Boolean = items.any { it.id == videoId }

/** Saves [item] to playlist [playlistId], or takes it out when it is [saved] there. */
internal fun toggleInPlaylist(library: VideoLibrary, playlistId: String, item: VideoItem, saved: Boolean) {
    if (saved) library.removeFromPlaylist(playlistId, item.id) else library.addToPlaylist(playlistId, item)
}

/** The list of playlists scrolls inside the sheet past this height (about six rows). */
private val LIST_MAX_HEIGHT = 384.dp

/** Room under the last row, as the video sheet keeps. */
private val SHEET_END = 8.dp

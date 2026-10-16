package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_empty_saved_playlists
import com.cosmotune.video.resources.video_saved_playlists
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.PlaylistRow
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import org.jetbrains.compose.resources.stringResource

/**
 * The remote playlists you saved (spec 5.8, 8.1), the most recent first: the heading, then a [PlaylistRow] each (the picture with the
 * count strip, the owner and the count), opening the playlist's page, where it can be played, downloaded or unsaved (Y-20). Empty: the
 * empty state.
 */
@Composable
fun SavedPlaylistsPage(contentPadding: PaddingValues, wide: Boolean) {
    val library = LocalVideoGraph.current.library
    val navigator = LocalVideoNavigator.current
    val saved by library.savedPlaylists.collectAsState()
    LibraryList(contentPadding, wide) { cell ->
        item(key = "heading") { LibraryHeader(stringResource(Res.string.video_saved_playlists), wide, cell) }
        if (saved.isEmpty()) {
            item(key = "empty") {
                CosmoEmptyState(message = stringResource(Res.string.video_empty_saved_playlists), modifier = cell, icon = VideoIcons.Bookmark)
            }
        }
        items(saved, key = { "playlist:" + it.id }) { playlist ->
            PlaylistRow(playlist, onClick = { navigator.push(VideoRoute.Playlist(playlist.id)) }, modifier = cell, desktop = wide)
        }
    }
}

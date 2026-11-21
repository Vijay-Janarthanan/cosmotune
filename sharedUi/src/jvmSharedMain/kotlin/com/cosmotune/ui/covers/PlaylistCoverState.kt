package com.cosmotune.ui.covers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.cosmotune.music.data.covers.PlaylistCoverRules
import com.cosmotune.music.data.covers.PlaylistCovers
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState

/** What the cover picker is opened on: one of the person's own playlists, its songs (empty until fetched) and its automatic cover. */
data class PlaylistCoverRequest(val browseId: String, val title: String, val songs: List<Song>, val automatic: String?)

/** The covers picked for the person's own playlists (playlist id to picture), followed as they change. */
@Composable
fun rememberPlaylistCovers(): Map<String, String> {
    val covers by PlaylistCovers.covers.collectAsState()
    return covers
}

/** The Library as [state] has it, with each playlist's card showing its picked cover. */
@Composable
fun rememberLibraryWithCovers(state: UiState<LibraryPage>): UiState<LibraryPage> {
    val covers = rememberPlaylistCovers()
    return remember(state, covers) {
        if (state !is UiState.Success || covers.isEmpty()) state
        else UiState.Success(state.data.copy(shelves = PlaylistCoverRules.shelvesWithCovers(state.data.shelves, covers)))
    }
}

/** One Library shelf (a "Show all" grid) with each playlist's card showing its picked cover. */
@Composable
fun rememberShelfWithCovers(shelf: HomeShelf): HomeShelf {
    val covers = rememberPlaylistCovers()
    return remember(shelf, covers) { PlaylistCoverRules.shelvesWithCovers(listOf(shelf), covers).single() }
}

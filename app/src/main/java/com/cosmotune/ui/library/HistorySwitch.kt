package com.cosmotune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.LocalCosmoShell
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.screens.HistoryScreen

/**
 * The one entry point of the History page: the new list while the new look is on (the shell exists), the old one while it is off.
 * It is in `app` because [HistoryScreen] is; [CosmoHistory] is in `sharedUi`.
 *
 * The parameter list is [HistoryScreen]'s, name for name and in the same order, and both branches pass every argument by name
 * (see `LibrarySwitch` for why).
 */
@Composable
fun HistorySwitch(
    state: UiState<List<Song>>,
    listState: LazyListState,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (LocalCosmoShell.current != null) {
        CosmoHistory(
            state = state, listState = listState, onSongClick = onSongClick, onSongLongPress = onSongLongPress,
            onSongSwipe = onSongSwipe, onRetry = onRetry, contentPadding = contentPadding, currentSong = currentSong,
            isPlaying = isPlaying, modifier = modifier,
        )
    } else {
        HistoryScreen(
            state = state, listState = listState, onSongClick = onSongClick, onSongLongPress = onSongLongPress,
            onSongSwipe = onSongSwipe, onRetry = onRetry, contentPadding = contentPadding, currentSong = currentSong,
            isPlaying = isPlaying, modifier = modifier,
        )
    }
}

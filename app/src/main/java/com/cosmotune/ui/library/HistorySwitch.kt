package com.cosmotune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState

/**
 * The one entry point of the History page: [CosmoHistory]. The parameter list is upstream's `HistoryScreen`'s, name for name and in
 * the same order (see `LibrarySwitch` for why).
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
    CosmoHistory(
        state = state, listState = listState, onSongClick = onSongClick, onSongLongPress = onSongLongPress,
        onSongSwipe = onSongSwipe, onRetry = onRetry, contentPadding = contentPadding, currentSong = currentSong,
        isPlaying = isPlaying, modifier = modifier,
    )
}

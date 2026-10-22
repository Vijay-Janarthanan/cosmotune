package com.cosmotune.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.blocks.CosmoSongRow
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.isSameTrackAs
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.songListSkeleton
import org.jetbrains.compose.resources.stringResource

/**
 * What the account has been listening to, most recent first, in the new look: a plain list of song rows with no dividers and no
 * heading (the top bar names the page, and no single cover stands for a history). The parameters are
 * `com.cosmotune.music.ui.screens.HistoryScreen`'s (it lives in `app`, which this module cannot see: `HistorySwitch` there chooses).
 *
 * Tapping a row plays it with the rest of the history behind it, so the list doubles as a queue that has already been approved
 * once. The rows are keyed on the video id and the index: the feed is deduplicated on the id, but a list keyed on something that
 * could repeat is one bad response away from a crash, and the index makes the key total.
 */
@Composable
fun CosmoHistory(
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
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        when (state) {
            is UiState.Loading -> songListSkeleton(count = 10, keyPrefix = "skeleton:history")

            is UiState.Error -> item(key = "history:message") {
                MessageState(message = state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }

            is UiState.Success -> {
                val songs = state.data
                items(count = songs.size, key = { "${songs[it].videoId}:$it" }) { index ->
                    val song = songs[index]
                    val current = song.isSameTrackAs(currentSong)
                    CosmoSongRow(
                        song = song,
                        onClick = { onSongClick(songs, index) },
                        onLongPress = { onSongLongPress(song) },
                        onSwipeToQueue = { onSongSwipe(song) },
                        isCurrent = current,
                        isPlaying = current && isPlaying,
                    )
                }
            }
        }
    }
}

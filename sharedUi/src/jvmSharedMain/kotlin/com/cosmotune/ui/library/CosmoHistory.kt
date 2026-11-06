package com.cosmotune.ui.library

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.songListSkeleton
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitSongRowFor
import com.cosmotune.ui.orbit.OrbitStatCard
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import org.jetbrains.compose.resources.stringResource

/*
 * History in Orbit (Canvas repo, design/cosmotune/batch-a/board.html, section 1): the heading "History." with a mono count, one quiet
 * stat card (beside the list on a wide window, under the heading on the phone), and the songs as Home's rows grouped under mono day
 * labels ("Today", "Yesterday", ...), the days YouTube Music files the history under. A history with no days (this computer's own,
 * when nobody is signed in) stands under one label, "Recently played".
 */

/**
 * What the account has been listening to, most recent first. The parameters are `com.cosmotune.music.ui.screens.HistoryScreen`'s
 * (it lives in `app`, which this module cannot see: `HistorySwitch` there chooses); the desktop calls it too.
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
    val fonts = orbitFonts()
    val look = orbitLook()
    val days by YtMusicRepository.historyDays.collectAsStateWithLifecycle()
    val songs = (state as? UiState.Success)?.data.orEmpty()
    val groups = remember(songs, days) { historyGroups(songs, days) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("music:history")) {
        val wide = maxWidth >= 840.dp
        val side = if (wide) 28.dp else 16.dp
        val column = sideColumn(maxWidth)
        val stat: @Composable (Modifier) -> Unit = { m -> HistoryStat(songs, groups, fonts, look, m) }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxSize(), contentPadding = contentPadding) {
                item(key = "history:heading") {
                    val artists = remember(songs) { songs.map { it.artist }.filter { it.isNotBlank() }.distinct().size }
                    OrbitPageHeading(
                        title = "History",
                        label = if (songs.isEmpty()) "Your listening" else "${songs.size} songs · $artists artists",
                        fonts = fonts, look = look,
                        modifier = Modifier.padding(start = side, end = side, top = 12.dp, bottom = 18.dp),
                    )
                }
                if (!wide && songs.isNotEmpty()) item(key = "history:stat") { stat(Modifier.padding(horizontal = side).padding(bottom = 10.dp)) }
                when (state) {
                    is UiState.Loading -> songListSkeleton(count = 10, keyPrefix = "skeleton:history")

                    is UiState.Error -> item(key = "history:message") {
                        MessageState(message = state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }

                    is UiState.Success -> {
                        if (songs.isEmpty()) item(key = "history:empty") {
                            OrbitStatCard("Nothing yet", "Nothing played yet", "Songs you play show up here, by the day you played them.", fonts, look, Modifier.padding(horizontal = side))
                        }
                        // The row that was played lights, not every namesake: see [playingRowMatcher].
                        val isCurrentRow = playingRowMatcher(songs, currentSong)
                        for (group in groups) {
                            item(key = "history:day:${group.first}:${group.label}") { DayLabel(group.label, fonts, look, side, group.first == 0) }
                            items(count = group.count, key = { "${songs[group.first + it].videoId}:${group.first + it}" }) { offset ->
                                val index = group.first + offset
                                val song = songs[index]
                                val current = isCurrentRow(song)
                                OrbitSongRowFor(
                                    song = song, fonts = fonts, look = look,
                                    onClick = { onSongClick(songs, index) },
                                    onLongClick = { onSongLongPress(song) },
                                    onSwipe = { onSongSwipe(song) },
                                    now = current,
                                    playing = current && isPlaying,
                                    modifier = Modifier.padding(horizontal = side, vertical = 4.dp).widthIn(max = 760.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (wide && songs.isNotEmpty()) {
                Column(Modifier.width(column).padding(top = contentPadding.calculateTopPadding() + 112.dp, end = side)) {
                    stat(Modifier)
                }
            }
        }
    }
}

/** The wide page's right column: a third of the page, never under 300 dp nor over 380 dp. */
private fun sideColumn(width: Dp): Dp = (width / 3).coerceIn(300.dp, 380.dp)

/** A run of the history under one day: where it starts in the list, how many rows it holds, and the day. */
internal data class HistoryGroup(val label: String, val first: Int, val count: Int)

/** [songs] cut into runs by the day [days] files each under; a song with no day joins the run above it (or "Recently played"). */
internal fun historyGroups(songs: List<Song>, days: Map<String, String>): List<HistoryGroup> {
    val out = ArrayList<HistoryGroup>()
    var label: String? = null
    var first = 0
    songs.forEachIndexed { index, song ->
        val day = days[song.videoId] ?: label ?: RECENT
        if (day != label) {
            label?.let { out += HistoryGroup(it, first, index - first) }
            label = day
            first = index
        }
    }
    label?.let { out += HistoryGroup(it, first, songs.size - first) }
    return out
}

private const val RECENT = "Recently played"

@Composable
private fun DayLabel(text: String, fonts: OrbitFonts, look: OrbitLook, side: Dp, top: Boolean) {
    Column {
        if (!top) Spacer(Modifier.height(10.dp))
        OrbitLabel(text, fonts, look.dim, Modifier.padding(start = side + 6.dp, end = side, top = 6.dp, bottom = 6.dp))
    }
}

/** The quiet card: how much of the history is from today, and the artist heard most in it. */
@Composable
private fun HistoryStat(songs: List<Song>, groups: List<HistoryGroup>, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val today = groups.firstOrNull { it.label.equals("Today", ignoreCase = true) }?.count ?: 0
    val top = remember(songs) {
        songs.map { it.artist }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
    }
    val dated = groups.any { it.label != RECENT }
    OrbitStatCard(
        label = if (dated) "Listening · from your account" else "Listening · on this device",
        title = if (dated) "$today today · ${songs.size} in all" else "${songs.size} songs",
        line = top?.let { (artist, plays) -> "Mostly $artist lately, $plays ${if (plays == 1) "song" else "songs"}" },
        fonts = fonts, look = look, modifier = modifier.fillMaxWidth(),
    )
}

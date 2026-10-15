package com.cosmotune.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cosmotune.design.ReleaseMeta
import com.cosmotune.ui.theme.LocalCosmoShell
import com.music.bitchord.R
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.ui.screens.DetailScreen

/**
 * The one entry point of the album, playlist and artist pages: the new pages while the new look is on (the shell exists), the old
 * screen while it is off.
 *
 * The parameter list is [DetailScreen]'s, name for name, with the same defaults and in the same order, and the old branch passes every
 * argument by name. That is the seam: when upstream adds a parameter to [DetailScreen] the old branch stops compiling (or the contract
 * test fails), and this file is where the new parameter has to be decided on instead of silently going missing from the new pages.
 * With the new look off nothing here runs but the old call.
 *
 * The new branch turns what the old screen did itself into what the pages are given: an artist's page gets Apple's picture (found by
 * name, as the old screen did) and its counts in the app's own words; a release page gets its songs in the order [songSort] says and
 * the track numbers the release itself has, which a sort must not relabel.
 */
@Composable
fun DetailSwitch(
    page: DetailPage,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    activeShelf: HomeShelf? = null,
    onActiveShelfChange: (HomeShelf?) -> Unit = {},
    onSectionItemLongPress: ((ShelfItem) -> Unit)? = null,
    onMore: ((List<Song>) -> Unit)? = null,
    onToggleLibrary: (() -> Unit)? = null,
    onToggleSubscription: (() -> Unit)? = null,
    releaseLibrary: Map<String, LibraryState> = emptyMap(),
    onLoadReleaseLibrary: ((String) -> Unit)? = null,
    onToggleReleaseLibrary: ((String) -> Unit)? = null,
    songSort: SongSort = SongSort.DEFAULT,
) {
    if (LocalCosmoShell.current != null) {
        if (page.type == BrowseType.ARTIST) {
            CosmoArtistPage(
                page = rememberArtistPage(page),
                heroUrl = rememberAppleHero(page),
                currentSong = currentSong,
                isPlaying = isPlaying,
                listState = listState,
                contentPadding = contentPadding,
                activeShelf = activeShelf,
                onActiveShelfChange = onActiveShelfChange,
                onSongClick = onSongClick,
                onSongLongPress = onSongLongPress,
                onSongSwipe = onSongSwipe,
                onShuffle = onShuffle,
                onSectionItemClick = onSectionItemClick,
                onSectionItemLongPress = onSectionItemLongPress,
                onToggleSubscription = onToggleSubscription,
                releaseLibrary = releaseLibrary,
                onLoadReleaseLibrary = onLoadReleaseLibrary,
                onToggleReleaseLibrary = onToggleReleaseLibrary,
                modifier = modifier,
            )
        } else {
            val raw = (page.songs as? UiState.Success)?.data.orEmpty()
            val songs = remember(raw, songSort) { raw.sortedForDetail(songSort) }
            // What a numbered row shows whatever [songSort] says: an album's track numbers are the sleeve's own and must not
            // relabel themselves to match wherever a sort put the row (`DetailScreen.kt`'s `originalTrackNumbers`).
            val trackNumbers = remember(raw) { raw.withIndex().associate { (i, s) -> s.videoId to i + 1 } }
            CosmoReleasePage(
                page = page,
                songs = songs,
                trackNumbers = trackNumbers,
                currentSong = currentSong,
                isPlaying = isPlaying,
                listState = listState,
                contentPadding = contentPadding,
                onSongClick = onSongClick,
                onSongLongPress = onSongLongPress,
                onSongSwipe = onSongSwipe,
                onShuffle = onShuffle,
                onMore = onMore,
                onArtistClick = onArtistClick,
                onAddSuggested = onAddSuggested,
                onToggleLibrary = onToggleLibrary,
                modifier = modifier,
            )
        }
    } else {
        DetailScreen(
            page = page,
            currentSong = currentSong,
            isPlaying = isPlaying,
            onSongClick = onSongClick,
            onSongLongPress = onSongLongPress,
            onSongSwipe = onSongSwipe,
            onShuffle = onShuffle,
            onSectionItemClick = onSectionItemClick,
            onArtistClick = onArtistClick,
            onAddSuggested = onAddSuggested,
            contentPadding = contentPadding,
            modifier = modifier,
            listState = listState,
            activeShelf = activeShelf,
            onActiveShelfChange = onActiveShelfChange,
            onSectionItemLongPress = onSectionItemLongPress,
            onMore = onMore,
            onToggleLibrary = onToggleLibrary,
            onToggleSubscription = onToggleSubscription,
            releaseLibrary = releaseLibrary,
            onLoadReleaseLibrary = onLoadReleaseLibrary,
            onToggleReleaseLibrary = onToggleReleaseLibrary,
            songSort = songSort,
        )
    }
}

/**
 * Apple Music's hero photo for this artist, found by name, or null until (and unless) it arrives; the page's own picture stands in
 * meanwhile. Copied from `DetailScreen.kt` (`appleArt` and its two effects): what is already known is read at once, and the lookup
 * waits for the name to settle. Opened from a track, the title is the whole credit ("A, B and C") until the page loads and swaps in
 * the one artist's name, and searching a credit finds nobody, so a title that reads as several artists waits for that swap.
 */
@Composable
private fun rememberAppleHero(page: DetailPage): String? {
    var appleArt by remember(page.browseId) { mutableStateOf(AppleArtistArtRepository.cached(page.title)) }
    val nameSettled = !ReleaseMeta.looksLikeCredit(page.title) || page.songs !is UiState.Loading
    LaunchedEffect(page.browseId, page.title, nameSettled) {
        if (nameSettled) appleArt = AppleArtistArtRepository.artFor(page.title) ?: appleArt
    }
    return appleArt?.heroUrl
}

/**
 * The artist's page with its two counts said in the app's own words, as the old About section did: YouTube's text already reads
 * "1.2M subscribers" in full, so only the number is kept and the label comes from the strings of the app (which exist in every
 * language). The page then shows the counts as it has them.
 */
@Composable
private fun rememberArtistPage(page: DetailPage): DetailPage {
    val subscribers = page.subscriberCountText?.let { stringResource(R.string.subscribers, it.substringBefore(' ')) }
    val listeners = page.monthlyListenerCount?.let { stringResource(R.string.monthly_listeners, it.substringBefore(' ')) }
    return remember(page, subscribers, listeners) { page.copy(subscriberCountText = subscribers, monthlyListenerCount = listeners) }
}

/**
 * How the track list is ordered. Copied from `DetailScreen.kt` (`sortedForDetail`, private there): the release's own running order,
 * or alphabetical, or by date added.
 */
private fun List<Song>.sortedForDetail(sort: SongSort): List<Song> = when (sort) {
    SongSort.DEFAULT -> this
    SongSort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    SongSort.TITLE_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
    // A catalogue row carries no added date, so its place in the running order stands in for one: a playlist is appended to as
    // songs are added, and reversed that puts the most recent addition on top. Rows that do carry a MediaStore timestamp (device
    // tracks, downloads) are dated properly, with the position order left to break the ties.
    SongSort.DATE_ADDED_ASC -> withIndex()
        .sortedWith(
            compareBy<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MAX_VALUE
            }.thenBy { (position, _) -> position },
        )
        .map { it.value }
    SongSort.DATE_ADDED_DESC -> withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MIN_VALUE
            }.thenByDescending { (position, _) -> position },
        )
        .map { it.value }
}

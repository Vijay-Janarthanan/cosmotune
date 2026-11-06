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
import com.cosmotune.music.R
import com.cosmotune.music.data.canvas.AppleArtistArtRepository
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.canvas.CanvasRepository
import com.cosmotune.music.data.settings.AppSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryState
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.MusicListOrder
import com.cosmotune.music.data.settings.SongSort
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.screens.DetailScreen

/**
 * The one entry point of the album, playlist and artist pages: the new pages while the new look is on (the shell exists), the old
 * screen while it is off.
 *
 * The parameter list is [DetailScreen]'s, name for name, with the same defaults and in the same order, plus the new pages' own at
 * the end ([onPlayPause], [onDownloadAll]), which the old screen never reads; and the old branch passes every argument of [DetailScreen] by name. That is the seam: when upstream adds a parameter to [DetailScreen] the old branch stops compiling (or the contract
 * test fails), and this file is where the new parameter has to be decided on instead of silently going missing from the new pages.
 * With the new look off nothing here runs but the old call.
 *
 * The new branch turns what the old screen did itself into what the pages are given: an artist's page gets Apple's picture (found by
 * name, as the old screen did) and its counts in the app's own words; a release page gets its songs in the order [songSort] says and
 * the track numbers the release itself has, which a sort must not relabel. The release page owns its view controls and receives
 * canonical songs, the global sort as an initial order, and completed downloads from the installed Android UI host.
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
    // A parameter the old screen does not have: the root's play/pause, for the bite of the artist and release cards while one of
    // their songs is loaded (the old screen never needed it). Not passed on to [DetailScreen].
    onPlayPause: (() -> Unit)? = null,
    // The Orbit release card's Download, which the old screen never had.
    onDownloadAll: ((List<Song>) -> Unit)? = null,
) {
    if (LocalCosmoShell.current != null) {
        if (page.type == BrowseType.ARTIST) {
            CosmoArtistPage(
                page = rememberArtistPage(page),
                heroUrl = null,
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
                onPlayPause = onPlayPause,
            )
        } else {
            val raw = (page.songs as? UiState.Success)?.data.orEmpty()
            val initialOrder = songSort.listOrder(raw.any { it.localDateAddedSeconds != null })
            val downloadedIds = AppUi.host.downloadedIds()
            val albumCanvas = rememberAlbumCanvas(page, raw)
            CosmoReleasePage(
                page = page,
                songs = raw,
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
                initialOrder = initialOrder,
                downloadedIds = downloadedIds,
                onDownloadAll = onDownloadAll,
                albumCanvas = albumCanvas,
                onPlayPause = onPlayPause,
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
 * An album's animated cover, the old screen's lookup (`DetailScreen.kt`): an album only (a playlist's artwork is a collage), asked
 * by the album's name and its credit while the animated-artwork switch is on, keeping a clip already found when a second pass (the
 * credit filling in once the songs load) comes back empty. The page plays it under the motion rules of [rememberMotionAllowed].
 */
@Composable
private fun rememberAlbumCanvas(page: DetailPage, songs: List<Song>): CanvasArtwork? {
    val enabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val spotifyFirst by AppSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    val credit = ReleaseMeta.parts(page.subtitle, page.type, emptyList()).credit.ifBlank { songs.firstOrNull()?.artist.orEmpty() }
    var canvas by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, page.title, credit, enabled, spotifyFirst) {
        if (!enabled || page.type != BrowseType.ALBUM) {
            canvas = null
            return@LaunchedEffect
        }
        canvas = CanvasRepository.canvasForAlbum(page.title, credit) ?: canvas
    }
    return canvas
}

/**
 * Interpret the global preference without presenting provider position as an unavailable added timestamp.
 */
private fun SongSort.listOrder(hasAddedDates: Boolean): MusicListOrder = when (this) {
    SongSort.DEFAULT -> MusicListOrder.DEFAULT
    SongSort.TITLE_ASC -> MusicListOrder.TITLE
    SongSort.TITLE_DESC -> MusicListOrder.TITLE_DESC
    SongSort.DATE_ADDED_ASC -> if (hasAddedDates) MusicListOrder.ADDED else MusicListOrder.DEFAULT
    SongSort.DATE_ADDED_DESC -> if (hasAddedDates) MusicListOrder.ADDED_DESC else MusicListOrder.REVERSED
}

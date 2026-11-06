package com.cosmotune.desktop

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ArtistPlay
import com.cosmotune.design.CollectionColumns
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.MusicListOrder
import com.cosmotune.music.data.model.MusicSongList
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.durationMillis
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.music.data.model.isUnresolvedSpotify
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.desktop.DesktopCanvasArtwork
import com.cosmotune.music.desktop.DesktopCanvasClient
import com.cosmotune.music.desktop.DesktopCollection
import com.cosmotune.music.desktop.DesktopStrings
import com.cosmotune.music.desktop.desktopSongContextMenu
import com.cosmotune.music.desktop.toShared
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_song_count
import com.cosmotune.music.sharedui.resources.cosmo_type_album
import com.cosmotune.music.sharedui.resources.cosmo_type_artist
import com.cosmotune.music.sharedui.resources.cosmo_type_ep
import com.cosmotune.music.sharedui.resources.cosmo_type_playlist
import com.cosmotune.music.sharedui.resources.cosmo_type_single
import com.cosmotune.music.sharedui.resources.music_list_no_results
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.blocks.MusicListControls
import com.cosmotune.ui.bridge.DropdownMenu
import com.cosmotune.ui.bridge.DropdownMenuItem
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.orbit.OrbitCardButton
import com.cosmotune.ui.orbit.OrbitPageButton
import com.cosmotune.ui.orbit.OrbitReleaseCard
import com.cosmotune.ui.orbit.OrbitTrackRow
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * An album or a playlist in Orbit (Canvas repo, design/cosmotune/pages-orbit, board section 1): the page's one colour card (the
 * cover as a big disc, what it is with its count and length, its name, who made it, the round buttons, Play in the bite), then
 * the list's controls and the songs as Home's rows.
 */

/** The desktop's page margin (`ShellMetrics.DESKTOP_PAGE_MARGIN_DP`) and the room above the card. */
private val PAGE_PADDING_X = 28.dp
private val PAGE_PADDING_TOP = 16.dp

/** Rows are 8 dp apart, as Home's; room under the last row for whatever floats over the page. */
private val ROW_GAP = 8.dp
private val PAGE_FOOT = 32.dp

/**
 * The collection page. It takes the old page's parameters, in the same order, and two more at the end: [nowPlaying] (the song that
 * is playing, whose row is peach with the orbit round its disc) and [isPlaying] (the orbit's moon turns; from [LocalCosmoPlaying],
 * which the root fills with the player's own flag). The old page reads the playing song from a local that only its own file can
 * see, so its delegate passes [nowPlaying] and leaves [isPlaying] at its default.
 *
 * What the old page did it still does: the list filter, sort, downloaded-only and selection, the next page of tracks when the last
 * row shows, the motion artwork when [animatedCanvas] (now inside the card's disc), Download on the card, the more menu with share,
 * rename, reorder and delete (the last three for a playlist of the account's own), a Spotify track with no match dimmed and inert,
 * and each row's like, download, add and remove on hover with its context menu.
 */
@Composable
internal fun CosmoDesktopCollectionPage(
    collection: DesktopCollection,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onRemoveFromPlaylist: ((Song) -> Unit)?,
    likedIds: Set<String>,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onDownloadAll: (List<Song>) -> Unit,
    animatedCanvas: Boolean,
    onToggleLike: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    downloadedIds: Set<String>,
    downloadInProgress: Set<String>,
    contentPadding: PaddingValues,
    onReorder: (() -> Unit)? = null,
    nowPlaying: Song? = null,
    isPlaying: Boolean = LocalCosmoPlaying.current,
) {
    val songs = collection.songs
    val onPlayPause = LocalCosmoPlayPause.current
    // "Search this list": a filter over the rows already loaded.
    var searching by rememberSaveable(collection.browseId) { mutableStateOf(false) }
    var listQuery by rememberSaveable(collection.browseId) { mutableStateOf("") }
    var listOrderName by rememberSaveable(collection.browseId) { mutableStateOf(MusicListOrder.DEFAULT.name) }
    val listOrder = MusicListOrder.entries.firstOrNull { it.name == listOrderName } ?: MusicListOrder.DEFAULT
    var downloadedOnly by rememberSaveable(collection.browseId) { mutableStateOf(false) }
    var selecting by rememberSaveable(collection.browseId) { mutableStateOf(false) }
    var selection by rememberSaveable(collection.browseId) { mutableStateOf(emptyList<String>()) }
    var selectionMenu by remember { mutableStateOf(false) }
    // The row that was played lights, not every namesake: see [playingRowMatcher].
    val isCurrentRow = remember(songs, nowPlaying) { playingRowMatcher(songs, nowPlaying) }
    val shownSongs = remember(songs, listQuery, listOrder, downloadedOnly, downloadedIds) {
        MusicSongList.rows(songs, listQuery, listOrder, downloadedOnly, downloadedIds)
    }
    val queue = remember(shownSongs) { shownSongs.map { it.value } }
    val selectedSongs = remember(songs, selection, listOrder) {
        val keys = selection.toSet()
        MusicSongList.rows(songs, order = listOrder).filter { MusicSongList.occurrence(it) in keys }.map { it.value }
    }
    val isAlbum = collection.type == BrowseType.ALBUM
    // A row names its album only where rows name one: an album's rows are all on it, and a playlist whose feed names none of them
    // would say nothing. Decided on every row loaded, not on the filtered ones, so the line does not change while typing.
    val showAlbum = remember(songs, isAlbum) { CollectionColumns.showsAlbum(isAlbum, songs.map { it.albumName }) }
    val bottom = contentPadding.calculateBottomPadding()
    val direction = LocalLayoutDirection.current
    Box(
        Modifier.fillMaxSize().padding(
            start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction),
            top = contentPadding.calculateTopPadding(),
        ),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = PAGE_PADDING_X, top = PAGE_PADDING_TOP, end = PAGE_PADDING_X, bottom = PAGE_FOOT + bottom),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            item(key = "collection-card") {
                CollectionCard(
                    collection = collection,
                    animatedCanvas = animatedCanvas,
                    playing = ArtistPlay.showsPause(songs, nowPlaying, isPlaying),
                    onPlay = {
                        // While one of these songs is the one loaded the bite is the player's play/pause; else it plays the visible list.
                        when (ArtistPlay.tap(songs, nowPlaying, canToggle = onPlayPause != null)) {
                            ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()
                            ArtistPlay.Tap.PLAY_LIST -> if (queue.isNotEmpty()) onPlaySongs(queue, 0)
                        }
                    },
                    onShuffle = { if (queue.isNotEmpty()) onShuffle(queue) },
                    onDownloadAll = { onDownloadAll(queue) },
                    onShare = onShare,
                    onRename = onRename,
                    onDelete = onDelete,
                    onReorder = onReorder,
                )
            }
            item(key = "collection-controls") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    OrbitPageButton(
                        if (searching) CosmoIcons.Close else CosmoIcons.Search,
                        if (searching) DesktopStrings["close_search", "Close search"] else DesktopStrings["search_this_list", "Search this list"],
                        onClick = {
                            searching = !searching
                            if (!searching) listQuery = ""
                        },
                        on = searching,
                    )
                    Box(Modifier.weight(1f)) {
                        MusicListControls(shownSongs.size, songs.size, collection.continuation != null, listOrder,
                            { listOrderName = it.name }, downloadedOnly, { downloadedOnly = it },
                            onReset = if (listQuery.isNotBlank() || downloadedOnly) { { listQuery = ""; downloadedOnly = false } } else null,
                            selectionCount = selectedSongs.size, selecting = selecting,
                            onSelecting = { selecting = !selecting; selection = emptyList() },
                            onPlaySelection = { if (selectedSongs.isNotEmpty()) onPlaySongs(selectedSongs, 0) },
                            onMoreSelection = { selectionMenu = true },
                            onSelectVisible = { selection = shownSongs.filterNot { it.value.isMatchPending || it.value.isMatchMissing }.map(MusicSongList::occurrence) },
                            onClearSelection = { selection = emptyList() }, hasAddedDates = songs.any { it.localDateAddedSeconds != null })
                        DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                            DropdownMenuItem(text = { Text(DesktopStrings["download", "Download"]) }, onClick = {
                                selectionMenu = false; onDownloadAll(selectedSongs)
                            })
                            DropdownMenuItem(text = { Text(DesktopStrings["like", "Like"]) }, onClick = {
                                selectionMenu = false; selectedSongs.filter { it.videoId !in likedIds }.forEach(onToggleLike)
                            })
                        }
                    }
                }
            }
            if (searching) {
                item(key = "collection-search") {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    CosmoSearchField(
                        query = listQuery,
                        onQueryChange = { listQuery = it },
                        onSubmit = {},
                        placeholder = DesktopStrings["search_this_list", "Search this list"],
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        focusRequester = focus,
                        height = 44.dp,
                    )
                }
            }
            if (shownSongs.isEmpty() && songs.isNotEmpty()) item(key = "collection-no-results") {
                Text(stringResource(SharedRes.string.music_list_no_results), Modifier.padding(12.dp))
            }
            itemsIndexed(shownSongs, key = { _, entry -> MusicSongList.occurrence(entry) }) { position, entry ->
                val song = entry.value
                CollectionTrackRow(
                    song = song,
                    cover = song.thumbnailUrl ?: collection.thumbnailUrl,
                    showAlbum = showAlbum,
                    isCurrent = isCurrentRow(song),
                    isPlaying = isPlaying,
                    liked = song.videoId in likedIds,
                    downloaded = song.videoId in downloadedIds,
                    downloadInProgress = song.videoId in downloadInProgress,
                    onClick = {
                        if (selecting) {
                            val key = MusicSongList.occurrence(entry)
                            selection = if (key in selection) selection - key else selection + key
                        } else onPlaySongs(queue, position)
                    },
                    selected = if (selecting) MusicSongList.occurrence(entry) in selection else null,
                    onToggleLike = onToggleLike,
                    onDownload = onDownload,
                    onAddToPlaylist = onAddToPlaylist,
                    onRemove = onRemoveFromPlaylist?.takeIf { song.setVideoId != null },
                    reserveRemove = onRemoveFromPlaylist != null,
                )
            }
            if (collection.continuation != null || collection.paginationError != null) {
                item(key = "collection-more") {
                    LaunchedEffect(collection.continuation, songs.size) {
                        if (collection.continuation != null && collection.paginationError == null) onLoadMore()
                    }
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        collection.paginationError?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
                        if (loadingMore) CosmoLoadingBlob(Modifier.size(24.dp))
                        else if (collection.continuation != null) CosmoButton(
                            text = if (collection.paginationError != null) DesktopStrings["retry", "Try again"] else DesktopStrings["load_more", "Load more"],
                            onClick = onLoadMore)
                    }
                }
            }
        }
    }
}

// ---- the card ----

/**
 * The page's colour card: the cover as a big disc (the album's motion artwork looping inside it when [animatedCanvas]), "ALBUM ·
 * 2024 · 12 SONGS · 48 MIN", the name, who made it, and Shuffle, the source's save, Download and More (share, rename, reorder,
 * delete) as round buttons; Play in the bite.
 */
@Composable
private fun CollectionCard(
    collection: DesktopCollection,
    animatedCanvas: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownloadAll: () -> Unit,
    onShare: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onReorder: (() -> Unit)?,
) {
    val songs = collection.songs
    val parts = remember(collection.subtitle) {
        collection.subtitle.split(" • ", " · ", " | ").map(String::trim).filter(String::isNotBlank)
    }
    val credit = remember(parts) { parts.firstOrNull(::isCredit).orEmpty() }
    val year = remember(parts) { parts.firstOrNull { it.matches(YEAR) } }
    val typeLabel = collectionTypeLabel(collection.type, parts)
    val countText = if (songs.isNotEmpty()) pluralStringResource(SharedRes.plurals.cosmo_song_count, songs.size, songs.size) else null
    val seconds = remember(songs) { songs.sumOf { it.durationText.durationMillis() / 1_000L } }
    val facts = listOfNotNull(typeLabel, year, countText, if (seconds > 0) formatDuration(seconds) else null).joinToString(" · ")
    // The services hang motion artwork off the release, so a page for one asks for it directly rather than picking a track and
    // hoping it sits on the right edition.
    val albumCanvas by produceState<DesktopCanvasArtwork?>(null, collection.title, animatedCanvas) {
        value = if (!animatedCanvas) {
            null
        } else {
            withContext(Dispatchers.IO) { DesktopCanvasClient.lookupAlbum(collection.title, songs.firstOrNull()?.artist.orEmpty()) }
        }
    }
    val motion = remember(albumCanvas) { albumCanvas?.toShared() }
    var moreOpen by remember { mutableStateOf(false) }
    OrbitReleaseCard(
        cover = collection.thumbnailUrl ?: songs.firstOrNull()?.thumbnailUrl,
        label = facts,
        title = collection.title,
        credit = credit,
        onCredit = null,
        playing = playing,
        onPlay = onPlay.takeIf { songs.isNotEmpty() },
        motion = motion,
        modifier = Modifier.fillMaxWidth(),
    ) { colors ->
        if (songs.isNotEmpty()) {
            OrbitCardButton(CosmoIcons.Shuffle, DesktopStrings["shuffle", "Shuffle"], colors, onShuffle)
        }
        com.cosmotune.music.desktop.DesktopSourceLibraryButton(collection)
        if (songs.isNotEmpty()) {
            OrbitCardButton(CosmoIcons.Download, DesktopStrings["download_all", "Download all"], colors, onDownloadAll)
        }
        if (onShare != null || onRename != null || onReorder != null || onDelete != null) {
            Box {
                OrbitCardButton(CosmoIcons.MoreVertical, DesktopStrings["more", "More"], colors, { moreOpen = true })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    onShare?.let { share ->
                        DropdownMenuItem(
                            text = { Text(DesktopStrings["share", "Share"]) },
                            leadingIcon = { Icon(CosmoIcons.Share, null) },
                            onClick = {
                                moreOpen = false
                                share()
                            },
                        )
                    }
                    onRename?.let { rename ->
                        DropdownMenuItem(
                            text = { Text(DesktopStrings["rename", "Rename"]) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = {
                                moreOpen = false
                                rename()
                            },
                        )
                    }
                    onReorder?.let { reorder ->
                        DropdownMenuItem(
                            text = { Text(DesktopStrings["reorder_playlist", "Edit order"]) },
                            leadingIcon = { Icon(Icons.Rounded.DragHandle, null) },
                            onClick = {
                                moreOpen = false
                                reorder()
                            },
                        )
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(
                            text = { Text(DesktopStrings["delete", "Delete"]) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = {
                                moreOpen = false
                                delete()
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---- the words of the card ----

private val YEAR = Regex("\\d{4}")
private val CLOCK = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")
private val TALLY = Regex("[0-9,.]+\\s*(?:songs?|tracks?)", RegexOption.IGNORE_CASE)
private val TYPE_WORDS = setOf("album", "single", "ep", "playlist", "artist")

/** Whether a piece of the subtitle names who made the thing: not the kind, the year, a length or a count. */
private fun isCredit(part: String): Boolean =
    part.lowercase() !in TYPE_WORDS && !part.matches(YEAR) && !part.matches(CLOCK) && !part.matches(TALLY)

/** What the thing is, from the kind the page was opened as and, for a release, from the words of its subtitle (single, EP). */
@Composable
private fun collectionTypeLabel(type: BrowseType, subtitleParts: List<String>): String = when (type) {
    BrowseType.ALBUM -> when {
        subtitleParts.any { it.equals("single", ignoreCase = true) } -> stringResource(SharedRes.string.cosmo_type_single)
        subtitleParts.any { it.equals("ep", ignoreCase = true) } -> stringResource(SharedRes.string.cosmo_type_ep)
        else -> stringResource(SharedRes.string.cosmo_type_album)
    }
    BrowseType.PLAYLIST -> stringResource(SharedRes.string.cosmo_type_playlist)
    BrowseType.ARTIST -> stringResource(SharedRes.string.cosmo_type_artist)
    BrowseType.OTHER -> DesktopStrings["d_collection", "Collection"]
}

/** "38 min", or "1 hr 4 min" from an hour up. */
private fun formatDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60L
    return if (minutes < 60L) "$minutes min" else "${minutes / 60L} hr ${minutes % 60L} min"
}

// ---- a track ----

/**
 * A song as Home's row ([OrbitTrackRow]): the round cover, the title over the artist (and the album, where rows name one:
 * [showAlbum]), and at its end the like, download, add and remove buttons (shown on hover, as before) and the duration. The playing
 * row is peach with the orbit round its disc. A Spotify track still being matched shows a spinner for its disc; one with no match is
 * dimmed and does nothing. While choosing ([selected] not null) a check stands in the disc's place.
 */
@Composable
private fun CollectionTrackRow(
    song: Song,
    cover: String?,
    showAlbum: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    liked: Boolean,
    downloaded: Boolean,
    downloadInProgress: Boolean,
    onClick: () -> Unit,
    onToggleLike: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onRemove: ((Song) -> Unit)?,
    reserveRemove: Boolean,
    selected: Boolean? = null,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    // A Spotify track still being matched to a service, or with no match: listed, but there is nothing yet to play, like, save or remove.
    val unresolved = song.isUnresolvedSpotify
    val chrome = rememberCosmoRowChrome(current = isCurrent)
    val album = song.albumName?.takeIf { showAlbum && it.isNotBlank() }
    OrbitTrackRow(
        song = song,
        cover = cover,
        subtitle = listOfNotNull(song.artist.takeIf(String::isNotBlank), album).joinToString(" · "),
        onClick = { if (!unresolved) onClick() },
        now = isCurrent,
        playing = isCurrent && isPlaying,
        selected = selected,
        showQuality = true,
        modifier = Modifier
            .fillMaxWidth()
            .desktopSongContextMenu(song, enabled = !unresolved)
            .then(if (!unresolved) Modifier.hoverable(chrome.interaction) else Modifier),
        trailing = {
            if (!unresolved) {
                CosmoDesktopRowActions(
                    song = song,
                    reveal = chrome.reveal,
                    liked = liked,
                    onToggleLike = onToggleLike,
                    onDownload = onDownload,
                    downloaded = downloaded,
                    downloadInProgress = downloadInProgress,
                    onAddToPlaylist = onAddToPlaylist,
                    onRemove = onRemove,
                    reserveRemove = reserveRemove,
                )
            } else {
                Spacer(Modifier.width(DESKTOP_ROW_ACTION * (if (reserveRemove) 4 else 3)))
            }
            Text(
                text = song.durationText ?: "—",
                modifier = Modifier.width(DESKTOP_DURATION_COLUMN),
                style = type.desktopRowDuration(),
                color = Color(palette.textSecondary),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        },
    )
}

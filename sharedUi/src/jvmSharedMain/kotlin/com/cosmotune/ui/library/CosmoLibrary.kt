package com.cosmotune.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.LibrarySegment
import com.cosmotune.design.LibrarySegments
import com.cosmotune.ui.blocks.CosmoCard
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.blocks.shelfKey
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitChipRow
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitQuickTile
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.albums
import com.cosmotune.music.sharedui.resources.artists
import com.cosmotune.music.sharedui.resources.cosmo_library_folders
import com.cosmotune.music.sharedui.resources.cosmo_sort_default
import com.cosmotune.music.sharedui.resources.cosmo_sort_title_asc
import com.cosmotune.music.sharedui.resources.cosmo_sort_title_desc
import com.cosmotune.music.sharedui.resources.cosmo_state_empty
import com.cosmotune.music.sharedui.resources.cosmo_view_grid
import com.cosmotune.music.sharedui.resources.cosmo_view_list
import com.cosmotune.music.sharedui.resources.downloaded
import com.cosmotune.music.sharedui.resources.import_spotify
import com.cosmotune.music.sharedui.resources.import_spotify_subtitle
import com.cosmotune.music.sharedui.resources.library
import com.cosmotune.music.sharedui.resources.library_sign_in_description
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.new_playlist
import com.cosmotune.music.sharedui.resources.on_device
import com.cosmotune.music.sharedui.resources.pinned
import com.cosmotune.music.sharedui.resources.playlists
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.sharedui.resources.saved_to_youtube_music
import com.cosmotune.music.sharedui.resources.sign_in
import com.cosmotune.music.sharedui.resources.spotify_logo
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.music.ui.components.libraryGrid
import com.cosmotune.music.ui.components.librarySkeleton
import com.cosmotune.music.ui.screens.LibraryLink
import com.cosmotune.music.ui.screens.localizeCardSubtitle
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * The Library page (DESIGN_SYSTEM.md 6.1): a light title with the new-playlist button, a row of filter chips, a line with the count,
 * the order and the list or grid switch, and under them the page of the chosen chip. LibrarySwitch calls it while the new look is on.
 */

/** The two pinned tiles and the gap between them never take more than this (two tiles of 366 dp and the margins). */
private val PIN_TILES_MAX_WIDTH = 776.dp

/** Between the cards of a grid (DESIGN_SYSTEM.md 3.3, 4.8): 12 dp across, 20 dp down. */
private val CARD_GAP_ACROSS = 12.dp
private val CARD_GAP_DOWN = 20.dp

/**
 * The signed-in library, under four chips: Playlists (the Replay cards, the liked and a pinned tile, the New playlist and Import
 * rows, the playlists and any other shelf), Albums, Artists (round covers) and Downloaded (the device's folders and the releases kept
 * on it). Playlists, Albums and Artists can be a list or a grid, each chip keeping its own choice, and follow the library's order. The parameters are [com.cosmotune.music.ui.screens.LibraryScreen]'s, name for name.
 *
 * [showTitle] is not read: the new top bars carry no page title at rest, so the heading stands on the phone and the desktop alike.
 * A playlist that has a tile (the liked one, the mix) is not listed again in the rows; holding any playlist, either tile
 * included, or its more button, is [onShelfItemLongPress] (the menu the old row opened).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CosmoLibrary(
    signedIn: Boolean,
    state: UiState<LibraryPage>,
    listState: LazyListState,
    onShelfItemClick: (ShelfItem) -> Unit,
    onShelfItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)? = null,
    /**
     * A shelf's "Show all" — every other shelf's row here stops at five cards (see [LibrarySegments.RAIL_MAX_ITEMS]), so this
     * is the only way to reach whatever didn't fit.
     */
    onShowAll: (HomeShelf) -> Unit,
    /**
     * The way in to Replay at the head of the page — the row of Replay cards, their placeholders while the history is read, or
     * nothing at all.
     */
    replay: @Composable () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /** The device's folders — downloads, local files, the remote libraries this build supports. Built by the app. */
    links: List<LibraryLink>,
    /** The releases kept on this device whole. Left off the page entirely while there are none. */
    deviceItems: List<ShelfItem>,
    /** The big "Library" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
    sourceShelves: List<HomeShelf> = com.cosmotune.ui.library.rememberSourceLibraryShelves(),
) {
    val pinned by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    var segmentIndex by rememberSaveable { mutableStateOf(0) }
    val segment = LibrarySegment.entries[segmentIndex.coerceIn(0, LibrarySegment.entries.lastIndex)]
    val librarySort by AppUi.host.librarySort.collectAsStateWithLifecycle()
    // A list of playlists and of artists, a grid of albums, until the person says otherwise on that chip.
    var gridSegments by rememberSaveable { mutableStateOf(listOf(LibrarySegment.ALBUMS.ordinal)) }
    val grid = segment.ordinal in gridSegments
    val labels = listOf(
        stringResource(Res.string.playlists),
        stringResource(Res.string.albums),
        stringResource(Res.string.artists),
        stringResource(Res.string.downloaded),
    )
    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = libraryGrid(maxWidth - CosmoSpacing.margin * 2).columns
            val cardWidth = (maxWidth - CosmoSpacing.margin * 2 - CARD_GAP_ACROSS * (columns - 1)) / columns
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "heading") { LibraryHeading(onNewPlaylist = onNewPlaylist) }
                item(key = "segments") {
                    KitChipRow(labels, segment.ordinal, { segmentIndex = it }, Modifier.padding(top = 12.dp).testTag("music:library:chips"))
                }
                val sourceKind = when (segment) {
                    LibrarySegment.PLAYLISTS -> "playlists"
                    LibrarySegment.ALBUMS -> "albums"
                    LibrarySegment.ARTISTS -> "artists"
                    LibrarySegment.DOWNLOADED -> null
                }
                sourceShelves.filter { it.sourceId == "source-library:$sourceKind" }.forEach { shelf ->
                    item(key = "source-header:${shelf.title}") { CosmoOverline(shelf.title) }
                    if (sourceKind == "artists" && !grid) artistRows(shelf, onShelfItemClick)
                    else cardRows("source:${shelf.title}", shelf.items, columns, cardWidth, onShelfItemClick, onShelfItemLongPress, circles = sourceKind == "artists")
                }
                when (segment) {
                    LibrarySegment.DOWNLOADED -> downloadedSegment(links, deviceItems, columns, cardWidth, onShelfItemClick, onShelfItemLongPress)
                    else -> if (!signedIn) {
                        signedOutSegment(segment, replay, onNewPlaylist, onImportSpotifyPlaylist, onSignIn)
                    } else when (state) {
                        is UiState.Loading -> librarySkeleton()
                        is UiState.Error -> item(key = "message") {
                            MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                        }
                        is UiState.Success -> {
                            val shelf = LibrarySegments.shelfFor(segment, state.data.shelves)
                            item(key = "view") {
                                LibraryViewLine(
                                    count = shelf?.items?.size ?: 0,
                                    label = labels[segment.ordinal],
                                    sort = librarySort,
                                    grid = grid,
                                    onToggleGrid = { gridSegments = if (grid) gridSegments - segment.ordinal else gridSegments + segment.ordinal },
                                )
                            }
                            val grids = LibraryGrids(grid, columns, cardWidth, librarySort)
                            when (segment) {
                                LibrarySegment.PLAYLISTS -> playlistsSegment(state.data.shelves, pinned, replay, onShelfItemClick, onShelfItemLongPress, onNewPlaylist, onImportSpotifyPlaylist, onShowAll, grids)
                                LibrarySegment.ALBUMS -> albumsSegment(shelf, onShelfItemClick, onShelfItemLongPress, grids)
                                else -> artistsSegment(shelf, onShelfItemClick, onShelfItemLongPress, grids)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The page's title and, at its end, the tonal 40 dp new-playlist button. */
@Composable
private fun LibraryHeading(onNewPlaylist: () -> Unit) {
    CosmoPageHeader(title = stringResource(Res.string.library)) {
        CosmoIconButton(CosmoIcons.Plus, stringResource(Res.string.new_playlist), onNewPlaylist, style = CosmoIconButtonStyle.TONAL, size = 40.dp)
    }
}

/** How a chip's page draws its items: as [grid] of [columns] cards [cardWidth] wide or as rows, in the library's [sort]. */
private class LibraryGrids(val grid: Boolean, val columns: Int, val cardWidth: Dp, val sort: LibrarySort)

/** "6 playlists", then the order as a dropdown chip and the list or grid switch (DESIGN_SYSTEM.md 6.1). */
@Composable
private fun LibraryViewLine(count: Int, label: String, sort: LibrarySort, grid: Boolean, onToggleGrid: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var sortOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin - 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (count > 0) "$count ${label.lowercase()}" else label,
            style = type.forText(type.meta, label),
            color = Color(palette.textSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            KitChip(sortLabel(sort), selected = false, onClick = { sortOpen = true }, modifier = Modifier.testTag("music:library:sort"), icon = CosmoIcons.Sort)
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(option)) },
                        onClick = { AppUi.host.setLibrarySort(option); sortOpen = false },
                        trailingIcon = if (option == sort) { { Icon(CosmoIcons.Check, contentDescription = null) } } else null,
                    )
                }
            }
        }
        KitIconButton(
            if (grid) CosmoIcons.List else CosmoIcons.Grid,
            stringResource(if (grid) Res.string.cosmo_view_list else Res.string.cosmo_view_grid),
            onToggleGrid,
            Modifier.testTag("music:library:view"),
        )
    }
}

@Composable
private fun sortLabel(sort: LibrarySort): String = stringResource(
    when (sort) {
        LibrarySort.DEFAULT -> Res.string.cosmo_sort_default
        LibrarySort.TITLE_ASC -> Res.string.cosmo_sort_title_asc
        LibrarySort.TITLE_DESC -> Res.string.cosmo_sort_title_desc
    },
)

// ---- Playlists ----------------------------------------------------------------------------------------------------------

/** Signed out: Playlists is Replay, the two rows that make a playlist, and the sign-in message; the other two say to sign in. */
private fun LazyListScope.signedOutSegment(
    segment: LibrarySegment,
    replay: @Composable () -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)?,
    onSignIn: () -> Unit,
) {
    if (segment == LibrarySegment.PLAYLISTS) {
        item(key = "replay") { replay() }
        newPlaylistRows(onNewPlaylist, onImportSpotifyPlaylist)
    }
    item(key = "sign-in") {
        MessageState(
            message = stringResource(Res.string.library_sign_in_description),
            actionLabel = stringResource(Res.string.sign_in),
            onAction = onSignIn,
        )
    }
}

/**
 * Replay, the two tiles, the New playlist and Import rows, the playlists (pinned first, none twice) and then every other shelf of
 * the feed (podcasts and the like) as a rail. A fresh account has no Playlists shelf at all, and that is exactly the account most
 * in need of the row that makes one, so the rows are drawn either way.
 */
private fun LazyListScope.playlistsSegment(
    shelves: List<HomeShelf>,
    pinned: List<String>,
    replay: @Composable () -> Unit,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)?,
    onShowAll: (HomeShelf) -> Unit,
    grids: LibraryGrids,
) {
    val layout = LibrarySegments.playlistLayout(LibrarySegments.shelfFor(LibrarySegment.PLAYLISTS, shelves)?.items.orEmpty(), pinned)
    item(key = "replay") { replay() }
    if (layout.liked != null || layout.mix != null) {
        item(key = "pins") { PinTiles(layout.liked, layout.mix, onClick, onLongPress) }
    }
    newPlaylistRows(onNewPlaylist, onImportSpotifyPlaylist)
    val rows = LibrarySegments.sorted(layout.rows, grids.sort)
    if (grids.grid) cardRows("playlist", rows, grids.columns, grids.cardWidth, onClick, onLongPress)
    else itemsIndexed(rows, key = { index, item -> "playlist:" + shelfKey(item, index) }) { _, item ->
        val pinnedItem = item.browseId != null && item.browseId in pinned
        CosmoListRow(
            title = item.title,
            subtitle = localizeCardSubtitle(item.subtitle).takeIf { it.isNotBlank() },
            leading = CosmoRowLeading.Cover(item.thumbnailUrl),
            onClick = { onClick(item) },
            onLongPress = { onLongPress(item) },
            trailing = {
                if (pinnedItem) PinMark()
                MoreButton { onLongPress(item) }
            },
        )
    }
    for (shelf in LibrarySegments.otherShelves(shelves)) {
        if (shelf.items.isEmpty()) continue
        item(key = "shelf:${shelf.title}") {
            CosmoShelf(
                title = shelf.title,
                items = shelf.items.take(LibrarySegments.RAIL_MAX_ITEMS),
                onItemClick = onClick,
                subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                onItemLongPress = onLongPress,
                onShowAll = { onShowAll(shelf) }.takeIf { shelf.items.size > LibrarySegments.RAIL_MAX_ITEMS },
                leadFirst = false,
            )
        }
    }
}

/** The shortcut tiles at the top of the page (DESIGN_SYSTEM.md 4.9), two columns 8 dp apart; a lone tile keeps the first. */
@Composable
private fun PinTiles(liked: ShelfItem?, mix: ShelfItem?, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit) {
    Row(
        // On a wide page the two tiles stop growing and stand on the left.
        Modifier.widthIn(max = PIN_TILES_MAX_WIDTH).fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (liked != null) KitQuickTile(liked.title, null, { onClick(liked) }, Modifier.weight(1f), icon = CosmoIcons.Heart, onLongClick = { onLongPress(liked) })
        if (mix != null) KitQuickTile(mix.title, mix.thumbnailUrl, { onClick(mix) }, Modifier.weight(1f), onLongClick = { onLongPress(mix) })
        if (liked == null || mix == null) Spacer(Modifier.weight(1f))
    }
}

/** The row that makes a playlist, and, when this build can import one, the row that imports from Spotify. */
private fun LazyListScope.newPlaylistRows(onNewPlaylist: () -> Unit, onImportSpotifyPlaylist: (() -> Unit)?) {
    item(key = "new-playlist") {
        CosmoListRow(
            title = stringResource(Res.string.new_playlist),
            subtitle = stringResource(Res.string.saved_to_youtube_music),
            leading = CosmoRowLeading.Icon(CosmoIcons.Plus),
            onClick = onNewPlaylist,
        )
    }
    if (onImportSpotifyPlaylist != null) {
        item(key = "import-spotify") {
            CosmoListRow(
                title = stringResource(Res.string.import_spotify),
                subtitle = stringResource(Res.string.import_spotify_subtitle),
                leading = CosmoRowLeading.IconPainter(painterResource(Res.drawable.spotify_logo)),
                onClick = onImportSpotifyPlaylist,
            )
        }
    }
}

/** The pin that marks a pinned playlist at the end of its row. */
@Composable
private fun RowScope.PinMark() {
    val palette = LocalCosmoColors.current.palette
    Icon(
        imageVector = CosmoIcons.Pin,
        contentDescription = stringResource(Res.string.pinned),
        tint = Color(palette.textTertiary),
        modifier = Modifier.padding(end = 2.dp).size(16.dp),
    )
}

/** The 40 dp more button at the end of a playlist's row: the same menu as a hold, whose 48 dp target is 4 dp inside the margin. */
@Composable
private fun RowScope.MoreButton(onClick: () -> Unit) {
    CosmoIconButton(
        icon = CosmoIcons.MoreVertical,
        contentDescription = stringResource(Res.string.more),
        onClick = onClick,
        modifier = Modifier.offset(x = 4.dp),
        style = CosmoIconButtonStyle.PLAIN,
        size = 40.dp,
    )
}

// ---- Albums and Artists -------------------------------------------------------------------------------------------------

/** Albums: a grid of the Albums shelf's cards, or rows. */
private fun LazyListScope.albumsSegment(
    albums: HomeShelf?,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
    grids: LibraryGrids,
) {
    val items = LibrarySegments.sorted(albums?.items.orEmpty(), grids.sort)
    if (items.isEmpty()) {
        item(key = "albums:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    if (grids.grid) {
        cardRows("albums", items, grids.columns, grids.cardWidth, onClick, onLongPress)
    } else {
        itemsIndexed(items, key = { index, item -> "album:" + shelfKey(item, index) }) { _, item ->
            CosmoListRow(
                title = item.title,
                subtitle = localizeCardSubtitle(item.subtitle).takeIf { it.isNotBlank() },
                leading = CosmoRowLeading.Cover(item.thumbnailUrl),
                onClick = { onClick(item) },
                onLongPress = { onLongPress(item) },
            )
        }
    }
}

/** Artists: from the Artists and Subscriptions shelves, a round cover each, as rows or as a grid. */
private fun LazyListScope.artistsSegment(artists: HomeShelf?, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit, grids: LibraryGrids) {
    val items = LibrarySegments.sorted(artists?.items.orEmpty(), grids.sort)
    if (grids.grid && items.isNotEmpty()) cardRows("artist", items, grids.columns, grids.cardWidth, onClick, onLongPress, circles = true)
    else artistRows(HomeShelf(title = artists?.title.orEmpty(), items = items), onClick)
}

/** Artists as rows, a round cover each. */
private fun LazyListScope.artistRows(artists: HomeShelf?, onClick: (ShelfItem) -> Unit) {
    val items = artists?.items.orEmpty()
    if (items.isEmpty()) {
        item(key = "artists:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    itemsIndexed(items, key = { index, item -> "artist:" + shelfKey(item, index) }) { _, item ->
        CosmoListRow(
            title = item.title,
            subtitle = localizeCardSubtitle(item.subtitle).takeIf { it.isNotBlank() },
            leading = CosmoRowLeading.Cover(item.thumbnailUrl, circle = true),
            onClick = { onClick(item) },
        )
    }
}

// ---- Downloaded ---------------------------------------------------------------------------------------------------------

/**
 * Downloaded: "Folders and servers" (Downloads, Local Music, and the remote libraries this build has) and, under "On device", the
 * releases kept on this device as a grid of cards. It does not wait for the account's library: it is the device's own.
 */
private fun LazyListScope.downloadedSegment(
    links: List<LibraryLink>,
    deviceItems: List<ShelfItem>,
    columns: Int,
    cardWidth: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
) {
    if (links.isEmpty() && deviceItems.isEmpty()) {
        item(key = "downloaded:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    if (links.isNotEmpty()) {
        item(key = "folders:header") { CosmoOverline(stringResource(Res.string.cosmo_library_folders)) }
        itemsIndexed(links, key = { index, link -> "folder:${link.item.browseId ?: link.item.title}#$index" }) { _, link ->
            CosmoListRow(
                title = link.item.title,
                subtitle = link.item.subtitle.takeIf { it.isNotBlank() },
                leading = if (link.logo != null) CosmoRowLeading.IconPainter(painterResource(link.logo)) else CosmoRowLeading.Icon(link.icon),
                onClick = { onClick(link.item) },
                trailing = { Chevron() },
            )
        }
    }
    if (deviceItems.isNotEmpty()) {
        item(key = "device:header") { CosmoOverline(stringResource(Res.string.on_device)) }
        cardRows("device", deviceItems, columns, cardWidth, onClick, onLongPress)
    }
}

/** The 20 dp chevron at the end of a folder's row, in `textTertiary`. */
@Composable
private fun RowScope.Chevron() {
    val palette = LocalCosmoColors.current.palette
    Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(20.dp))
}

// ---- A grid of cards inside the list -------------------------------------------------------------------------------------

/**
 * [items] as cards in rows of [columns], each row one lazy item (a grid cannot nest in the list): [cardWidth] wide, 12 dp across,
 * 20 dp down, with 8 dp above the first row and below the last. A short last row keeps its cards on the left. [circles] draws artists.
 */
private fun LazyListScope.cardRows(
    prefix: String,
    items: List<ShelfItem>,
    columns: Int,
    cardWidth: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
    circles: Boolean = false,
) {
    val rows = items.chunked(columns.coerceAtLeast(1))
    itemsIndexed(rows, key = { index, _ -> "$prefix:row:$index" }) { index, row ->
        Row(
            Modifier.fillMaxWidth().padding(
                start = CosmoSpacing.margin,
                end = CosmoSpacing.margin,
                top = if (index == 0) 8.dp else CARD_GAP_DOWN,
                bottom = if (index == rows.lastIndex) 8.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP_ACROSS),
        ) {
            for (item in row) {
                CosmoCard(
                    title = item.title,
                    imageUrl = item.thumbnailUrl,
                    onClick = { onClick(item) },
                    subtitle = localizeCardSubtitle(item.subtitle).takeIf { it.isNotBlank() },
                    width = cardWidth,
                    circle = circles,
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

package com.cosmotune.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.LibrarySegment
import com.cosmotune.design.LibrarySegments
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.blocks.CosmoCard
import com.cosmotune.ui.blocks.CosmoLikedTile
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoMixTile
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.blocks.shelfKey
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSegmented
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.albums
import com.music.bitchord.sharedui.resources.artists
import com.music.bitchord.sharedui.resources.cosmo_library_folders
import com.music.bitchord.sharedui.resources.cosmo_state_empty
import com.music.bitchord.sharedui.resources.downloaded
import com.music.bitchord.sharedui.resources.import_spotify
import com.music.bitchord.sharedui.resources.import_spotify_subtitle
import com.music.bitchord.sharedui.resources.library
import com.music.bitchord.sharedui.resources.library_sign_in_description
import com.music.bitchord.sharedui.resources.more
import com.music.bitchord.sharedui.resources.new_playlist
import com.music.bitchord.sharedui.resources.on_device
import com.music.bitchord.sharedui.resources.pinned
import com.music.bitchord.sharedui.resources.playlists
import com.music.bitchord.sharedui.resources.retry
import com.music.bitchord.sharedui.resources.saved_to_youtube_music
import com.music.bitchord.sharedui.resources.sign_in
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.libraryGrid
import com.music.bitchord.ui.components.librarySkeleton
import com.music.bitchord.ui.screens.LibraryLink
import com.music.bitchord.ui.screens.localizeCardSubtitle
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * The Library page in the new look (`V2.library`, `v2/v2.js:78-86`, `screens-v2.css:85-101`; the rows and the album grid are
 * `libPlaylists` and `libAlbums` of `screens-more-a.js`): a heading with the new-playlist button, four segments, and under them
 * the page of the chosen segment. LibrarySwitch calls it while the new look is on.
 */

/** The window width from which the page heading is the large display step (the desktop's), in dp. */
private const val WIDE_PAGE_DP = 840f

/** The two pinned tiles and the gap between them never take more than this (two tiles of 366 dp and the margins). */
private val PIN_TILES_MAX_WIDTH = 776.dp

/** Between the cards of a grid: 14 dp across, 18 dp down (`libAlbums`). */
private val CARD_GAP_ACROSS = 14.dp
private val CARD_GAP_DOWN = 18.dp

/**
 * The signed-in library, as four segments: Playlists (the Replay cards, the liked and a pinned tile, the New playlist and Import
 * rows, the playlists and any other shelf), Albums (a grid), Artists (rows with round covers) and Downloaded (the device's
 * folders and the releases kept on it). The parameters are [com.music.bitchord.ui.screens.LibraryScreen]'s, name for name.
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
) {
    val pinned by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    var segmentIndex by rememberSaveable { mutableStateOf(0) }
    val segment = LibrarySegment.entries[segmentIndex.coerceIn(0, LibrarySegment.entries.lastIndex)]
    val labels = listOf(
        stringResource(Res.string.playlists),
        stringResource(Res.string.albums),
        stringResource(Res.string.artists),
        stringResource(Res.string.downloaded),
    )
    val segmentScroll = rememberScrollState()
    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = libraryGrid(maxWidth - CosmoSpacing.margin * 2).columns
            val cardWidth = (maxWidth - CosmoSpacing.margin * 2 - CARD_GAP_ACROSS * (columns - 1)) / columns
            val wide = maxWidth.value >= WIDE_PAGE_DP
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "heading") { LibraryHeading(wide = wide, onNewPlaylist = onNewPlaylist) }
                item(key = "segments") {
                    // Four labels with the check are wider than a narrow phone: the control scrolls sideways there.
                    CosmoSegmented(
                        options = labels,
                        selectedIndex = segment.ordinal,
                        onSelect = { segmentIndex = it },
                        modifier = Modifier.horizontalScroll(segmentScroll).padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 16.dp),
                    )
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
                        is UiState.Success -> when (segment) {
                            LibrarySegment.PLAYLISTS -> playlistsSegment(state.data.shelves, pinned, replay, onShelfItemClick, onShelfItemLongPress, onNewPlaylist, onImportSpotifyPlaylist, onShowAll)
                            LibrarySegment.ALBUMS -> albumsSegment(LibrarySegments.shelfFor(segment, state.data.shelves), columns, cardWidth, onShelfItemClick, onShelfItemLongPress)
                            else -> artistsSegment(LibrarySegments.shelfFor(segment, state.data.shelves), onShelfItemClick)
                        }
                    }
                }
            }
        }
    }
}

/** `.pg-head2`: the title in `display(DISPLAY_M)` (`DISPLAY_L` from 840 dp), and the tonal 40 dp new-playlist button at the end. */
@Composable
private fun LibraryHeading(wide: Boolean, onNewPlaylist: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = stringResource(Res.string.library)
    Row(
        Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin - 4.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = type.forText(type.display(if (wide) TypeScale.DISPLAY_L else TypeScale.DISPLAY_M), title),
            color = Color(palette.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        CosmoIconButton(CosmoIcons.Plus, stringResource(Res.string.new_playlist), onNewPlaylist, style = CosmoIconButtonStyle.TONAL, size = 40.dp)
    }
}

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
) {
    val layout = LibrarySegments.playlistLayout(LibrarySegments.shelfFor(LibrarySegment.PLAYLISTS, shelves)?.items.orEmpty(), pinned)
    item(key = "replay") { replay() }
    if (layout.liked != null || layout.mix != null) {
        item(key = "pins") { PinTiles(layout.liked, layout.mix, onClick, onLongPress) }
    }
    newPlaylistRows(onNewPlaylist, onImportSpotifyPlaylist)
    itemsIndexed(layout.rows, key = { index, item -> "playlist:" + shelfKey(item, index) }) { _, item ->
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

/** `.pins2`: two columns 12 dp apart (padding 4 16 10); a tile that is not there leaves its column empty. */
@Composable
private fun PinTiles(liked: ShelfItem?, mix: ShelfItem?, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit) {
    Row(
        // On a wide page the two tiles stop growing (a tile 172 dp tall and 600 dp wide is not the board's tile) and stand on the left.
        Modifier.widthIn(max = PIN_TILES_MAX_WIDTH).fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(CosmoSpacing.gutter),
    ) {
        if (liked != null) {
            CosmoLikedTile(
                title = liked.title,
                subtitle = localizeCardSubtitle(liked.subtitle).takeIf { it.isNotBlank() },
                onClick = { onClick(liked) },
                modifier = Modifier.weight(1f),
                onLongPress = { onLongPress(liked) },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (mix != null) {
            CosmoMixTile(
                title = mix.title,
                subtitle = localizeCardSubtitle(mix.subtitle).takeIf { it.isNotBlank() },
                covers = listOf(mix.thumbnailUrl),
                onClick = { onClick(mix) },
                modifier = Modifier.weight(1f),
                onLongPress = { onLongPress(mix) },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
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

/** Albums: the cards of the Albums shelf in rows of [columns] (14 dp across, 18 dp down), as `libAlbums`. */
private fun LazyListScope.albumsSegment(
    albums: HomeShelf?,
    columns: Int,
    cardWidth: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
) {
    val items = albums?.items.orEmpty()
    if (items.isEmpty()) {
        item(key = "albums:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    cardRows("albums", items, columns, cardWidth, onClick, onLongPress)
}

/** Artists: a row each, with a round cover, from the Artists and Subscriptions shelves. */
private fun LazyListScope.artistsSegment(artists: HomeShelf?, onClick: (ShelfItem) -> Unit) {
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
 * [items] as cards in rows of [columns], each row one lazy item (a grid cannot nest in the list): [cardWidth] wide, 14 dp across,
 * 18 dp down, with 8 dp above the first row and below the last (`padding 8 16`). A short last row keeps its cards on the left.
 */
private fun LazyListScope.cardRows(
    prefix: String,
    items: List<ShelfItem>,
    columns: Int,
    cardWidth: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
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
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

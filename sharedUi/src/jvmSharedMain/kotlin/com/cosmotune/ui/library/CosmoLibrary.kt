package com.cosmotune.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.LibrarySegment
import com.cosmotune.design.LibrarySegments
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_library_folders
import com.cosmotune.music.sharedui.resources.cosmo_sort_default
import com.cosmotune.music.sharedui.resources.cosmo_sort_title_asc
import com.cosmotune.music.sharedui.resources.cosmo_sort_title_desc
import com.cosmotune.music.sharedui.resources.cosmo_state_empty
import com.cosmotune.music.sharedui.resources.cosmo_view_grid
import com.cosmotune.music.sharedui.resources.cosmo_view_list
import com.cosmotune.music.sharedui.resources.import_spotify
import com.cosmotune.music.sharedui.resources.library_sign_in_description
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.new_playlist
import com.cosmotune.music.sharedui.resources.on_device
import com.cosmotune.music.sharedui.resources.pinned
import com.cosmotune.music.sharedui.resources.retry
import com.cosmotune.music.sharedui.resources.sign_in
import com.cosmotune.music.sharedui.resources.spotify_logo
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.librarySkeleton
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.music.ui.components.rememberShelfFlingBehavior
import com.cosmotune.music.ui.screens.LibraryLink
import com.cosmotune.music.ui.screens.localizeCardSubtitle
import com.cosmotune.ui.blocks.shelfKey
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitPortrait
import com.cosmotune.ui.orbit.OrbitRecord
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitSongRowFor
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitRecordGrid
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * The Library page in Orbit (Canvas repo, design/cosmotune/pages-orbit/board.html, section 4): the heading "Library." with a mono
 * count, Liked songs as the colour card with Play in its bite next to a quiet Downloaded card, chips that choose what is shown
 * (Playlists, Albums, Artists, Songs, Downloaded) and how it is sorted, and under them the chosen chip's page: playlists and
 * albums as records with the play moon on their edge, artists as portraits, songs as Home's rows. LibrarySwitch calls it.
 */

/** The chips of the page, in the board's order. Playlists, Albums, Artists and Downloaded are the feed's [LibrarySegment]s. */
internal enum class LibraryChip(val label: String, val segment: LibrarySegment?) {
    PLAYLISTS("Playlists", LibrarySegment.PLAYLISTS),
    ALBUMS("Albums", LibrarySegment.ALBUMS),
    ARTISTS("Artists", LibrarySegment.ARTISTS),
    SONGS("Songs", null),
    DOWNLOADED("Downloaded", LibrarySegment.DOWNLOADED),
}

/**
 * The signed-in library. The parameters are [com.cosmotune.music.ui.screens.LibraryScreen]'s, name for name, and then four the
 * Orbit page adds for the Songs chip and the Liked card's Play: the playing song, whether it plays, a tap on a song (the list
 * and the index), and a hold on one. Without [onSongClick] a song row opens as a shelf item would, and Play in the Liked card
 * opens the playlist.
 *
 * [showTitle] is not read: the heading stands on the phone and the desktop alike. Holding any playlist, album or artist, or a
 * playlist row's more button, is [onShelfItemLongPress] (the menu the old row opened).
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
     * A shelf's "Show all" — every other shelf's row here stops at five records (see [LibrarySegments.RAIL_MAX_ITEMS]), so this
     * is the only way to reach whatever didn't fit.
     */
    onShowAll: (HomeShelf) -> Unit,
    /**
     * The way in to Replay at the head of the Playlists chip — the row of Replay cards, their placeholders while the history is
     * read, or nothing at all.
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
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    onSongClick: ((List<Song>, Int) -> Unit)? = null,
    onSongLongPress: ((Song) -> Unit)? = null,
) {
    val pinned by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    var segmentIndex by rememberSaveable { mutableStateOf(0) }
    val chip = LibraryChip.entries[segmentIndex.coerceIn(0, LibraryChip.entries.lastIndex)]
    val librarySort by AppUi.host.librarySort.collectAsStateWithLifecycle()
    // Records and portraits, until the person turns a chip to a list; each chip keeps its own choice.
    var listSegments by rememberSaveable { mutableStateOf(listOf<Int>()) }
    val grid = chip.ordinal !in listSegments
    val fonts = orbitFonts()
    val look = orbitLook()
    val downloadedCount = AppUi.host.downloadedIds().size
    val library = (state as? UiState.Success)?.data
    val playlists = remember(library) { library?.let { LibrarySegments.shelfFor(LibrarySegment.PLAYLISTS, it.shelves) }?.items.orEmpty() }
    val liked = remember(playlists, pinned) { LibrarySegments.playlistLayout(playlists, pinned).liked }
    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val side = if (wide) 28.dp else 16.dp
            val (columns, cell) = orbitRecordGrid(maxWidth, side)
            val page = LibraryLook(fonts, look, side, wide, columns, cell, librarySort, grid)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "heading") {
                    val count = library?.shelves?.let { shelves ->
                        listOf(LibrarySegment.PLAYLISTS, LibrarySegment.ALBUMS, LibrarySegment.ARTISTS).sumOf { LibrarySegments.shelfFor(it, shelves)?.items?.size ?: 0 }
                    } ?: 0
                    LibraryHeading(count, onNewPlaylist, page)
                }
                item(key = "lead") {
                    LeadCards(
                        liked = liked.takeIf { signedIn },
                        likedSongs = if (signedIn) library?.likedSongs.orEmpty() else emptyList(),
                        likedComplete = library?.likedContinuation == null,
                        downloadedCount = downloadedCount,
                        page = page,
                        onOpen = onShelfItemClick,
                        onHold = onShelfItemLongPress,
                        onPlay = onSongClick,
                        onDownloaded = { segmentIndex = LibraryChip.DOWNLOADED.ordinal },
                    )
                }
                item(key = "segments") {
                    LibraryChips(
                        chip = chip,
                        onChip = { segmentIndex = it.ordinal },
                        sort = librarySort,
                        grid = grid,
                        onToggleGrid = { listSegments = if (grid) listSegments + chip.ordinal else listSegments - chip.ordinal },
                        page = page,
                    )
                }
                val sourceKind = when (chip) {
                    LibraryChip.PLAYLISTS -> "playlists"
                    LibraryChip.ALBUMS -> "albums"
                    LibraryChip.ARTISTS -> "artists"
                    else -> null
                }
                sourceShelves.filter { sourceKind != null && it.sourceId == "source-library:$sourceKind" }.forEach { shelf ->
                    sectionHeader("source-header:${shelf.title}", shelf.title, page)
                    item(key = "source:${shelf.title}") { RecordRail(shelf.items, onShelfItemClick, onShelfItemLongPress, page, portraits = sourceKind == "artists") }
                }
                when (chip) {
                    LibraryChip.DOWNLOADED -> downloadedSegment(links, deviceItems, onShelfItemClick, onShelfItemLongPress, page)
                    else -> if (!signedIn) {
                        signedOutSegment(chip, replay, onNewPlaylist, onImportSpotifyPlaylist, onSignIn, page)
                    } else when (state) {
                        is UiState.Loading -> librarySkeleton()
                        is UiState.Error -> item(key = "message") {
                            MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                        }
                        is UiState.Success -> when (chip) {
                            LibraryChip.PLAYLISTS -> playlistsSegment(state.data.shelves, pinned, replay, onShelfItemClick, onShelfItemLongPress, onNewPlaylist, onImportSpotifyPlaylist, onShowAll, page)
                            LibraryChip.ALBUMS -> albumsSegment(LibrarySegments.shelfFor(LibrarySegment.ALBUMS, state.data.shelves), onShelfItemClick, onShelfItemLongPress, page)
                            LibraryChip.ARTISTS -> artistsSegment(LibrarySegments.shelfFor(LibrarySegment.ARTISTS, state.data.shelves), onShelfItemClick, onShelfItemLongPress, page)
                            else -> songsSegment(state.data, currentSong, isPlaying, onSongClick, onSongLongPress, onShelfItemClick, page)
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

/** How the page is drawn: the Orbit fonts and colours, the gutter, the records' grid, the order, and records or rows. */
private class LibraryLook(
    val fonts: OrbitFonts,
    val look: OrbitLook,
    val side: Dp,
    val wide: Boolean,
    val columns: Int,
    val cell: Dp,
    val sort: LibrarySort,
    val grid: Boolean,
)

// ---- the head of the page -----------------------------------------------------------------------------------------------

/** "Library." with the mono count above it and, at its end, the round new-playlist button. */
@Composable
private fun LibraryHeading(count: Int, onNewPlaylist: () -> Unit, page: LibraryLook) {
    OrbitPageHeading(
        title = "Library",
        label = if (count > 0) "Your library · $count items" else "Your library",
        fonts = page.fonts,
        look = page.look,
        modifier = Modifier.padding(start = page.side + 6.dp, end = page.side, top = 18.dp),
    ) {
        OrbitRoundButton(CosmoIcons.Plus, stringResource(Res.string.new_playlist), page.look.ink, onClick = onNewPlaylist, size = 44.dp)
    }
}

/**
 * Liked songs as the colour card in its cover's colour (the count, "Everything you loved", the first covers as small discs, and
 * Play in the bite), and beside it (under it on the phone) the quiet Downloaded card, which opens the Downloaded chip. Play
 * plays the liked songs from the top through [onPlay]; without it, or before they are read, it opens the playlist.
 */
@Composable
private fun LeadCards(
    liked: ShelfItem?,
    likedSongs: List<Song>,
    likedComplete: Boolean,
    downloadedCount: Int,
    page: LibraryLook,
    onOpen: (ShelfItem) -> Unit,
    onHold: (ShelfItem) -> Unit,
    onPlay: ((List<Song>, Int) -> Unit)?,
    onDownloaded: () -> Unit,
) {
    val showLiked = liked != null || likedSongs.isNotEmpty()
    val likedCard: @Composable (Modifier) -> Unit = { m ->
        val (fill, ink) = coverFill(liked?.thumbnailUrl ?: likedSongs.firstOrNull()?.thumbnailUrl, "liked")
        val play: () -> Unit = {
            if (onPlay != null && likedSongs.isNotEmpty()) onPlay(likedSongs, 0) else liked?.let(onOpen)
        }
        val open: () -> Unit = { if (liked != null) onOpen(liked) else play() }
        val hold: (() -> Unit)? = liked?.let { { onHold(it) } }
        OrbitBiteCard(fill = fill, modifier = m, bite = { OrbitPlayButton(playing = false, onClick = play, look = page.look) }) {
            Box(Modifier.fillMaxSize().longPressMenuClickable(onClick = open, onLongClick = hold).contextClick(hold))
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp)) {
                val count = if (likedComplete && likedSongs.isNotEmpty()) " · ${likedSongs.size}" else ""
                OrbitLabel("Liked songs$count", page.fonts, ink.copy(alpha = 0.75f))
                Text("Everything you loved", style = page.fonts.heroTitle.copy(fontSize = 28.sp, lineHeight = 30.sp), color = ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, end = 70.dp))
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 21.dp, bottom = 21.dp), horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
                likedSongs.take(3).forEach { song ->
                    Box(Modifier.size(46.dp).clip(CircleShape).background(fill).padding(3.dp)) { Disc(song.thumbnailUrl, 40.dp) }
                }
            }
        }
    }
    val songs = when (downloadedCount) { 0 -> "Nothing yet"; 1 -> "1 song"; else -> "$downloadedCount songs" }
    val downloadedCard: @Composable (Modifier) -> Unit = { m ->
        Column(m.clip(RoundedCornerShape(30.dp)).background(page.look.chip).clickable(onClick = onDownloaded).padding(22.dp)) {
            OrbitLabel("Downloaded · on this device", page.fonts, page.look.dim)
            Text(songs, style = page.fonts.heroTitle.copy(fontSize = 24.sp), color = page.look.ink, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
            Text("Plays without the internet", style = page.fonts.body, color = page.look.dim, modifier = Modifier.padding(top = 4.dp))
        }
    }
    val outer = Modifier.padding(horizontal = page.side).padding(top = 22.dp)
    if (!showLiked) {
        // Alone (signed out, no Liked songs card): one quiet line-high card, not a big empty one.
        Row(
            outer.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(page.look.chip).clickable(onClick = onDownloaded)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                OrbitLabel("Downloaded · on this device", page.fonts, page.look.dim)
                Text("$songs · Plays without the internet", style = page.fonts.title, color = page.look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = page.look.dim, modifier = Modifier.padding(start = 12.dp).size(20.dp))
        }
        return
    }
    if (page.wide) {
        Row(outer.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            likedCard(Modifier.weight(1.15f).fillMaxSize())
            downloadedCard(Modifier.weight(1f).fillMaxSize())
        }
    } else {
        Column(outer.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            likedCard(Modifier.fillMaxWidth().height(180.dp))
            downloadedCard(Modifier.fillMaxWidth())
        }
    }
}

/** The chips: what is shown, then (but on Downloaded) the order as a chip with its menu and the records-or-rows switch. */
@Composable
private fun LibraryChips(chip: LibraryChip, onChip: (LibraryChip) -> Unit, sort: LibrarySort, grid: Boolean, onToggleGrid: () -> Unit, page: LibraryLook) {
    var sortOpen by remember { mutableStateOf(false) }
    OrbitChipRow(page.side, Modifier.padding(top = 22.dp, bottom = 6.dp).testTag("music:library:chips")) {
        LibraryChip.entries.forEach { entry ->
            OrbitSelectChip(entry.label, selected = entry == chip, fonts = page.fonts, look = page.look, onClick = { onChip(entry) }, haptic = entry != chip)
        }
        if (chip != LibraryChip.DOWNLOADED) {
            Spacer(Modifier.width(16.dp))
            Box {
                OrbitSelectChip(sortLabel(sort), selected = false, fonts = page.fonts, look = page.look, onClick = { sortOpen = true },
                    modifier = Modifier.testTag("music:library:sort"), icon = CosmoIcons.Sort)
                // The sort menu is the kit's popover (board batch-a, section 4): the order in force is solid ink.
                OrbitMenu(
                    open = sortOpen,
                    onDismiss = { sortOpen = false },
                    items = LibrarySort.entries.map { option ->
                        OrbitMenuItem(sortLabel(option), selected = option == sort) { AppUi.host.setLibrarySort(option) }
                    },
                    fonts = page.fonts,
                    look = page.look,
                    header = "Sort by",
                    width = 240.dp,
                )
            }
            if (chip != LibraryChip.SONGS) {
                OrbitRoundButton(
                    if (grid) CosmoIcons.List else CosmoIcons.Grid,
                    stringResource(if (grid) Res.string.cosmo_view_list else Res.string.cosmo_view_grid),
                    page.look.ink, onClick = onToggleGrid, modifier = Modifier.testTag("music:library:view"),
                )
            }
        }
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

private fun LazyListScope.sectionHeader(key: String, title: String, page: LibraryLook) {
    item(key = key) { OrbitSectionHeader(title, "", page.fonts, page.look, Modifier.padding(horizontal = page.side + 6.dp).padding(top = 28.dp, bottom = 12.dp)) }
}

// ---- Playlists ----------------------------------------------------------------------------------------------------------

/** Signed out: Playlists is Replay, the chips that make a playlist, and the sign-in message; the other chips say to sign in. */
private fun LazyListScope.signedOutSegment(
    chip: LibraryChip,
    replay: @Composable () -> Unit,
    onNewPlaylist: () -> Unit,
    onImportSpotifyPlaylist: (() -> Unit)?,
    onSignIn: () -> Unit,
    page: LibraryLook,
) {
    if (chip == LibraryChip.PLAYLISTS) {
        item(key = "replay") { replay() }
        makeChips(onNewPlaylist, onImportSpotifyPlaylist, page)
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
 * Replay, the chips that make a playlist, the playlists (pinned first, the liked one left to its card) as records or rows, and
 * then every other shelf of the feed (podcasts and the like) as a rail of records. A fresh account has no Playlists shelf at
 * all, and that is exactly the account most in need of the chip that makes one, so the chips are drawn either way.
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
    page: LibraryLook,
) {
    val layout = LibrarySegments.playlistLayout(LibrarySegments.shelfFor(LibrarySegment.PLAYLISTS, shelves)?.items.orEmpty(), pinned)
    item(key = "replay") { replay() }
    makeChips(onNewPlaylist, onImportSpotifyPlaylist, page)
    // The liked playlist has its card above; the mix the old page gave a tile of its own is a record like the rest.
    val rows = LibrarySegments.sorted(listOfNotNull(layout.mix) + layout.rows, page.sort)
    if (page.grid) recordGrid("playlist", rows, onClick, onLongPress, page)
    else itemsIndexed(rows, key = { index, item -> "playlist:" + shelfKey(item, index) }) { _, item ->
        val pinnedItem = item.browseId != null && item.browseId in pinned
        OrbitSongRow(
            title = item.title,
            subtitle = localizeCardSubtitle(item.subtitle),
            cover = item.thumbnailUrl,
            fonts = page.fonts,
            look = page.look,
            onClick = { onClick(item) },
            modifier = Modifier.padding(horizontal = page.side, vertical = 4.dp).fillMaxWidth(),
            onLongClick = { onLongPress(item) },
            endPadding = 6.dp,
            trailing = {
                if (pinnedItem) PinMark(page)
                MoreButton(page) { onLongPress(item) }
            },
        )
    }
    for (shelf in LibrarySegments.otherShelves(shelves)) {
        if (shelf.items.isEmpty()) continue
        sectionHeader("shelf-header:${shelf.title}", shelf.title, page)
        item(key = "shelf:${shelf.title}") {
            Column {
                RecordRail(shelf.items.take(LibrarySegments.RAIL_MAX_ITEMS), onClick, onLongPress, page)
                if (shelf.items.size > LibrarySegments.RAIL_MAX_ITEMS) {
                    Text(
                        "Show all ${shelf.items.size}", style = page.fonts.chip, color = page.look.accent,
                        modifier = Modifier.padding(start = page.side, top = 8.dp).clip(CircleShape).clickable { onShowAll(shelf) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The chips that make a playlist, and, when this build can import one, the chip that imports from Spotify. */
private fun LazyListScope.makeChips(onNewPlaylist: () -> Unit, onImportSpotifyPlaylist: (() -> Unit)?, page: LibraryLook) {
    item(key = "make") {
        OrbitChipRow(page.side, Modifier.padding(top = 14.dp, bottom = 6.dp)) {
            OrbitSelectChip(stringResource(Res.string.new_playlist), selected = false, fonts = page.fonts, look = page.look, onClick = onNewPlaylist, icon = CosmoIcons.Plus)
            if (onImportSpotifyPlaylist != null) {
                OrbitSelectChip(stringResource(Res.string.import_spotify), selected = false, fonts = page.fonts, look = page.look, onClick = onImportSpotifyPlaylist,
                    painter = painterResource(Res.drawable.spotify_logo))
            }
        }
    }
}

/** The pin that marks a pinned playlist at the end of its row. */
@Composable
private fun RowScope.PinMark(page: LibraryLook) {
    Icon(CosmoIcons.Pin, contentDescription = stringResource(Res.string.pinned), tint = page.look.dim, modifier = Modifier.padding(end = 2.dp).size(16.dp))
}

/** The more button at the end of a playlist's row: the same menu as a hold. */
@Composable
private fun RowScope.MoreButton(page: LibraryLook, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(CosmoIcons.MoreVertical, contentDescription = stringResource(Res.string.more), tint = page.look.dim, modifier = Modifier.size(20.dp))
    }
}

// ---- Albums, Artists and Songs ------------------------------------------------------------------------------------------

/** Albums: the Albums shelf as records with the play moon, or as rows. */
private fun LazyListScope.albumsSegment(albums: HomeShelf?, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit, page: LibraryLook) {
    val items = LibrarySegments.sorted(albums?.items.orEmpty(), page.sort)
    if (items.isEmpty()) {
        item(key = "albums:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    if (page.grid) recordGrid("albums", items, onClick, onLongPress, page)
    else itemRows("album", items, onClick, onLongPress, page)
}

/** Artists: from the Artists and Subscriptions shelves, as portraits or as rows with a round photo. */
private fun LazyListScope.artistsSegment(artists: HomeShelf?, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit, page: LibraryLook) {
    val items = LibrarySegments.sorted(artists?.items.orEmpty(), page.sort)
    if (items.isEmpty()) {
        item(key = "artists:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    if (page.grid) recordGrid("artist", items, onClick, onLongPress, page, portraits = true)
    else itemRows("artist", items, onClick, onLongPress, page)
}

/**
 * Songs: the tracks added to the library (the liked ones while there are none), as Home's rows in the library's order. A tap
 * plays from that row with the list behind it; without [onSongClick] it opens as a shelf item would.
 */
private fun LazyListScope.songsSegment(
    library: LibraryPage,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: ((List<Song>, Int) -> Unit)?,
    onSongLongPress: ((Song) -> Unit)?,
    onShelfItemClick: (ShelfItem) -> Unit,
    page: LibraryLook,
) {
    val songs = library.librarySongs.ifEmpty { library.likedSongs }.let { list ->
        when (page.sort) {
            LibrarySort.DEFAULT -> list
            LibrarySort.TITLE_ASC -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            LibrarySort.TITLE_DESC -> list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }
    if (songs.isEmpty()) {
        item(key = "songs:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    val isCurrentRow = playingRowMatcher(songs, currentSong)
    item(key = "songs:top") { Spacer(Modifier.height(8.dp)) }
    itemsIndexed(songs, key = { index, song -> "song:${song.videoId}:$index" }) { index, song ->
        val current = isCurrentRow(song)
        OrbitSongRowFor(
            song = song,
            fonts = page.fonts,
            look = page.look,
            onClick = {
                if (onSongClick != null) onSongClick(songs, index)
                else onShelfItemClick(ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, song = song))
            },
            modifier = Modifier.padding(horizontal = page.side, vertical = 4.dp),
            onLongClick = onSongLongPress?.let { held -> { held(song) } },
            now = current,
            playing = isPlaying && current,
        )
    }
}

/** Items as Home's rows: the disc, the title, and the line under it. A hold opens the menu. */
private fun LazyListScope.itemRows(prefix: String, items: List<ShelfItem>, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit, page: LibraryLook) {
    itemsIndexed(items, key = { index, item -> "$prefix:" + shelfKey(item, index) }) { _, item ->
        OrbitSongRow(
            title = item.title,
            subtitle = localizeCardSubtitle(item.subtitle),
            cover = item.thumbnailUrl,
            fonts = page.fonts,
            look = page.look,
            onClick = { onClick(item) },
            modifier = Modifier.padding(horizontal = page.side, vertical = 4.dp).fillMaxWidth(),
            onLongClick = { onLongPress(item) },
        )
    }
}

// ---- Downloaded ---------------------------------------------------------------------------------------------------------

/**
 * Downloaded: "Folders and servers" (Downloads, Local Music, and the remote libraries this build has) as rows with a chevron and,
 * under "On device", the releases kept on this device as records. It does not wait for the account's library: it is the device's own.
 */
private fun LazyListScope.downloadedSegment(
    links: List<LibraryLink>,
    deviceItems: List<ShelfItem>,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
    page: LibraryLook,
) {
    if (links.isEmpty() && deviceItems.isEmpty()) {
        item(key = "downloaded:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    if (links.isNotEmpty()) {
        item(key = "folders:header") {
            OrbitSectionHeader(stringResource(Res.string.cosmo_library_folders), "", page.fonts, page.look, Modifier.padding(horizontal = page.side + 6.dp).padding(top = 22.dp, bottom = 12.dp))
        }
        itemsIndexed(links, key = { index, link -> "folder:${link.item.browseId ?: link.item.title}#$index" }) { _, link ->
            val logo = link.logo?.let { painterResource(it) }
            OrbitSongRow(
                title = link.item.title,
                subtitle = link.item.subtitle,
                cover = null,
                fonts = page.fonts,
                look = page.look,
                onClick = { onClick(link.item) },
                modifier = Modifier.padding(horizontal = page.side, vertical = 4.dp).fillMaxWidth(),
                endPadding = 6.dp,
                discContent = {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(page.look.chip), contentAlignment = Alignment.Center) {
                        if (logo != null) Icon(logo, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(22.dp))
                        else Icon(link.icon, contentDescription = null, tint = page.look.ink, modifier = Modifier.size(20.dp))
                    }
                },
                trailing = { Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = page.look.dim, modifier = Modifier.padding(end = 10.dp).size(20.dp)) },
            )
        }
    }
    if (deviceItems.isNotEmpty()) {
        item(key = "device:header") {
            OrbitSectionHeader(stringResource(Res.string.on_device), "", page.fonts, page.look, Modifier.padding(horizontal = page.side + 6.dp).padding(top = 28.dp, bottom = 12.dp))
        }
        recordGrid("device", deviceItems, onClick, onLongPress, page)
    }
}

// ---- records ------------------------------------------------------------------------------------------------------------

/**
 * [items] as records (or portraits) in rows of the page's columns, each row one lazy item (a grid cannot nest in the list). A
 * short last row keeps its records on the left. The moon on a record opens it, as a tap does: its page plays it.
 */
private fun LazyListScope.recordGrid(
    prefix: String,
    items: List<ShelfItem>,
    onClick: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
    page: LibraryLook,
    portraits: Boolean = false,
) {
    val rows = items.chunked(page.columns.coerceAtLeast(1))
    itemsIndexed(rows, key = { index, _ -> "$prefix:row:$index" }) { index, row ->
        Row(
            Modifier.fillMaxWidth().padding(start = page.side, end = page.side, top = if (index == 0) 14.dp else 22.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            for (item in row) LibraryRecord(item, onClick, onLongPress, page, page.cell, portraits)
        }
    }
}

/** A shelf's items as one row of records (or portraits) that swipes sideways. */
@Composable
private fun RecordRail(items: List<ShelfItem>, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit, page: LibraryLook, portraits: Boolean = false) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = page.side + 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        flingBehavior = rememberShelfFlingBehavior(state),
        verticalAlignment = Alignment.Top,
    ) {
        itemsIndexed(items, key = { index, item -> shelfKey(item, index) }) { _, item ->
            LibraryRecord(item, onClick, onLongPress, page, if (portraits) 120.dp else 150.dp, portraits)
        }
    }
}

@Composable
private fun LibraryRecord(item: ShelfItem, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit, page: LibraryLook, size: Dp, portrait: Boolean) {
    if (portrait) {
        OrbitPortrait(item.title, item.thumbnailUrl, size, page.fonts, page.look, onClick = { onClick(item) }, onLongClick = { onLongPress(item) })
    } else {
        OrbitRecord(
            title = item.title,
            subtitle = localizeCardSubtitle(item.subtitle),
            cover = item.thumbnailUrl,
            size = size,
            fonts = page.fonts,
            look = page.look,
            onClick = { onClick(item) },
            onLongClick = { onLongPress(item) },
            onPlay = { onClick(item) },
        )
    }
}

package com.cosmotune.ui.library

import androidx.compose.ui.graphics.Color
import com.cosmotune.ui.orbit.OrbitSpace
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
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
 * The Library page in Orbit (the Music board's 3): the heading "Library." with a mono count, four 64 dp shortcut tiles (Liked songs
 * in their cover's colour with a small Play, Downloads, History, and the first pinned playlist), chips with counts that choose what
 * is shown (Playlists, Albums, Artists, Songs, Downloaded), one control row (find in the library, the order, records or rows), and
 * under them the chosen chip's page: playlists and albums as three columns of records with the play moon on their edge, artists as
 * portraits, songs as flat rows. LibrarySwitch calls it.
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
    /** Opens the listening history (its shortcut tile); null leaves the tile out. */
    onHistory: (() -> Unit)? = null,
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
    val pinnedPlaylist = remember(playlists, pinned, liked) { playlists.firstOrNull { it.browseId != null && it.browseId in pinned && it != liked } }
    // Find in the library: what the chosen chip shows, narrowed by title (and by artist for songs). Cleared with the chip.
    var query by rememberSaveable(segmentIndex) { mutableStateOf("") }
    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val side = if (wide) 28.dp else 16.dp
            val (columns, cell) = orbitRecordGrid(maxWidth, side)
            val page = LibraryLook(fonts, look, side, wide, columns, cell, librarySort, grid, query.trim())
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "heading") {
                    val count = library?.shelves?.let { shelves ->
                        listOf(LibrarySegment.PLAYLISTS, LibrarySegment.ALBUMS, LibrarySegment.ARTISTS).sumOf { LibrarySegments.shelfFor(it, shelves)?.items?.size ?: 0 }
                    } ?: 0
                    LibraryHeading(count, onNewPlaylist, page)
                }
                item(key = "lead") {
                    ShortcutTiles(
                        liked = liked.takeIf { signedIn },
                        likedSongs = if (signedIn) library?.likedSongs.orEmpty() else emptyList(),
                        likedComplete = library?.likedContinuation == null,
                        downloadedCount = downloadedCount,
                        pinned = pinnedPlaylist.takeIf { signedIn },
                        page = page,
                        onOpen = onShelfItemClick,
                        onHold = onShelfItemLongPress,
                        onPlay = onSongClick,
                        onDownloaded = { segmentIndex = LibraryChip.DOWNLOADED.ordinal },
                        onHistory = onHistory,
                    )
                }
                item(key = "segments") {
                    val counts = library?.let { libraryCounts(it, pinned, liked) }.orEmpty() + (LibraryChip.DOWNLOADED to downloadedCount)
                    LibraryChips(chip = chip, onChip = { segmentIndex = it.ordinal }, counts = counts, page = page)
                }
                item(key = "controls") {
                    LibraryControls(
                        chip = chip,
                        query = query,
                        onQuery = { query = it },
                        sort = librarySort,
                        grid = grid,
                        onGrid = { wanted -> listSegments = if (wanted) listSegments - chip.ordinal else listSegments + chip.ordinal },
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

/** How the page is drawn: the Orbit fonts and colours, the gutter, the records' grid, the order, records or rows, and what is looked for. */
private class LibraryLook(
    val fonts: OrbitFonts,
    val look: OrbitLook,
    val side: Dp,
    val wide: Boolean,
    val columns: Int,
    val cell: Dp,
    val sort: LibrarySort,
    val grid: Boolean,
    val query: String,
)

/** [items] whose title (or line under it) holds the page's query, whatever the case; everything while nothing is typed. */
private fun List<ShelfItem>.matching(page: LibraryLook): List<ShelfItem> =
    if (page.query.isEmpty()) this else filter { it.title.contains(page.query, ignoreCase = true) || it.subtitle.contains(page.query, ignoreCase = true) }

/** How many each chip shows: the chips' counts. */
private fun libraryCounts(library: LibraryPage, pinned: List<String>, liked: ShelfItem?): Map<LibraryChip, Int> {
    val playlists = LibrarySegments.playlistLayout(LibrarySegments.shelfFor(LibrarySegment.PLAYLISTS, library.shelves)?.items.orEmpty(), pinned)
    return mapOf(
        LibraryChip.PLAYLISTS to (listOfNotNull(playlists.mix).size + playlists.rows.size),
        LibraryChip.ALBUMS to (LibrarySegments.shelfFor(LibrarySegment.ALBUMS, library.shelves)?.items?.size ?: 0),
        LibraryChip.ARTISTS to (LibrarySegments.shelfFor(LibrarySegment.ARTISTS, library.shelves)?.items?.size ?: 0),
        LibraryChip.SONGS to library.librarySongs.ifEmpty { library.likedSongs }.size,
    )
}

// ---- the head of the page -----------------------------------------------------------------------------------------------

/** "Library." with the mono count above it and, at its end, the round new-playlist button. */
@Composable
private fun LibraryHeading(count: Int, onNewPlaylist: () -> Unit, page: LibraryLook) {
    OrbitPageHeading(
        title = "Library",
        label = if (count > 0) "Your library · $count items" else "Your library",
        fonts = page.fonts,
        look = page.look,
        modifier = Modifier.padding(start = page.side + 4.dp, end = page.side, top = 6.dp),
    ) {
        OrbitRoundButton(CosmoIcons.Plus, stringResource(Res.string.new_playlist), page.look.ink, onClick = onNewPlaylist, onInk = page.look.ground, touch = 48.dp)
    }
}

/**
 * The shortcut tiles (the Music board's 3): two columns of 64 dp tiles. Liked songs in its cover's colour, with the count and a small
 * peach Play that plays them from the top ([onPlay]; without it, or before they are read, it opens the playlist); Downloads with its
 * count, which opens the Downloaded chip; History ([onHistory]); and the first [pinned] playlist. Signed out, Downloads and History.
 */
@Composable
private fun ShortcutTiles(
    liked: ShelfItem?,
    likedSongs: List<Song>,
    likedComplete: Boolean,
    downloadedCount: Int,
    pinned: ShelfItem?,
    page: LibraryLook,
    onOpen: (ShelfItem) -> Unit,
    onHold: (ShelfItem) -> Unit,
    onPlay: ((List<Song>, Int) -> Unit)?,
    onDownloaded: () -> Unit,
    onHistory: (() -> Unit)?,
) {
    val tiles = mutableListOf<@Composable (Modifier) -> Unit>()
    if (liked != null || likedSongs.isNotEmpty()) tiles += { m ->
        val (fill, ink) = coverFill(liked?.thumbnailUrl ?: likedSongs.firstOrNull()?.thumbnailUrl, "liked")
        val play: () -> Unit = { if (onPlay != null && likedSongs.isNotEmpty()) onPlay(likedSongs, 0) else liked?.let(onOpen) }
        val open: () -> Unit = { if (liked != null) onOpen(liked) else play() }
        val count = if (likedComplete && likedSongs.isNotEmpty()) songsText(likedSongs.size) else "Liked songs"
        val hold: (() -> Unit)? = liked?.let { { onHold(it) } }
        ShortcutTile("Liked", count, page, m, fill = fill, ink = ink, onClick = open, onLongClick = hold,
            lead = { TileIcon(CosmoIcons.HeartFill, ink, ink.copy(alpha = 0.16f)) },
            trailing = { OrbitPlayButton(playing = false, onClick = play, look = page.look, size = 32.dp, touch = 48.dp) },
        )
    }
    tiles += { m ->
        ShortcutTile("Downloads", if (downloadedCount == 0) "Nothing yet" else songsText(downloadedCount), page, m, onClick = onDownloaded,
            lead = { TileIcon(CosmoIcons.Download, page.look.ink, page.look.chip) }, testTag = "music:library:downloads")
    }
    if (onHistory != null) tiles += { m ->
        ShortcutTile("History", "What you played", page, m, onClick = onHistory, lead = { TileIcon(CosmoIcons.Clock, page.look.ink, page.look.chip) }, testTag = "music:library:history")
    }
    if (pinned != null) tiles += { m ->
        ShortcutTile(pinned.title, listOf("Pinned", localizeCardSubtitle(pinned.subtitle)).filter { it.isNotBlank() }.joinToString(" · "), page, m,
            onClick = { onOpen(pinned) }, onLongClick = { onHold(pinned) }, lead = { Disc(pinned.thumbnailUrl, 44.dp) })
    }
    val columns = if (page.wide) 4 else 2
    Column(Modifier.padding(horizontal = page.side).padding(top = 16.dp).testTag("music:library:tiles"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile -> tile(Modifier.weight(1f)) }
                repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun songsText(count: Int): String = if (count == 1) "1 song" else "$count songs"

/** One 64 dp shortcut tile: [lead] (a 44 dp round icon or a disc), the [title] over its [line], and [trailing]; quiet ink, or [fill]. */
@Composable
private fun ShortcutTile(
    title: String,
    line: String,
    page: LibraryLook,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    fill: Color = page.look.chip,
    ink: Color = page.look.ink,
    testTag: String? = null,
    lead: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.height(64.dp).clip(RoundedCornerShape(22.dp)).background(fill)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongClick).contextClick(onLongClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        lead()
        Column(Modifier.weight(1f)) {
            Text(title, style = page.fonts.title.copy(fontSize = 14.5.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = page.fonts.body.copy(fontSize = 12.sp, fontWeight = FontWeight(600)), color = if (fill == page.look.chip) page.look.dim else ink.copy(alpha = 0.74f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun TileIcon(icon: ImageVector, ink: Color, ground: Color) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

/** The chips that choose what is shown, each with its count. */
@Composable
private fun LibraryChips(chip: LibraryChip, onChip: (LibraryChip) -> Unit, counts: Map<LibraryChip, Int>, page: LibraryLook) {
    OrbitChipRow(page.side, Modifier.padding(top = 20.dp).testTag("music:library:chips")) {
        LibraryChip.entries.forEach { entry ->
            OrbitSelectChip(entry.label, selected = entry == chip, fonts = page.fonts, look = page.look, onClick = { onChip(entry) }, haptic = entry != chip, count = counts[entry])
        }
    }
}

/**
 * The one control row under the chips: find in what the chip shows (a 40 dp field), the order as a chip with its menu, and the
 * records-or-rows switch (two segments). Downloaded has the field alone; Songs has no records to switch to.
 */
@Composable
private fun LibraryControls(chip: LibraryChip, query: String, onQuery: (String) -> Unit, sort: LibrarySort, grid: Boolean, onGrid: (Boolean) -> Unit, page: LibraryLook) {
    var sortOpen by remember { mutableStateOf(false) }
    val look = page.look
    Row(
        Modifier.fillMaxWidth().padding(horizontal = page.side).padding(top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(40.dp).clip(CircleShape).background(look.chip).padding(start = 14.dp, end = 6.dp).testTag("music:library:find"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CosmoIcons.Search, contentDescription = null, tint = look.dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val text = page.fonts.chip.copy(fontWeight = FontWeight(600))
                if (query.isEmpty()) Text("Find in ${chip.label.lowercase()}", style = text, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true, textStyle = text.copy(color = look.ink),
                    cursorBrush = SolidColor(look.accent), modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) OrbitRoundButton(CosmoIcons.Close, "Clear", look.ink, onClick = { onQuery("") }, size = 28.dp)
        }
        if (chip != LibraryChip.DOWNLOADED) {
            Box {
                OrbitSelectChip(sortLabel(sort), selected = false, fonts = page.fonts, look = look, onClick = { sortOpen = true },
                    modifier = Modifier.testTag("music:library:sort"), icon = CosmoIcons.Sort)
                // The sort menu is the kit's popover (board batch-a, section 4): the order in force is solid ink.
                OrbitMenu(
                    open = sortOpen,
                    onDismiss = { sortOpen = false },
                    items = LibrarySort.entries.map { option ->
                        OrbitMenuItem(sortLabel(option), selected = option == sort) { AppUi.host.setLibrarySort(option) }
                    },
                    fonts = page.fonts,
                    look = look,
                    header = "Sort by",
                    width = 240.dp,
                )
            }
            if (chip != LibraryChip.SONGS) {
                Row(Modifier.height(40.dp).clip(CircleShape).background(look.chip).padding(3.dp).testTag("music:library:view"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((records, icon, label) in listOf(Triple(true, CosmoIcons.Grid, Res.string.cosmo_view_grid), Triple(false, CosmoIcons.List, Res.string.cosmo_view_list))) {
                        val on = grid == records
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(if (on) look.ink else Color.Transparent)
                                .selectable(selected = on, role = Role.Tab, onClick = { onGrid(records) }),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, contentDescription = stringResource(label), tint = if (on) look.ground else look.dim, modifier = Modifier.size(18.dp)) }
                    }
                }
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
    item(key = key) { OrbitSectionHeader(title, "", page.fonts, page.look, Modifier.padding(horizontal = page.side + 4.dp).padding(top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent)) }
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
    val rows = LibrarySegments.sorted(listOfNotNull(layout.mix) + layout.rows, page.sort).matching(page)
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
            modifier = Modifier.padding(horizontal = page.side).fillMaxWidth(),
            onLongClick = { onLongPress(item) },
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
    val items = LibrarySegments.sorted(albums?.items.orEmpty(), page.sort).matching(page)
    if (items.isEmpty()) {
        item(key = "albums:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    if (page.grid) recordGrid("albums", items, onClick, onLongPress, page)
    else itemRows("album", items, onClick, onLongPress, page)
}

/** Artists: from the Artists and Subscriptions shelves, as portraits or as rows with a round photo. */
private fun LazyListScope.artistsSegment(artists: HomeShelf?, onClick: (ShelfItem) -> Unit, onLongPress: (ShelfItem) -> Unit, page: LibraryLook) {
    val items = LibrarySegments.sorted(artists?.items.orEmpty(), page.sort).matching(page)
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
    }.let { list ->
        if (page.query.isEmpty()) list
        else list.filter { it.title.contains(page.query, ignoreCase = true) || it.artist.contains(page.query, ignoreCase = true) }
    }
    if (songs.isEmpty()) {
        item(key = "songs:empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
        return
    }
    val isCurrentRow = playingRowMatcher(songs, currentSong)
    item(key = "songs:top") { Spacer(Modifier.height(4.dp)) }
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
            modifier = Modifier.padding(horizontal = page.side),
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
            modifier = Modifier.padding(horizontal = page.side).fillMaxWidth(),
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
            OrbitSectionHeader(stringResource(Res.string.cosmo_library_folders), "", page.fonts, page.look, Modifier.padding(horizontal = page.side + 4.dp).padding(top = 14.dp, bottom = OrbitSpace.sectionToContent))
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
                modifier = Modifier.padding(horizontal = page.side).fillMaxWidth(),
                discContent = {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(page.look.chip), contentAlignment = Alignment.Center) {
                        if (logo != null) Icon(logo, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(22.dp))
                        else Icon(link.icon, contentDescription = null, tint = page.look.ink, modifier = Modifier.size(20.dp))
                    }
                },
                trailing = { Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = page.look.dim, modifier = Modifier.padding(end = 12.dp).size(20.dp)) },
            )
        }
    }
    val device = deviceItems.matching(page)
    if (device.isNotEmpty()) {
        item(key = "device:header") {
            OrbitSectionHeader(stringResource(Res.string.on_device), "", page.fonts, page.look, Modifier.padding(horizontal = page.side + 4.dp).padding(top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent))
        }
        recordGrid("device", device, onClick, onLongPress, page)
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
            Modifier.fillMaxWidth().padding(start = page.side, end = page.side, top = if (index == 0) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        contentPadding = PaddingValues(horizontal = page.side),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberShelfFlingBehavior(state),
        verticalAlignment = Alignment.Top,
    ) {
        itemsIndexed(items, key = { index, item -> shelfKey(item, index) }) { _, item ->
            LibraryRecord(item, onClick, onLongPress, page, if (portraits) 112.dp else 140.dp, portraits)
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

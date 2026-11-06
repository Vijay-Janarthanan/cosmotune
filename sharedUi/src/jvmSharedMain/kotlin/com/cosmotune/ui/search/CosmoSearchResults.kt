package com.cosmotune.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.model.BrowseItem
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.albums
import com.cosmotune.music.sharedui.resources.artists
import com.cosmotune.music.sharedui.resources.cosmo_search_no_results
import com.cosmotune.music.sharedui.resources.cosmo_search_no_results_hint
import com.cosmotune.music.sharedui.resources.cosmo_songs
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.playlist_action
import com.cosmotune.music.sharedui.resources.playlists
import com.cosmotune.music.sharedui.resources.search_addons_empty
import com.cosmotune.music.sharedui.resources.search_addons_no_results
import com.cosmotune.music.sharedui.resources.search_library_empty
import com.cosmotune.music.sharedui.resources.search_library_no_results
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.music.ui.components.rememberShelfFlingBehavior
import com.cosmotune.music.ui.components.songListSkeleton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitArtistDisc
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitPortrait
import com.cosmotune.ui.orbit.OrbitRecord
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitSongRowFor
import com.cosmotune.ui.orbit.coverFill
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/*
 * The result pages of Search in Orbit (Canvas repo, design/cosmotune/pages-orbit/board.html, section 3): the top result as the
 * colour card with Play in its bite, songs as Home's rows, albums and playlists as records with the play moon on their edge,
 * artists as portraits; the Library and Add-ons sources' songs; and the wide page, where the top result and the songs stand side
 * by side.
 */

/** From this width on, the top result and the songs of "All" stand side by side. */
internal const val WIDE_SEARCH_DP = 840f

/** What the rows of the result pages need from the page, so the functions below do not each take a dozen lambdas. */
internal class SearchRowActions(
    val currentSong: Song?,
    val isPlaying: Boolean,
    val onSongClick: (List<Song>, Int) -> Unit,
    val onSongLongPress: (Song) -> Unit,
    val onSongSwipe: (Song) -> Unit,
    val onTopResultPlay: (Song) -> Unit,
    val onTopResultPlaylist: (Song) -> Unit,
    val onBrowseClick: (BrowseItem) -> Unit,
    val onBrowseLongPress: ((BrowseItem) -> Unit)?,
)

/** How the page is drawn: the Orbit fonts and colours, the gutter, whether it is wide, and the records' grid. */
internal class SearchPage(val fonts: OrbitFonts, val look: OrbitLook, val side: Dp, val wide: Boolean, val columns: Int, val cell: Dp)

/** The kinds of section the old screen's `searchSections` made, by what they hold (it named them in English; the page names them from the strings). */
internal enum class SearchSectionKind(val title: StringResource?) {
    /** The single section of a filtered search: no heading. */
    PLAIN(null),
    SONGS(Res.string.cosmo_songs),
    ARTISTS(Res.string.artists),
    ALBUMS(Res.string.albums),
    PLAYLISTS(Res.string.playlists),
    MORE(Res.string.more),
}

internal class SearchSection(val kind: SearchSectionKind, val rows: List<SearchResult>)

/**
 * The top result of a page: only on "All", the promoted card YouTube puts first, a song ([SearchResult.TopTrack]) or, when the
 * card is an artist, an album or a playlist, that [SearchResult.Browse] (the parser puts it at the head of the rows).
 */
internal fun searchTop(rows: List<SearchResult>, filter: SearchFilter): SearchResult? {
    if (filter != SearchFilter.ALL) return null
    val first = rows.firstOrNull()
    return first?.takeIf { it is SearchResult.TopTrack || it is SearchResult.Browse } ?: rows.firstOrNull { it is SearchResult.TopTrack }
}

/**
 * What the old screen's `searchSections` decided, kept as it was: a filter other than "All" shows its rows as one section with no
 * heading; "All" splits them into songs, artists, albums, playlists and the rest, and leaves out a section with nothing in it.
 * The promoted top track is not in the songs, and a promoted [top] of another kind is not in its section: each has its own card.
 */
internal fun searchSections(rows: List<SearchResult>, filter: SearchFilter, top: SearchResult? = null): List<SearchSection> {
    if (filter != SearchFilter.ALL) return listOf(SearchSection(SearchSectionKind.PLAIN, rows))
    val browse = rows.filterIsInstance<SearchResult.Browse>().filter { it !== top }
    return listOf(
        SearchSection(SearchSectionKind.SONGS, rows.filterIsInstance<SearchResult.Track>()),
        SearchSection(SearchSectionKind.ARTISTS, browse.filter { it.item.type == BrowseType.ARTIST }),
        SearchSection(SearchSectionKind.ALBUMS, browse.filter { it.item.type == BrowseType.ALBUM }),
        SearchSection(SearchSectionKind.PLAYLISTS, browse.filter { it.item.type == BrowseType.PLAYLIST }),
        SearchSection(SearchSectionKind.MORE, browse.filter { it.item.type == BrowseType.OTHER }),
    ).filter { it.rows.isNotEmpty() }
}

/** The words for a thing that came with no subtitle: its type, as the old rows said it ("Album"). */
internal fun typeLabel(typeName: String): String =
    typeName.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }

// ---------------------------------------------------------------------------------------------------------------------------
// the rows

/**
 * The rows of a successful YouTube Music search (the page of "All", or of one filter). Nothing to show gives the "no results"
 * message; [loadingMore] puts three skeleton rows at the end, as the old page did.
 */
internal fun LazyListScope.resultRows(
    rows: List<SearchResult>,
    query: String,
    filter: SearchFilter,
    page: SearchPage,
    loadingMore: Boolean,
    actions: SearchRowActions,
) {
    // The queue a tap starts from: the songs of the page, the promoted one included (the old screen's `tracks`).
    val tracks = rows.mapNotNull { row ->
        when (row) {
            is SearchResult.TopTrack -> row.song
            is SearchResult.Track -> row.song
            is SearchResult.Browse -> null
        }
    }
    val top = searchTop(rows, filter)
    val sections = searchSections(rows, filter, top)
    if (top == null && sections.all { it.rows.isEmpty() }) {
        item(key = "search:no-results") {
            MessageState(
                stringResource(Res.string.cosmo_search_no_results, "“${query.trim()}”") + "\n" +
                    stringResource(Res.string.cosmo_search_no_results_hint),
            )
        }
        return
    }
    val songs = sections.firstOrNull { it.kind == SearchSectionKind.SONGS }
    if (top != null) {
        item(key = "search:top-result:${topKey(top)}") {
            if (page.wide) WideTopAndSongs(top, songs?.rows.orEmpty(), tracks, actions, page)
            else TopResultCard(top, actions, page, Modifier.padding(horizontal = page.side).padding(top = 8.dp).fillMaxWidth().height(250.dp))
        }
    }
    for (section in sections) {
        when (section.kind) {
            SearchSectionKind.SONGS -> if (!(page.wide && top != null)) songSection(section, tracks, actions, page)
            SearchSectionKind.ARTISTS -> portraitShelf(section, actions, page)
            SearchSectionKind.ALBUMS, SearchSectionKind.PLAYLISTS, SearchSectionKind.MORE -> recordShelf(section, actions, page)
            SearchSectionKind.PLAIN -> plainSection(section, tracks, actions, page)
        }
    }
    if (loadingMore) {
        songListSkeleton(count = 3, keyPrefix = "skeleton:search:more", circular = filter == SearchFilter.ARTISTS)
    }
}

private fun topKey(top: SearchResult): String = when (top) {
    is SearchResult.TopTrack -> top.song.videoId
    is SearchResult.Track -> top.song.videoId
    is SearchResult.Browse -> top.item.browseId
}

/** A section's heading in the wide face. */
private fun LazyListScope.sectionTitle(key: String, title: StringResource, page: SearchPage) {
    item(key = key) {
        OrbitSectionHeader(stringResource(title), "", page.fonts, page.look, Modifier.padding(horizontal = page.side + 6.dp).padding(top = 28.dp, bottom = 12.dp))
    }
}

/** The songs of "All" as Home's rows under their heading. Keys as the old screen's. */
private fun LazyListScope.songSection(section: SearchSection, tracks: List<Song>, actions: SearchRowActions, page: SearchPage) {
    sectionTitle("search-section:${section.kind}", requireNotNull(section.kind.title), page)
    itemsIndexed(section.rows, key = { index, row -> rowKey(row, index) }) { _, row ->
        val song = (row as? SearchResult.Track)?.song ?: return@itemsIndexed
        SearchSongRow(song, tracks, actions, page, Modifier.padding(horizontal = page.side, vertical = 4.dp))
    }
}

private fun rowKey(row: SearchResult, index: Int): String = when (row) {
    is SearchResult.TopTrack -> "top_${row.song.videoId}_$index"
    is SearchResult.Track -> "track_${row.song.videoId}_$index"
    is SearchResult.Browse -> "browse_${row.item.browseId}_$index"
}

/** What holding a release does: its menu; holding an artist does nothing (the page has no menu for one). */
private fun holdFor(item: BrowseItem, actions: SearchRowActions): (() -> Unit)? =
    item.takeIf { it.type != BrowseType.ARTIST }?.let { release -> actions.onBrowseLongPress?.let { held -> { held(release) } } }

/** Albums, playlists or the rest as a row of records that swipes sideways; albums and playlists wear the play moon. */
private fun LazyListScope.recordShelf(section: SearchSection, actions: SearchRowActions, page: SearchPage) {
    val items = section.rows.filterIsInstance<SearchResult.Browse>().map { it.item }
    sectionTitle("search-section:${section.kind}", requireNotNull(section.kind.title), page)
    item(key = "search-shelf:${section.kind}") {
        val state = rememberLazyListState()
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = page.side + 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            flingBehavior = rememberShelfFlingBehavior(state),
            verticalAlignment = Alignment.Top,
        ) {
            itemsIndexed(items, key = { index, item -> "release_${item.browseId}_$index" }) { _, item -> SearchRecord(item, actions, page, if (page.wide) 150.dp else 140.dp) }
        }
    }
}

/** The artists as a row of portraits. */
private fun LazyListScope.portraitShelf(section: SearchSection, actions: SearchRowActions, page: SearchPage) {
    val artists = section.rows.filterIsInstance<SearchResult.Browse>().map { it.item }
    sectionTitle("search-section:${section.kind}", requireNotNull(section.kind.title), page)
    item(key = "search-shelf:${section.kind}") {
        val state = rememberLazyListState()
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = page.side + 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            flingBehavior = rememberShelfFlingBehavior(state),
            verticalAlignment = Alignment.Top,
        ) {
            itemsIndexed(artists, key = { index, artist -> "artist_${artist.browseId}_$index" }) { _, artist ->
                OrbitPortrait(artist.title, artist.thumbnailUrl, 110.dp, page.fonts, page.look, onClick = { actions.onBrowseClick(artist) })
            }
        }
    }
}

@Composable
private fun SearchRecord(item: BrowseItem, actions: SearchRowActions, page: SearchPage, size: Dp) {
    if (item.type == BrowseType.ARTIST) {
        OrbitPortrait(item.title, item.thumbnailUrl, size, page.fonts, page.look, onClick = { actions.onBrowseClick(item) })
        return
    }
    OrbitRecord(
        title = item.title,
        subtitle = item.subtitle.ifBlank { typeLabel(item.type.name) },
        cover = item.thumbnailUrl,
        size = size,
        fonts = page.fonts,
        look = page.look,
        onClick = { actions.onBrowseClick(item) },
        onLongClick = holdFor(item, actions),
        // The moon opens the release, whose page plays it: the search has no way to play a whole album from here.
        onPlay = if (item.type == BrowseType.OTHER) null else ({ actions.onBrowseClick(item) }),
    )
}

/**
 * The page of one filter: its songs as rows, and its albums, playlists or artists as a grid of records or portraits, one lazy
 * item per row of the grid.
 */
private fun LazyListScope.plainSection(section: SearchSection, tracks: List<Song>, actions: SearchRowActions, page: SearchPage) {
    item(key = "search-plain:top") { Spacer(Modifier.height(6.dp)) }
    val songs = section.rows.withIndex().filter { it.value is SearchResult.Track || it.value is SearchResult.TopTrack }
    itemsIndexed(songs, key = { _, row -> rowKey(row.value, row.index) }) { _, row ->
        val song = when (val r = row.value) { is SearchResult.Track -> r.song; is SearchResult.TopTrack -> r.song; else -> return@itemsIndexed }
        SearchSongRow(song, tracks, actions, page, Modifier.padding(horizontal = page.side, vertical = 4.dp))
    }
    val grid = section.rows.filterIsInstance<SearchResult.Browse>().map { it.item }.chunked(page.columns)
    itemsIndexed(grid, key = { index, row -> "grid_${row.first().browseId}_$index" }) { index, row ->
        Row(
            Modifier.fillMaxWidth().padding(start = page.side, end = page.side, top = if (index == 0) 8.dp else 22.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            row.forEach { item -> SearchRecord(item, actions, page, page.cell) }
        }
    }
}

/** A song of the results: a tap plays it with the page's songs as the list, a hold opens its menu, a swipe queues it. */
@Composable
private fun SearchSongRow(song: Song, tracks: List<Song>, actions: SearchRowActions, page: SearchPage, modifier: Modifier = Modifier) {
    // The row that was played lights, not every namesake among the page's songs: see [playingRowMatcher].
    val isCurrentRow = remember(tracks, actions.currentSong) { playingRowMatcher(tracks, actions.currentSong) }
    val current = isCurrentRow(song)
    OrbitSongRowFor(
        song = song,
        fonts = page.fonts,
        look = page.look,
        onClick = { actions.onSongClick(tracks, tracks.indexOf(song).coerceAtLeast(0)) },
        modifier = modifier,
        onLongPress = { actions.onSongLongPress(song) },
        onSwipe = { actions.onSongSwipe(song) },
        now = current,
        playing = actions.isPlaying && current,
    )
}

/** An album, an artist, a playlist or the rest as Home's row: the disc, the name, and what it is. */
@Composable
internal fun BrowseListRow(item: BrowseItem, onClick: () -> Unit, onLongPress: (() -> Unit)?, page: SearchPage, modifier: Modifier = Modifier) {
    OrbitSongRow(
        title = item.title,
        subtitle = item.subtitle.ifBlank { typeLabel(item.type.name) },
        cover = item.thumbnailUrl,
        fonts = page.fonts,
        look = page.look,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        onLongClick = onLongPress,
    )
}

// ---------------------------------------------------------------------------------------------------------------------------
// the Library and Add-ons sources

/**
 * The Library source's page: the Local Music folder's tracks that match, as song rows. An artist or album hit needs no row of its
 * own: every track under that name matches too, so they are already here. With [addons] it is the Add-ons source's page instead:
 * the enabled add-ons' rows for the submitted query, each leading its artist line with the tier its source offered.
 */
internal fun LazyListScope.libraryRows(
    query: String,
    results: UiState<List<Song>>?,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    page: SearchPage,
    addons: Boolean = false,
) {
    val prefix = if (addons) "addon" else "library"
    when (results) {
        null -> item(key = "$prefix:empty") {
            MessageState(stringResource(if (addons) Res.string.search_addons_empty else Res.string.search_library_empty))
        }
        UiState.Loading -> songListSkeleton(keyPrefix = "skeleton:search:$prefix")
        is UiState.Error -> item(key = "$prefix:error") { MessageState(results.message) }
        is UiState.Success -> if (results.data.isEmpty()) {
            item(key = "$prefix:no-match") {
                MessageState(stringResource(if (addons) Res.string.search_addons_no_results else Res.string.search_library_no_results, query))
            }
        } else {
            val tracks = results.data
            val isCurrentRow = playingRowMatcher(tracks, currentSong)
            itemsIndexed(tracks, key = { index, song -> "${prefix}_${song.videoId}_$index" }) { index, song ->
                val current = isCurrentRow(song)
                OrbitSongRowFor(
                    song = song,
                    fonts = page.fonts,
                    look = page.look,
                    onClick = { onSongClick(tracks, index) },
                    modifier = Modifier.padding(horizontal = page.side, vertical = 4.dp),
                    onLongPress = { onSongLongPress(song) },
                    onSwipe = { onSongSwipe(song) },
                    now = current,
                    playing = isPlaying && current,
                    // Rows from several catalogues side by side: each says what its source offered (BC-30).
                    showSourceQuality = addons,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// the top result

/**
 * The promoted hit as the colour card in its cover's colour: the cover as a disc (an artist's plays Apple's motion portrait when
 * there is one and it may play), the mono "TOP RESULT · SONG" label, the title and the line under it, and Play in the bite. A
 * song also has the round add-to-playlist button. A tap does what Play does; a hold (or a right click) opens the menu. Narrow, the
 * disc stands at the top and the words at the foot; wide, the words stand beside the disc.
 */
@Composable
private fun TopResultCard(top: SearchResult, actions: SearchRowActions, page: SearchPage, modifier: Modifier) {
    val fonts = page.fonts
    val song = (top as? SearchResult.TopTrack)?.song ?: (top as? SearchResult.Track)?.song
    val item = (top as? SearchResult.Browse)?.item
    val title = song?.title ?: item?.title.orEmpty()
    val subtitle = song?.artist ?: item?.let { it.subtitle.ifBlank { typeLabel(it.type.name) } }.orEmpty()
    val cover = song?.thumbnailUrl ?: item?.thumbnailUrl
    val kind = when {
        song != null -> if (song.isVideo) "Video" else "Song"
        item?.type == BrowseType.ARTIST -> "Artist"
        item?.type == BrowseType.ALBUM -> "Album"
        item?.type == BrowseType.PLAYLIST -> "Playlist"
        else -> "Result"
    }
    val (fill, ink) = coverFill(cover, title)
    val open: () -> Unit = { if (song != null) actions.onTopResultPlay(song) else if (item != null) actions.onBrowseClick(item) }
    val hold: (() -> Unit)? = when {
        song != null -> ({ actions.onSongLongPress(song) })
        item != null -> holdFor(item, actions)
        else -> null
    }
    OrbitBiteCard(fill = fill, modifier = modifier, bite = { OrbitPlayButton(playing = false, onClick = open, look = page.look) }) {
        Box(Modifier.fillMaxSize().longPressMenuClickable(onClick = open, onLongClick = hold).contextClick(hold))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val beside = maxWidth >= 380.dp && maxHeight < 240.dp
            val discSize = if (beside) 110.dp else 104.dp
            val disc: @Composable (Modifier) -> Unit = { m ->
                if (item?.type == BrowseType.ARTIST) OrbitArtistDisc(item.title, cover, discSize, m) else Disc(cover, discSize, m)
            }
            val words: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    OrbitLabel("Top result · $kind", fonts, ink.copy(alpha = 0.75f))
                    Text(title, style = fonts.heroTitle, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body.copy(fontSize = 13.5.sp), color = ink.copy(alpha = 0.85f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
            disc(Modifier.padding(start = 22.dp, top = 22.dp))
            if (beside) words(Modifier.padding(start = 150.dp, top = 30.dp, end = if (song != null) 64.dp else 22.dp))
            else words(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 22.dp, end = 100.dp))
            if (song != null) {
                OrbitRoundButton(CosmoIcons.Plus, stringResource(Res.string.playlist_action), ink, onClick = { actions.onTopResultPlaylist(song) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// the wide page

/** The first band of the wide page: the top result's card and, beside it, the songs. */
@Composable
private fun WideTopAndSongs(top: SearchResult, songRows: List<SearchResult>, tracks: List<Song>, actions: SearchRowActions, page: SearchPage) {
    Row(Modifier.fillMaxWidth().padding(horizontal = page.side).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TopResultCard(top, actions, page, Modifier.weight(1f).height(210.dp))
        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            songRows.forEachIndexed { index, row ->
                val song = (row as? SearchResult.Track)?.song ?: return@forEachIndexed
                key("track_${song.videoId}_$index") { SearchSongRow(song, tracks, actions, page) }
            }
        }
    }
}

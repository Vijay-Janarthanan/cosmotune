package com.cosmotune.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.blocks.CosmoCard
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.blocks.CosmoSongRow
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.BrowseItem
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.ui.components.rememberShelfFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.albums
import com.cosmotune.music.sharedui.resources.artists
import com.cosmotune.music.sharedui.resources.cosmo_search_no_results
import com.cosmotune.music.sharedui.resources.cosmo_search_no_results_hint
import com.cosmotune.music.sharedui.resources.cosmo_search_top_result
import com.cosmotune.music.sharedui.resources.cosmo_songs
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.playlist_action
import com.cosmotune.music.sharedui.resources.playlists
import com.cosmotune.music.sharedui.resources.search_library_empty
import com.cosmotune.music.sharedui.resources.search_library_no_results
import com.cosmotune.music.sharedui.resources.search_addons_empty
import com.cosmotune.music.sharedui.resources.search_addons_no_results
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.music.ui.components.songListSkeleton
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/*
 * The result pages of Search (`searchResults`, `screens-more-a.js:81-87`; the desktop's `searchDesktop`, `:106-112`): the top
 * result, the sections of songs, artists, albums and playlists, the Library source's songs, and the wide page of two columns.
 */

/** From this width on, the results of "All" stand in two columns, with the artists and the other releases under them. */
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
 * What the old screen's `searchSections` decided, kept as it was: a filter other than "All" shows its rows as one section with no
 * heading; "All" splits them into songs, artists, albums, playlists and the rest, and leaves out a section with nothing in it.
 * The promoted top track is not in the songs: it has its own tile.
 */
internal fun searchSections(rows: List<SearchResult>, filter: SearchFilter): List<SearchSection> {
    if (filter != SearchFilter.ALL) return listOf(SearchSection(SearchSectionKind.PLAIN, rows))
    val browse = rows.filterIsInstance<SearchResult.Browse>()
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
    wide: Boolean,
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
    val topResult = rows.filterIsInstance<SearchResult.TopTrack>().firstOrNull()
    val showTop = filter == SearchFilter.ALL && topResult != null
    val sections = searchSections(rows, filter)
    if (!showTop && sections.none { section -> section.rows.any { it !is SearchResult.TopTrack } }) {
        item(key = "search:no-results") {
            MessageState(
                stringResource(Res.string.cosmo_search_no_results, "“${query.trim()}”") + "\n" +
                    stringResource(Res.string.cosmo_search_no_results_hint),
            )
        }
        return
    }
    // The wide page is for "All"; a filter is one list, however wide the window.
    val twoColumns = wide && filter == SearchFilter.ALL
    val songs = sections.firstOrNull { it.kind == SearchSectionKind.SONGS }
    if (showTop && twoColumns) {
        item(key = "search:top-result:${topResult.song.videoId}") {
            WideTopAndSongs(topResult.song, songs?.rows.orEmpty(), tracks, actions)
        }
    } else if (showTop) {
        item(key = "search:top-title") { CosmoOverline(stringResource(Res.string.cosmo_search_top_result)) }
        item(key = "search:top-result:${topResult.song.videoId}") { TopResultTile(topResult.song, actions) }
    }
    for (section in sections) {
        when {
            twoColumns && showTop && section.kind == SearchSectionKind.SONGS -> Unit
            twoColumns && section.kind == SearchSectionKind.ARTISTS -> artistCircles(section, actions)
            twoColumns && (section.kind == SearchSectionKind.ALBUMS || section.kind == SearchSectionKind.PLAYLISTS) -> releaseRail(section, actions)
            else -> listSection(section, tracks, actions)
        }
    }
    if (loadingMore) {
        songListSkeleton(count = 3, keyPrefix = "skeleton:search:more", circular = filter == SearchFilter.ARTISTS)
    }
}

/** One section as a list of rows: its heading (the filtered page has none), then a song row or a release row for each. Keys as the old screen's. */
private fun LazyListScope.listSection(section: SearchSection, tracks: List<Song>, actions: SearchRowActions) {
    section.kind.title?.let { title ->
        item(key = "search-section:${section.kind}") { CosmoOverline(stringResource(title)) }
    }
    itemsIndexed(
        items = section.rows,
        key = { index, row ->
            when (row) {
                is SearchResult.TopTrack -> "top_${row.song.videoId}_$index"
                is SearchResult.Track -> "track_${row.song.videoId}_$index"
                is SearchResult.Browse -> "browse_${row.item.browseId}_$index"
            }
        },
    ) { _, row ->
        when (row) {
            is SearchResult.TopTrack -> Unit
            is SearchResult.Track -> SearchSongRow(row.song, tracks, actions)
            is SearchResult.Browse -> BrowseListRow(
                item = row.item,
                onClick = { actions.onBrowseClick(row.item) },
                // Holding an artist does nothing (the page has no menu for one); an album or a playlist opens its menu.
                onLongPress = row.item.takeIf { it.type != BrowseType.ARTIST }?.let { item -> actions.onBrowseLongPress?.let { held -> { held(item) } } },
            )
        }
    }
}

/** A song of the results: a tap plays it with the page's songs as the list, a hold opens its menu, a swipe queues it. */
@Composable
private fun SearchSongRow(song: Song, tracks: List<Song>, actions: SearchRowActions) {
    // The row that was played lights, not every namesake among the page's songs: see [playingRowMatcher].
    val isCurrentRow = remember(tracks, actions.currentSong) { playingRowMatcher(tracks, actions.currentSong) }
    val current = isCurrentRow(song)
    CosmoSongRow(
        song = song,
        onClick = { actions.onSongClick(tracks, tracks.indexOf(song).coerceAtLeast(0)) },
        onLongPress = { actions.onSongLongPress(song) },
        onSwipeToQueue = { actions.onSongSwipe(song) },
        isCurrent = current,
        isPlaying = actions.isPlaying && current,
    )
}

/** An album, an artist, a playlist or the rest, as a list row: the cover (a circle for an artist), the name, and what it is. */
@Composable
internal fun BrowseListRow(item: BrowseItem, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    CosmoListRow(
        title = item.title,
        onClick = onClick,
        subtitle = item.subtitle.ifBlank { typeLabel(item.type.name) },
        leading = CosmoRowLeading.Cover(item.thumbnailUrl, circle = item.type == BrowseType.ARTIST),
        onLongPress = onLongPress,
    )
}

// ---------------------------------------------------------------------------------------------------------------------------
// the Library source

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
                CosmoSongRow(
                    song = song,
                    onClick = { onSongClick(tracks, index) },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    isCurrent = current,
                    isPlaying = isPlaying && current,
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
 * The promoted hit (`searchResults`): a tile 16 dp in from the sides with the cover (96 dp, radius 12), the title (two lines) over
 * the artist, the play button, and under it a tonal button that puts the song in a playlist. A tap plays, a hold (or a right click)
 * opens the song's menu.
 */
@Composable
private fun TopResultTile(song: Song, actions: SearchRowActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val shape = remember { SuperellipseShape(24.dp) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin)
            // The press highlight has to stop at the corners, and so does the picture taken when the tile is held.
            .clip(shape)
            .longPressMenuClickable(onClick = { actions.onTopResultPlay(song) }, onLongClick = { actions.onSongLongPress(song) })
            .contextClick { actions.onSongLongPress(song) }
            .cosmoTile(shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(url = song.thumbnailUrl, size = 96.dp, radius = 12.dp, shadow = true)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                text = song.title,
                style = type.forText(type.title(TypeScale.TITLE_L), song.title),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = type.forText(artistStyle(type.textFamily), song.artist),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayMorphButton(playing = false, loading = false, onClick = { actions.onTopResultPlay(song) }, size = 56.dp)
            CosmoIconButton(
                icon = CosmoIcons.Plus,
                contentDescription = stringResource(Res.string.playlist_action),
                onClick = { actions.onTopResultPlaylist(song) },
                style = CosmoIconButtonStyle.TONAL,
                size = 40.dp,
            )
        }
    }
}

/** The artist line under a top result's title: 13 sp. */
private fun artistStyle(family: androidx.compose.ui.text.font.FontFamily): TextStyle =
    TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 13.sp * 1.35f)

// ---------------------------------------------------------------------------------------------------------------------------
// the wide page

/**
 * The first band of the wide page (`searchDesktop`): two columns, 0.9 to 1.4, 28 dp between the tile and the songs. On the left
 * the top result as a tile 250 dp tall (the cover 120, the title in display-m, the play button 18 dp from the bottom right corner
 * and the playlist button at the top right corner); on the right the songs.
 */
@Composable
private fun WideTopAndSongs(top: Song, songRows: List<SearchResult>, tracks: List<Song>, actions: SearchRowActions) {
    // The wide page heads its sections with the section header (the shelves under it do), not the overline of the phone's page.
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.9f)) {
            CosmoSectionHeader(stringResource(Res.string.cosmo_search_top_result))
            WideTopResultTile(top, actions, Modifier.padding(start = CosmoSpacing.margin))
        }
        Column(Modifier.weight(1.4f)) {
            if (songRows.isNotEmpty()) CosmoSectionHeader(stringResource(Res.string.cosmo_songs))
            songRows.forEachIndexed { index, row ->
                val song = (row as? SearchResult.Track)?.song ?: return@forEachIndexed
                key("track_${song.videoId}_$index") { SearchSongRow(song, tracks, actions) }
            }
        }
    }
}

@Composable
private fun WideTopResultTile(song: Song, actions: SearchRowActions, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val shape = remember { SuperellipseShape(24.dp) }
    Box(
        modifier
            .fillMaxWidth()
            .height(WIDE_TILE_DP.dp)
            .clip(shape)
            .longPressMenuClickable(onClick = { actions.onTopResultPlay(song) }, onLongClick = { actions.onSongLongPress(song) })
            .contextClick { actions.onSongLongPress(song) }
            .cosmoTile(shape),
    ) {
        // The cover at the top and the words at the foot, so the artist line is never the part that is cut when the title is tall.
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            CoverImage(url = song.thumbnailUrl, size = 120.dp, radius = 12.dp, shadow = true)
            // One line, and not under the play button: the title is as long as the room to the button allows.
            Column(Modifier.padding(end = 74.dp)) {
                Text(
                    text = song.title,
                    style = type.forText(type.display(TypeScale.DISPLAY_M), song.title),
                    color = Color(palette.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = type.forText(artistStyle(type.textFamily), song.artist),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        CosmoIconButton(
            icon = CosmoIcons.Plus,
            contentDescription = stringResource(Res.string.playlist_action),
            onClick = { actions.onTopResultPlaylist(song) },
            style = CosmoIconButtonStyle.TONAL,
            size = 40.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        )
        PlayMorphButton(
            playing = false,
            loading = false,
            onClick = { actions.onTopResultPlay(song) },
            size = 56.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        )
    }
}

private const val WIDE_TILE_DP = 250

/** The artists of the wide page as a row of 96 dp circles, 20 dp apart (the board's `M.circle`). */
private fun LazyListScope.artistCircles(section: SearchSection, actions: SearchRowActions) {
    val artists = section.rows.filterIsInstance<SearchResult.Browse>().map { it.item }
    item(key = "search-section:${section.kind}") {
        Column(Modifier.padding(top = 22.dp)) {
            CosmoSectionHeader(stringResource(Res.string.artists))
            val circles = rememberLazyListState()
            LazyRow(
                state = circles,
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                flingBehavior = rememberShelfFlingBehavior(circles),
                verticalAlignment = Alignment.Top,
            ) {
                itemsIndexed(artists, key = { index, artist -> "artist_${artist.browseId}_$index" }) { _, artist ->
                    CosmoCard(
                        title = artist.title,
                        imageUrl = artist.thumbnailUrl,
                        onClick = { actions.onBrowseClick(artist) },
                        subtitle = artist.subtitle.ifBlank { typeLabel(artist.type.name) },
                        width = 96.dp,
                        circle = true,
                    )
                }
            }
        }
    }
}

/** The albums, or the playlists, of the wide page as a shelf of 150 dp cards. */
private fun LazyListScope.releaseRail(section: SearchSection, actions: SearchRowActions) {
    val releases = section.rows.filterIsInstance<SearchResult.Browse>().map { it.item }
    val byId = releases.associateBy { it.browseId }
    val cards = releases.map { release ->
        ShelfItem(title = release.title, subtitle = release.subtitle, thumbnailUrl = release.thumbnailUrl, videoId = null, browseId = release.browseId)
    }
    item(key = "search-section:${section.kind}") {
        CosmoShelf(
            title = stringResource(requireNotNull(section.kind.title)),
            items = cards,
            onItemClick = { card -> byId[card.browseId]?.let(actions.onBrowseClick) },
            onItemLongPress = actions.onBrowseLongPress?.let { held -> { card -> byId[card.browseId]?.let(held) } },
            cardWidth = 150.dp,
            leadFirst = false,
        )
    }
}

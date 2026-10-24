package com.cosmotune.ui.pages

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ReleaseMeta
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.blocks.CosmoSongRow
import com.cosmotune.ui.blocks.rowTitleColor
import com.cosmotune.ui.blocks.rowTitleStyle
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_add
import com.cosmotune.music.sharedui.resources.cosmo_duration_h_min
import com.cosmotune.music.sharedui.resources.cosmo_duration_min
import com.cosmotune.music.sharedui.resources.cosmo_save
import com.cosmotune.music.sharedui.resources.cosmo_saved
import com.cosmotune.music.sharedui.resources.cosmo_search_in_list
import com.cosmotune.music.sharedui.resources.cosmo_search_no_results
import com.cosmotune.music.sharedui.resources.cosmo_shuffle
import com.cosmotune.music.sharedui.resources.cosmo_song_count
import com.cosmotune.music.sharedui.resources.cosmo_state_empty
import com.cosmotune.music.sharedui.resources.cosmo_suggested
import com.cosmotune.music.sharedui.resources.cosmo_type_album
import com.cosmotune.music.sharedui.resources.cosmo_type_ep
import com.cosmotune.music.sharedui.resources.cosmo_type_playlist
import com.cosmotune.music.sharedui.resources.cosmo_type_single
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.play
import com.cosmotune.music.ui.components.ExplicitSongTitle
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.player.PlayerBackHandler
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * An album, a single, an EP or a playlist in the new look (`V2.album`, `v2/v2.js:96-103`, `screens-v2.css:103-115`): one list over
 * the ambient ground (no background of its own; the page's top fade is the ground, not the cover's colours). From the top: the cover,
 * 236 dp with the album hero's deeper shadow; the title with what the page is above it, the artist (a tap opens them) and the meta
 * line under it; Play, Shuffle, Save, find-in-list and More; the tracks, numbered on an album and with covers on a playlist; what
 * YouTube suggests to round a playlist out; and About.
 *
 * [songs] is the list to show (the caller has sorted it); [trackNumbers] gives each song its place on the release as the release
 * has it, which a sort or the filter must not relabel. The title, the cover and the subtitle are what the row that was tapped
 * already knew, so the page draws them at once and a skeleton (or the message of a failure) stands where the tracks will be.
 * [onToggleLibrary] and [page]'s `library` together decide whether Save is offered; [onMore] whether the overflow is.
 */
@Composable
fun CosmoReleasePage(
    page: DetailPage,
    songs: List<Song>,
    trackNumbers: Map<String, Int>,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onMore: ((List<Song>) -> Unit)?,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    onToggleLibrary: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Narrowing the running order in place. Off by default and reset with the page: a filter left on an album that was closed and
    // reopened would be a page that appears to have lost most of its tracks (`DetailScreen.kt`'s own reasoning).
    var searching by rememberSaveable(page.browseId) { mutableStateOf(false) }
    var query by rememberSaveable(page.browseId) { mutableStateOf("") }
    // Whether the field still owes the keyboard an appearance: held here because the field is a row of a lazy list, disposed when it
    // scrolls out of sight, and a field that asked for focus every time it was composed would throw the keyboard back up each time.
    var focusSearch by remember(page.browseId) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val closeSearch: () -> Unit = {
        searching = false
        query = ""
    }
    // Back closes the search first.
    PlayerBackHandler(enabled = searching) { closeSearch() }
    // Opening the search carries the page up to the field, so it lands just under the bar with the tracks beneath it.
    LaunchedEffect(searching) {
        if (searching) listState.animateScrollToItem(PageMetrics.SEARCH_ITEM_INDEX)
    }

    // A numbered row is an album's (and a single's, an EP's): every row on it carries the cover that is already the largest thing on the page.
    val numbered = page.type == BrowseType.ALBUM
    val matches = remember(songs, query) { songs.matchingQuery(query) }
    // What a tap plays: the songs standing in the list now, from the tapped one.
    val queue = remember(matches) { matches.map { it.value } }
    val suggested = remember(page.suggestedSongs, query) { page.suggestedSongs.matchingQuery(query).map { it.value } }
    // A page that is itself a reading of this device (Downloads, a downloaded playlist) has every row downloaded: no mark then.
    val downloadedTint: Color? = if (page.browseId.startsWith("local:")) null else Color.Unspecified

    val parts = remember(page.subtitle, page.type, songs) { ReleaseMeta.parts(page.subtitle, page.type, songs.map { it.durationText }) }
    val typeWord = when (parts.kind) {
        ReleaseMeta.Kind.ALBUM -> stringResource(Res.string.cosmo_type_album)
        ReleaseMeta.Kind.SINGLE -> stringResource(Res.string.cosmo_type_single)
        ReleaseMeta.Kind.EP -> stringResource(Res.string.cosmo_type_ep)
        ReleaseMeta.Kind.PLAYLIST -> stringResource(Res.string.cosmo_type_playlist)
        null -> null
    }
    val songsText = if (parts.songCount > 0) pluralStringResource(Res.plurals.cosmo_song_count, parts.songCount, parts.songCount) else null
    val durationText = parts.playtime?.let { playtime ->
        if (playtime.hours > 0) stringResource(Res.string.cosmo_duration_h_min, playtime.hours, playtime.minutes)
        else stringResource(Res.string.cosmo_duration_min, playtime.minutes)
    }
    // The overline already says what the page is, so the line leaves the type out (the board: "2026 · 10 songs · 38 min").
    val metaLine = ReleaseMeta.line(type = null, year = parts.year, songs = songsText, duration = durationText)

    // Every row of a release carries the same credit, so the first one speaks for the page (the old header's rule); a playlist's credit
    // is its owner, who is that song's artist only when the names say so.
    val first = songs.firstOrNull()
    val firstArtist = first?.artist.orEmpty()
    val credit = parts.credit.ifBlank { firstArtist }
    val artistId = first?.artistId?.takeIf { page.type != BrowseType.PLAYLIST || credit.equals(firstArtist, ignoreCase = true) }
    val openArtist: (() -> Unit)? = artistId?.let { id -> { onArtistClick(id, firstArtist) } }

    val playLabel = stringResource(Res.string.play)
    val shuffleLabel = stringResource(Res.string.cosmo_shuffle)
    val findLabel = stringResource(Res.string.cosmo_search_in_list)
    val moreLabel = stringResource(Res.string.more)
    val library = page.library?.takeIf { onToggleLibrary != null }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "cover", contentType = "cover") {
            Box(Modifier.fillMaxWidth().padding(top = PageMetrics.COVER_TOP_DP.dp), contentAlignment = Alignment.Center) {
                CoverImage(
                    url = page.thumbnailUrl,
                    size = PageMetrics.COVER_DP.dp,
                    radius = CosmoRadii.coverNowPlaying,
                    modifier = Modifier.heroShadow(CosmoRadii.coverNowPlaying),
                )
            }
        }

        item(key = "meta", contentType = "meta") {
            Column(Modifier.fillMaxWidth()) {
                // The board's meta block stands 20 dp under the cover; the page header brings 14 of them.
                Spacer(Modifier.height(6.dp))
                CosmoPageHeader(title = page.title, overline = typeWord)
                if (credit.isNotBlank()) ArtistCredit(name = credit, onClick = openArtist)
                if (metaLine.isNotBlank()) ReleaseMetaLine(metaLine)
            }
        }

        if (songs.isNotEmpty()) {
            item(key = "actions", contentType = "actions") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Four icon buttons and Play do not fit between the margins of a 360 dp phone at the board's gap of 8.
                    val icons = 2 + (if (library != null) 1 else 0) + (if (onMore != null) 1 else 0)
                    val gap = if (icons >= 4 && maxWidth < PageMetrics.ACTIONS_NARROW_DP.dp) PageMetrics.ACTIONS_GAP_NARROW_DP else PageMetrics.ACTIONS_GAP_DP
                    Row(
                        Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 16.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CosmoButton(
                            text = playLabel,
                            onClick = { onSongClick(songs, 0) },
                            kind = CosmoButtonKind.PRIMARY,
                            size = CosmoButtonSize.LARGE,
                            icon = CosmoIcons.Play,
                        )
                        CosmoIconButton(
                            icon = CosmoIcons.Shuffle,
                            contentDescription = shuffleLabel,
                            onClick = { onShuffle(songs) },
                            style = CosmoIconButtonStyle.TONAL,
                            size = 48.dp,
                        )
                        if (library != null) {
                            CosmoIconButton(
                                icon = if (library.saved) CosmoIcons.HeartFill else CosmoIcons.Heart,
                                contentDescription = stringResource(if (library.saved) Res.string.cosmo_saved else Res.string.cosmo_save),
                                onClick = { onToggleLibrary?.invoke() },
                                style = CosmoIconButtonStyle.TONAL,
                                on = library.saved,
                                accentWhenOn = true,
                            )
                        }
                        CosmoIconButton(
                            icon = CosmoIcons.Search,
                            contentDescription = findLabel,
                            onClick = {
                                if (searching) {
                                    closeSearch()
                                } else {
                                    searching = true
                                    focusSearch = true
                                }
                            },
                            style = CosmoIconButtonStyle.TONAL,
                            on = searching,
                        )
                        Spacer(Modifier.weight(1f))
                        if (onMore != null) {
                            CosmoIconButton(
                                icon = CosmoIcons.MoreVertical,
                                contentDescription = moreLabel,
                                onClick = { onMore(songs) },
                                style = CosmoIconButtonStyle.PLAIN,
                            )
                        }
                    }
                }
            }
        }

        if (searching) {
            item(key = "search", contentType = "search") {
                LaunchedEffect(focusSearch) {
                    if (focusSearch) {
                        searchFocus.requestFocus()
                        focusSearch = false
                    }
                }
                CosmoSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSubmit = { },
                    placeholder = findLabel,
                    modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 8.dp),
                    focusRequester = searchFocus,
                )
            }
        }

        when (val state = page.songs) {
            is UiState.Loading -> pageSkeleton("skeleton:release", rowDp = PageMetrics.TRACK_ROW_DP)
            is UiState.Error -> item(key = "error") { MessageState(state.message) }
            is UiState.Success -> {
                if (songs.isEmpty() && page.suggestedSongs.isEmpty()) {
                    item(key = "empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
                }
                if (matches.isEmpty() && songs.isNotEmpty()) {
                    item(key = "no-matches") { MessageState(stringResource(Res.string.cosmo_search_no_results, query)) }
                }
                // The row that was played lights, not every namesake: see [playingRowMatcher].
                val isCurrentRow = playingRowMatcher(matches.map { it.value }, currentSong)
                itemsIndexed(
                    items = matches,
                    key = { _, entry -> "${entry.index}_${entry.value.videoId}" },
                    contentType = { _, _ -> "track" },
                ) { position, entry ->
                    val song = entry.value
                    val isCurrent = isCurrentRow(song)
                    CosmoSongRow(
                        song = if (numbered) song else song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl),
                        onClick = { onSongClick(queue, position) },
                        onLongPress = { onSongLongPress(song) },
                        onSwipeToQueue = { onSongSwipe(song) },
                        // The track's place on the release, not its place in what the filter or a sort left standing.
                        trackNumber = trackNumbers[song.videoId].takeIf { numbered },
                        downloadedTint = downloadedTint,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        height = PageMetrics.TRACK_ROW_DP.dp,
                        trailingText = song.durationText,
                    )
                }
            }
        }

        // What YouTube offers to round a playlist out: never folded into the list above (`DetailPage.suggestedSongs`).
        if (suggested.isNotEmpty()) {
            item(key = "suggested-heading", contentType = "heading") { CosmoOverline(stringResource(Res.string.cosmo_suggested)) }
            val isSuggestedCurrent = playingRowMatcher(suggested, currentSong)
            itemsIndexed(
                items = suggested,
                key = { _, song -> "suggested-${song.videoId}" },
                contentType = { _, _ -> "suggested" },
            ) { index, song ->
                val isCurrent = isSuggestedCurrent(song)
                SuggestedRow(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    onClick = { onSongClick(suggested, index) },
                    onLongPress = { onSongLongPress(song) },
                    onAdd = { onAddSuggested(song) },
                )
            }
        }

        // YouTube's own note: an album only, never a playlist (`DetailPage.description`).
        val description = page.description
        if (!description.isNullOrBlank() && page.type == BrowseType.ALBUM) {
            item(key = "about", contentType = "about") {
                AboutTile(text = description, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

/**
 * A row under "Suggested": a song YouTube offers to round the playlist out but that was never added. Adding it is the point of the
 * row, so it takes the end of the row, a tonal plus; holding it opens the song's menu.
 */
@Composable
private fun SuggestedRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onAdd: () -> Unit,
) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    val addLabel = stringResource(Res.string.cosmo_add)
    CosmoListRow(
        title = song.title,
        onClick = onClick,
        subtitle = song.artist,
        leading = CosmoRowLeading.Cover(song.thumbnailUrl),
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        onLongPress = onLongPress,
        titleContent = {
            ExplicitSongTitle(song = song, style = type.forText(type.rowTitleStyle(), song.title), color = rowTitleColor(colors, isCurrent))
        },
        trailing = {
            CosmoIconButton(
                icon = CosmoIcons.Plus,
                contentDescription = addLabel,
                onClick = onAdd,
                style = CosmoIconButtonStyle.TONAL,
                size = 40.dp,
            )
        },
    )
}

package com.cosmotune.ui.pages

import com.cosmotune.ui.nowplaying.catalogueQualityBadge
import com.cosmotune.ui.nowplaying.QualityPill
import com.cosmotune.ui.kit.KitPlayCircle
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitCoverGradient
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitActionRow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ReleaseMeta
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.blocks.CosmoSongRow
import com.cosmotune.ui.blocks.MusicListControls
import com.cosmotune.ui.blocks.rowTitleColor
import com.cosmotune.ui.blocks.rowTitleStyle
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
import com.cosmotune.music.data.model.MusicListOrder
import com.cosmotune.music.data.model.MusicSongList
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_add
import com.cosmotune.music.sharedui.resources.cosmo_duration_h_min
import com.cosmotune.music.sharedui.resources.cosmo_duration_min
import com.cosmotune.music.sharedui.resources.cosmo_save
import com.cosmotune.music.sharedui.resources.cosmo_saved
import com.cosmotune.music.sharedui.resources.cosmo_search_in_list
import com.cosmotune.music.sharedui.resources.cosmo_shuffle
import com.cosmotune.music.sharedui.resources.cosmo_song_count
import com.cosmotune.music.sharedui.resources.cosmo_state_empty
import com.cosmotune.music.sharedui.resources.cosmo_suggested
import com.cosmotune.music.sharedui.resources.cosmo_type_album
import com.cosmotune.music.sharedui.resources.cosmo_type_ep
import com.cosmotune.music.sharedui.resources.cosmo_type_playlist
import com.cosmotune.music.sharedui.resources.cosmo_type_single
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.music_list_no_results
import com.cosmotune.music.sharedui.resources.play
import com.cosmotune.music.ui.components.ExplicitSongTitle
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.player.PlayerBackHandler
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * An album, a single, an EP or a playlist (DESIGN_SYSTEM.md 6.1): one list on the page's ground, with the cover's colour behind the
 * header only. From the top: the cover, 220 dp; the title with what the page is above it, the artist (a tap opens them), the meta line
 * and the quality the catalogue reports; one row of Shuffle, Save, find-in-list and More with the play circle at its end; the tracks,
 * numbered on an album and with covers on a playlist, each with its quality pill; what YouTube suggests to round a playlist out; and
 * About. The header runs up under the bar (only the foot of [contentPadding] is the list's), so the colour reaches the top of the window.
 *
 * [songs] is the canonical source order; local sorting/filtering retains each occurrence's original track number.
 * [trackNumbers] remains for caller compatibility; occurrence indices, rather than video ids, distinguish repeated album tracks.
 * The title, the cover and the subtitle are what the row that was tapped
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
    initialOrder: MusicListOrder = MusicListOrder.DEFAULT,
    downloadedIds: Set<String> = emptySet(),
) {
    // Narrowing the running order in place. Off by default and reset with the page: a filter left on an album that was closed and
    // reopened would be a page that appears to have lost most of its tracks (`DetailScreen.kt`'s own reasoning).
    var searching by rememberSaveable(page.browseId) { mutableStateOf(false) }
    var query by rememberSaveable(page.browseId) { mutableStateOf("") }
    var orderName by rememberSaveable(page.browseId) { mutableStateOf(initialOrder.name) }
    LaunchedEffect(initialOrder) { orderName = initialOrder.name }
    val order = MusicListOrder.entries.firstOrNull { it.name == orderName } ?: MusicListOrder.DEFAULT
    var downloadedOnly by rememberSaveable(page.browseId) { mutableStateOf(false) }
    var selecting by rememberSaveable(page.browseId) { mutableStateOf(false) }
    var selection by rememberSaveable(page.browseId) { mutableStateOf(emptyList<String>()) }
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
    val repeatedIds = remember(songs) { songs.groupingBy { it.videoId }.eachCount().filterValues { it > 1 }.keys }
    val matches = remember(songs, query, order, downloadedOnly, downloadedIds) {
        MusicSongList.rows(songs, query, order, downloadedOnly, downloadedIds)
    }
    // What a tap plays: the songs standing in the list now, from the tapped one.
    val queue = remember(matches) { matches.map { it.value } }
    val selectedSongs = remember(songs, selection, order) {
        val keys = selection.toSet()
        MusicSongList.rows(songs, order = order).filter { MusicSongList.occurrence(it) in keys }.map { it.value }
    }
    fun toggle(entry: IndexedValue<Song>) {
        if (entry.value.isMatchPending || entry.value.isMatchMissing) return
        val key = MusicSongList.occurrence(entry)
        selection = if (key in selection) selection - key else selection + key
    }
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

    val quality = remember(songs) { songs.firstOrNull()?.catalogueQualityBadge() }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
        item(key = "cover", contentType = "cover") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                KitCoverGradient(page.thumbnailUrl, Modifier.fillMaxWidth().height(PageMetrics.HEADER_GRADIENT_DP.dp))
                val wide = maxWidth.value >= PageMetrics.WIDE_PAGE_DP
                Box(
                    Modifier.fillMaxWidth().padding(top = contentPadding.calculateTopPadding() + PageMetrics.COVER_TOP_DP.dp, start = if (wide) CosmoSpacing.margin else 0.dp),
                    contentAlignment = if (wide) Alignment.TopStart else Alignment.Center,
                ) {
                    CoverImage(
                        url = page.thumbnailUrl,
                        size = (if (wide) PageMetrics.COVER_WIDE_DP else PageMetrics.COVER_DP).dp,
                        radius = CosmoRadii.rMd,
                        shadow = false,
                    )
                }
            }
        }

        item(key = "meta", contentType = "meta") {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ReleaseHeading(title = page.title, overline = typeWord)
                if (credit.isNotBlank()) ArtistCredit(name = credit, onClick = openArtist)
                if (metaLine.isNotBlank()) ReleaseMetaLine(metaLine)
                if (quality != null) QualityPill(quality, modifier = Modifier.padding(start = CosmoSpacing.margin, top = 8.dp))
            }
        }

        if (songs.isNotEmpty()) {
            item(key = "actions", contentType = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(end = CosmoSpacing.margin, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KitActionRow(Modifier.weight(1f), contentPadding = PaddingValues(start = CosmoSpacing.margin, end = 8.dp)) {
                        KitButton(
                            text = shuffleLabel,
                            onClick = { if (queue.isNotEmpty()) onShuffle(queue) },
                            kind = KitButtonKind.TONAL,
                            icon = CosmoIcons.Shuffle,
                            enabled = queue.isNotEmpty(),
                            dense = true,
                        )
                        if (library != null) {
                            KitButton(
                                text = stringResource(if (library.saved) Res.string.cosmo_saved else Res.string.cosmo_save),
                                onClick = { onToggleLibrary?.invoke() },
                                kind = KitButtonKind.TONAL,
                                icon = if (library.saved) CosmoIcons.HeartFill else CosmoIcons.Heart,
                                dense = true,
                            )
                        }
                        KitIconButton(
                            icon = CosmoIcons.Search,
                            label = findLabel,
                            onClick = {
                                if (searching) {
                                    closeSearch()
                                } else {
                                    searching = true
                                    focusSearch = true
                                }
                            },
                            on = searching,
                        )
                        if (onMore != null) {
                            KitIconButton(CosmoIcons.MoreVertical, moreLabel, { onMore(queue) }, Modifier.testTag("music:release:more"))
                        }
                    }
                    KitPlayCircle(
                        label = playLabel,
                        onClick = { if (queue.isNotEmpty()) onSongClick(queue, 0) },
                        modifier = Modifier.testTag("music:release:play"),
                        enabled = queue.isNotEmpty(),
                    )
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

        if (songs.isNotEmpty()) item(key = "list-controls", contentType = "controls") {
            MusicListControls(matches.size, songs.size, page.continuation != null, order,
                { orderName = it.name }, downloadedOnly, { downloadedOnly = it },
                onReset = if (query.isNotBlank() || downloadedOnly) { { query = ""; downloadedOnly = false } } else null,
                modifier = Modifier.padding(horizontal = CosmoSpacing.margin),
                selectionCount = selectedSongs.size, selecting = selecting,
                onSelecting = { selecting = !selecting; selection = emptyList() },
                onPlaySelection = { if (selectedSongs.isNotEmpty()) onSongClick(selectedSongs, 0) },
                onMoreSelection = onMore?.let { more -> { more(selectedSongs) } },
                onSelectVisible = { selection = matches.filterNot { it.value.isMatchPending || it.value.isMatchMissing }.map(MusicSongList::occurrence) },
                onClearSelection = { selection = emptyList() },
                hasAddedDates = songs.any { it.localDateAddedSeconds != null })
        }

        when (val state = page.songs) {
            is UiState.Loading -> pageSkeleton("skeleton:release", rowDp = PageMetrics.TRACK_ROW_DP)
            is UiState.Error -> item(key = "error") { MessageState(state.message) }
            is UiState.Success -> {
                if (songs.isEmpty() && page.suggestedSongs.isEmpty()) {
                    item(key = "empty") { MessageState(stringResource(Res.string.cosmo_state_empty)) }
                }
                if (matches.isEmpty() && songs.isNotEmpty()) {
                    item(key = "no-matches") { MessageState(stringResource(Res.string.music_list_no_results)) }
                }
                // The row that was played lights, not every namesake: see [playingRowMatcher].
                val isCurrentRow = playingRowMatcher(matches.map { it.value }, currentSong)
                itemsIndexed(
                    items = matches,
                    key = { _, entry -> MusicSongList.occurrence(entry) },
                    contentType = { _, _ -> "track" },
                ) { position, entry ->
                    val song = entry.value
                    val isCurrent = isCurrentRow(song)
                    CosmoSongRow(
                        song = if (numbered) song else song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl),
                        onClick = { if (selecting) toggle(entry) else onSongClick(queue, position) },
                        onLongPress = { if (selecting) toggle(entry) else onSongLongPress(song) },
                        onSwipeToQueue = if (selecting) null else { { onSongSwipe(song) } },
                        // The track's place on the release, not its place in what the filter or a sort left standing.
                        trackNumber = (if (song.videoId in repeatedIds) entry.index + 1 else trackNumbers[song.videoId] ?: (entry.index + 1)).takeIf { numbered },
                        selected = selecting && MusicSongList.occurrence(entry) in selection,
                        modifier = Modifier.testTag("music:release:row:${MusicSongList.occurrence(entry)}"),
                        downloadedTint = downloadedTint,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        height = PageMetrics.TRACK_ROW_DP.dp,
                        trailingText = song.durationText,
                        showSourceQuality = true,
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

/** A release's title: `headline`, with what the page is ("ALBUM") in an overline above it. */
@Composable
private fun ReleaseHeading(title: String, overline: String?) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin)) {
        if (!overline.isNullOrBlank()) {
            Text(overline.uppercase(), style = type.forText(type.overline, overline), color = Color(palette.textSecondary), maxLines = 1)
        }
        Text(
            title,
            style = type.forText(type.headline, title),
            color = Color(palette.textPrimary),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
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

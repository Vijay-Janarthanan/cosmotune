package com.cosmotune.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.cosmotune.design.ArtistPlay
import com.cosmotune.design.ReleaseMeta
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.MusicListOrder
import com.cosmotune.music.data.model.MusicSongList
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.sharedui.resources.Res
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
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.player.PlayerBackHandler
import com.cosmotune.music.ui.player.rememberRemoteArtworkUrl
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.blocks.MusicListControls
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.QualityPill
import com.cosmotune.ui.nowplaying.catalogueQualityBadge
import com.cosmotune.ui.orbit.OrbitAboutCard
import com.cosmotune.ui.orbit.OrbitCardButton
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.OrbitReleaseCard
import com.cosmotune.ui.orbit.OrbitPageButton
import com.cosmotune.ui.orbit.OrbitTrackRow
import com.cosmotune.ui.orbit.orbitPageSide
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * An album, a single, an EP or a playlist in Orbit (Canvas repo, design/cosmotune/pages-orbit, board section 1): one colour card in
 * the cover's own colour, the cover as a big disc (the album's animated cover looping silently in it when [albumCanvas] is given),
 * what the page is with its count and length in a mono line, the title, who made it (a tap opens them), the quality the catalogue
 * reports, and round buttons for Shuffle, Save, Download and More, with Play in the card's bite. Under the card: find-in-list and
 * the list's own controls (order, downloaded only, select), then the songs as Home's rows, what YouTube suggests to round a
 * playlist out, and About.
 *
 * [songs] is the canonical source order; local sorting and filtering keep each occurrence's identity (an album may repeat a track).
 * The title, the cover and the subtitle are what the row that was tapped already knew, so the card is drawn at once and a skeleton
 * (or the message of a failure) stands where the songs will be. [onToggleLibrary] and [page]'s `library` together decide whether
 * Save is offered; [onDownloadAll] whether Download is; [onMore] whether the overflow is. While one of the page's songs is the one
 * loaded, the bite is the player's play/pause ([onPlayPause]); otherwise it plays the list from the top.
 */
@Composable
fun CosmoReleasePage(
    page: DetailPage,
    songs: List<Song>,
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
    onDownloadAll: ((List<Song>) -> Unit)? = null,
    albumCanvas: CanvasArtwork? = null,
    onPlayPause: (() -> Unit)? = null,
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
    // Opening the search carries the page up to the field, so it lands just under the bar with the songs beneath it.
    LaunchedEffect(searching) {
        if (searching) listState.animateScrollToItem(PageMetrics.SEARCH_ITEM_INDEX)
    }

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
    // The card's mono line says what the page is, then the year, the count and the length ("ALBUM · 2026 · 12 SONGS · 48 MIN").
    val metaLine = ReleaseMeta.line(type = typeWord, year = parts.year, songs = songsText, duration = durationText)

    // Every row of a release carries the same credit, so the first one speaks for the page (the old header's rule); a playlist's credit
    // is its owner, who is that song's artist only when the names say so.
    val first = songs.firstOrNull()
    val firstArtist = first?.artist.orEmpty()
    val credit = parts.credit.ifBlank { firstArtist }
    val artistId = first?.artistId?.takeIf { page.type != BrowseType.PLAYLIST || credit.equals(firstArtist, ignoreCase = true) }
    val openArtist: (() -> Unit)? = artistId?.let { id -> { onArtistClick(id, firstArtist) } }

    val shuffleLabel = stringResource(Res.string.cosmo_shuffle)
    val findLabel = stringResource(Res.string.cosmo_search_in_list)
    val moreLabel = stringResource(Res.string.more)
    val library = page.library?.takeIf { onToggleLibrary != null }
    val quality = remember(songs) { songs.firstOrNull()?.catalogueQualityBadge() }
    // The bite is the player's play/pause while one of these songs is the one loaded, and plays the list from the top otherwise.
    val showsPause = ArtistPlay.showsPause(songs, currentSong, isPlaying)
    val play: () -> Unit = {
        when (ArtistPlay.tap(songs, currentSong, canToggle = onPlayPause != null)) {
            ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()
            ArtistPlay.Tap.PLAY_LIST -> if (queue.isNotEmpty()) onSongClick(queue, 0)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = orbitPageSide(maxWidth)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + PageMetrics.COVER_TOP_DP.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item(key = "card", contentType = "card") {
                OrbitReleaseCard(
                    cover = page.thumbnailUrl,
                    label = metaLine.ifBlank { typeWord.orEmpty() },
                    title = page.title,
                    credit = credit,
                    onCredit = openArtist,
                    playing = showsPause,
                    onPlay = play.takeIf { songs.isNotEmpty() },
                    motion = albumCanvas,
                    modifier = Modifier.padding(horizontal = side).fillMaxWidth().testTag("music:release:card"),
                    below = { if (quality != null) QualityPill(quality, modifier = Modifier.padding(top = 8.dp)) },
                ) { colors ->
                    if (songs.isNotEmpty()) {
                        OrbitCardButton(CosmoIcons.Shuffle, shuffleLabel, colors, { if (queue.isNotEmpty()) onShuffle(queue) }, Modifier.testTag("music:release:shuffle"))
                    }
                    if (library != null) {
                        OrbitCardButton(
                            if (library.saved) CosmoIcons.HeartFill else CosmoIcons.Heart,
                            stringResource(if (library.saved) Res.string.cosmo_saved else Res.string.cosmo_save),
                            colors, { onToggleLibrary?.invoke() }, on = library.saved,
                        )
                    }
                    if (onDownloadAll != null && songs.isNotEmpty()) {
                        OrbitCardButton(CosmoIcons.Download, "Download", colors, { onDownloadAll(queue) }, Modifier.testTag("music:release:download"))
                    }
                    if (onMore != null) {
                        OrbitCardButton(CosmoIcons.MoreVertical, moreLabel, colors, { onMore(queue) }, Modifier.testTag("music:release:more"))
                    }
                }
            }

            if (songs.isNotEmpty()) item(key = "list-controls", contentType = "controls") {
                Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 18.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrbitPageButton(
                        if (searching) CosmoIcons.Close else CosmoIcons.Search, findLabel,
                        onClick = {
                            if (searching) closeSearch() else {
                                searching = true
                                focusSearch = true
                            }
                        },
                        modifier = Modifier.testTag("music:release:find"), on = searching,
                    )
                    MusicListControls(matches.size, songs.size, page.continuation != null, order,
                        { orderName = it.name }, downloadedOnly, { downloadedOnly = it },
                        onReset = if (query.isNotBlank() || downloadedOnly) { { query = ""; downloadedOnly = false } } else null,
                        modifier = Modifier.weight(1f),
                        selectionCount = selectedSongs.size, selecting = selecting,
                        onSelecting = { selecting = !selecting; selection = emptyList() },
                        onPlaySelection = { if (selectedSongs.isNotEmpty()) onSongClick(selectedSongs, 0) },
                        onMoreSelection = onMore?.let { more -> { more(selectedSongs) } },
                        onSelectVisible = { selection = matches.filterNot { it.value.isMatchPending || it.value.isMatchMissing }.map(MusicSongList::occurrence) },
                        onClearSelection = { selection = emptyList() },
                        hasAddedDates = songs.any { it.localDateAddedSeconds != null })
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
                        modifier = Modifier.padding(horizontal = side, vertical = 8.dp),
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
                        // An album's rows have no covers of their own: the album's stands in, so every row is a disc.
                        val cover = rememberRemoteArtworkUrl(song) ?: page.thumbnailUrl
                        OrbitTrackRow(
                            song = song,
                            cover = cover,
                            onClick = { if (selecting) toggle(entry) else onSongClick(queue, position) },
                            onLongClick = { if (selecting) toggle(entry) else onSongLongPress(song) },
                            onSwipe = if (selecting) null else { { onSongSwipe(song) } },
                            selected = if (selecting) MusicSongList.occurrence(entry) in selection else null,
                            modifier = Modifier.padding(horizontal = side, vertical = 4.dp).fillMaxWidth().testTag("music:release:row:${MusicSongList.occurrence(entry)}"),
                            now = isCurrent,
                            playing = isCurrent && isPlaying,
                            downloadedTint = downloadedTint?.takeIf { song.videoId in downloadedIds },
                            showQuality = true,
                        )
                    }
                }
            }

            // What YouTube offers to round a playlist out: never folded into the list above (`DetailPage.suggestedSongs`).
            if (suggested.isNotEmpty()) {
                item(key = "suggested-heading", contentType = "heading") {
                    OrbitPageSection(stringResource(Res.string.cosmo_suggested), Modifier.padding(horizontal = side + 6.dp).padding(top = 30.dp, bottom = 10.dp))
                }
                val isSuggestedCurrent = playingRowMatcher(suggested, currentSong)
                itemsIndexed(
                    items = suggested,
                    key = { _, song -> "suggested-${song.videoId}" },
                    contentType = { _, _ -> "suggested" },
                ) { index, song ->
                    val isCurrent = isSuggestedCurrent(song)
                    OrbitTrackRow(
                        song = song,
                        onClick = { onSongClick(suggested, index) },
                        onLongClick = { onSongLongPress(song) },
                        now = isCurrent,
                        playing = isCurrent && isPlaying,
                        modifier = Modifier.padding(horizontal = side, vertical = 4.dp).fillMaxWidth(),
                        trailing = {
                            // Adding it is the point of the row: a round plus at its end.
                            OrbitPageButton(CosmoIcons.Plus, "Add", { onAddSuggested(song) })
                        },
                    )
                }
            }

            // YouTube's own note: an album only, never a playlist (`DetailPage.description`).
            val description = page.description
            if (!description.isNullOrBlank() && page.type == BrowseType.ALBUM) {
                item(key = "about", contentType = "about") {
                    Column(Modifier.padding(horizontal = side).padding(top = 30.dp)) {
                        OrbitPageSection("About", Modifier.padding(start = 6.dp, bottom = 12.dp))
                        OrbitAboutCard(description, emptyList(), Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

package com.cosmotune.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.sharedui.resources.music_list_reset
import com.cosmotune.music.sharedui.resources.music_list_select
import com.cosmotune.music.sharedui.resources.music_list_sort
import com.cosmotune.music.ui.player.PlayerSettings
import com.cosmotune.ui.blocks.orderLabel
import com.cosmotune.ui.orbit.OrbitBarAction
import com.cosmotune.ui.orbit.OrbitCollapsedHeader
import com.cosmotune.ui.orbit.OrbitHeaderButton
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSelectionBar
import com.cosmotune.ui.orbit.OrbitSelectionHead
import com.cosmotune.ui.orbit.OrbitSpace
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.music.sharedui.resources.cosmo_keep_downloaded
import com.cosmotune.music.sharedui.resources.cosmo_keep_downloaded_line
import com.cosmotune.ui.orbit.PublishCollapsedHeader
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitRowSkeletons
import com.cosmotune.ui.settings.rememberCosmoLookSettings
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
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.QualityPill
import com.cosmotune.ui.nowplaying.catalogueQualityBadge
import com.cosmotune.ui.orbit.OrbitAboutCard
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.OrbitReleaseCard
import com.cosmotune.ui.orbit.OrbitPageButton
import com.cosmotune.ui.orbit.OrbitTrackRow
import com.cosmotune.ui.orbit.TitleArtAlbum
import com.cosmotune.ui.orbit.orbitPageSide
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * An album, a single, an EP or a playlist in Orbit (the Music board's 4a to 4d and 9): the compact page header in the cover's own
 * colour (the cover as a 120 dp disc, the album's animated cover looping silently in it when [albumCanvas] is given), what the page is
 * with its year and count in a mono line, the title, who made it (a tap opens them), and round buttons for Shuffle, Save, Download
 * (its ring how much is saved) and More, with Play in the bite. Once it scrolls away the heading collapses into the top bar with
 * Shuffle and Play ([PublishCollapsedHeader]). A playlist has "Keep downloaded" under it. Then one slim line: the count and length,
 * find in the list and the order (with "Downloaded only" and "Select"), and the songs as flat rows: an album's with their track
 * numbers (peach bars on the playing one), a playlist's with their covers. What YouTube suggests rounds a playlist out; an album ends
 * with About. From 840 dp the page is two panes: the tall card (and About) on the left, the songs on the right.
 *
 * [songs] is the canonical source order; local sorting and filtering keep each occurrence's identity (an album may repeat a track).
 * The title, the cover and the subtitle are what the row that was tapped already knew, so the card is drawn at once and placeholders
 * in the rows' shape (or the message of a failure) stand where the songs will be. [onToggleLibrary] and [page]'s `library` together
 * decide whether Save is offered; [onDownloadAll] whether Download (and Keep downloaded) is; [onMore] whether the overflow is. While
 * one of the page's songs is the one loaded, the bite is the player's play/pause ([onPlayPause]); otherwise it plays the list from the top.
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
    // Back closes the search first, then select mode.
    PlayerBackHandler(enabled = searching) { closeSearch() }
    PlayerBackHandler(enabled = selecting && !searching) { selecting = false; selection = emptyList() }

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
    val local = page.browseId.startsWith("local:")
    val downloadedTint: Color? = if (local) null else Color.Unspecified

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
    // The card's mono line says what the page is, then the year and the count ("ALBUM · 2026 · 12 SONGS"); the length goes to the
    // line over the list, with the count again.
    val metaLine = ReleaseMeta.line(type = typeWord, year = parts.year, songs = songsText, duration = null)
    val listLine = listOfNotNull(songsText, durationText).joinToString(" · ")

    // Every row of a release carries the same credit, so the first one speaks for the page (the old header's rule); a playlist's credit
    // is its owner, who is that song's artist only when the names say so.
    val first = songs.firstOrNull()
    val firstArtist = first?.artist.orEmpty()
    val credit = parts.credit.ifBlank { firstArtist }
    val artistId = first?.artistId?.takeIf { page.type != BrowseType.PLAYLIST || credit.equals(firstArtist, ignoreCase = true) }
    val openArtist: (() -> Unit)? = artistId?.let { id -> { onArtistClick(id, firstArtist) } }
    val album = page.type != BrowseType.PLAYLIST

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
    val shuffle: (() -> Unit)? = if (songs.isNotEmpty()) ({ if (queue.isNotEmpty()) onShuffle(queue) }) else null
    PublishCollapsedHeader(OrbitCollapsedHeader(page.thumbnailUrl, page.title, credit.ifBlank { null }, showsPause, play.takeIf { songs.isNotEmpty() }, shuffle))

    // Keep downloaded (the Music board's 4d): a playlist's new songs come down on Wi-Fi with the downloads the app already has.
    val look = rememberCosmoLookSettings()
    val kept by look.keepDownloaded.collectAsState()
    val keep = page.browseId in kept
    val canKeep = !album && !local && onDownloadAll != null && songs.isNotEmpty()
    if (canKeep && keep) KeepDownloadedEffect(page.browseId, songs, downloadedIds, onDownloadAll)
    val savedCount = remember(songs, downloadedIds) { songs.count { it.videoId in downloadedIds } }

    val card: @Composable (Boolean, Modifier) -> Unit = { tall, m ->
        OrbitReleaseCard(
            cover = page.thumbnailUrl,
            label = metaLine.ifBlank { typeWord.orEmpty() },
            title = page.title,
            credit = credit,
            onCredit = openArtist,
            playing = showsPause,
            onPlay = play.takeIf { songs.isNotEmpty() },
            motion = albumCanvas,
            // Title art for a release; a playlist keeps its plain title (it has no album's look to wear).
            titleArt = if (page.type == BrowseType.PLAYLIST) null else TitleArtAlbum(page.title, page.thumbnailUrl, artist = credit),
            modifier = m.testTag("music:release:card"),
            tall = tall,
            below = { if (quality != null) QualityPill(quality, modifier = Modifier.padding(top = 8.dp)) },
        ) { colors ->
            if (shuffle != null) OrbitHeaderButton(CosmoIcons.Shuffle, shuffleLabel, colors, shuffle, Modifier.testTag("music:release:shuffle"))
            if (library != null) {
                OrbitHeaderButton(
                    if (library.saved) CosmoIcons.HeartFill else CosmoIcons.Heart,
                    stringResource(if (library.saved) Res.string.cosmo_saved else Res.string.cosmo_save),
                    colors, { onToggleLibrary?.invoke() }, on = library.saved,
                )
            }
            if (onDownloadAll != null && songs.isNotEmpty()) {
                OrbitHeaderButton(
                    if (savedCount == songs.size && !local) CosmoIcons.Downloaded else CosmoIcons.Download, "Download", colors, { onDownloadAll(queue) },
                    Modifier.testTag("music:release:download"),
                    progress = if (local || savedCount == 0) null else savedCount.toFloat() / songs.size,
                )
            }
            if (onMore != null) {
                OrbitHeaderButton(CosmoIcons.MoreVertical, moreLabel, colors, { onMore(queue) }, Modifier.testTag("music:release:more"))
            }
        }
    }

    val list: LazyListScope.(Dp) -> Unit = { side ->
        if (canKeep) item(key = "keep", contentType = "keep") {
            OrbitSwitchRow(
                CosmoIcons.Download, stringResource(Res.string.cosmo_keep_downloaded),
                stringResource(Res.string.cosmo_keep_downloaded_line, savedCount, songs.size),
                checked = keep, onChange = { look.setKeepDownloaded(page.browseId, it) },
                modifier = Modifier.padding(horizontal = side).padding(top = 8.dp).testTag("music:release:keep"),
            )
        }
        if (songs.isNotEmpty()) item(key = "list-line", contentType = "controls") {
            if (selecting) {
                OrbitSelectionHead(
                    selectedSongs.size, onClose = { selecting = false; selection = emptyList() },
                    onSelectAll = { selection = matches.filterNot { it.value.isMatchPending || it.value.isMatchMissing }.map(MusicSongList::occurrence) },
                    modifier = Modifier.padding(horizontal = side).padding(top = 8.dp),
                )
            } else {
                ListLine(
                    text = if (matches.size != songs.size) "${matches.size} of $listLine" else listLine,
                    searching = searching,
                    onFind = { if (searching) closeSearch() else { searching = true; focusSearch = true } },
                    findLabel = findLabel,
                    order = order,
                    onOrder = { orderName = it.name },
                    hasAddedDates = songs.any { it.localDateAddedSeconds != null },
                    downloadedOnly = downloadedOnly,
                    onDownloadedOnly = if (local) null else { { downloadedOnly = it } },
                    onSelect = { selecting = true; selection = emptyList() },
                    onReset = if (query.isNotBlank() || downloadedOnly) { { query = ""; downloadedOnly = false } } else null,
                    modifier = Modifier.padding(start = side + 4.dp, end = side - 6.dp, top = 8.dp),
                )
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
            is UiState.Loading -> orbitRowSkeletons("skeleton:release", side, rows = 8, numbered = album)
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
                    OrbitTrackRow(
                        song = song,
                        // A playlist's rows wear their own covers; an album's carry their track numbers instead of its cover again.
                        cover = rememberRemoteArtworkUrl(song) ?: page.thumbnailUrl,
                        number = if (album) entry.index + 1 else null,
                        // The length joins the second line: the row's one trailing button is More.
                        subtitle = listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" · "),
                        onClick = { if (selecting) toggle(entry) else onSongClick(queue, position) },
                        onLongClick = {
                            if (selecting) toggle(entry)
                            else if (onMore != null) { selecting = true; selection = listOf(MusicSongList.occurrence(entry)) }
                            else onSongLongPress(song)
                        },
                        onSwipe = if (selecting) null else { { onSongSwipe(song) } },
                        selected = if (selecting) MusicSongList.occurrence(entry) in selection else null,
                        modifier = Modifier.padding(horizontal = side).fillMaxWidth().testTag("music:release:row:${MusicSongList.occurrence(entry)}"),
                        now = isCurrent,
                        playing = isCurrent && isPlaying,
                        downloadedTint = downloadedTint?.takeIf { song.videoId in downloadedIds },
                        // The quality the catalogue offers only where it is not the page's own.
                        showQuality = song.catalogueQualityBadge() != quality,
                        trailing = if (selecting) ({ }) else ({ RowMore(onSongLongPress, song) }),
                    )
                }
            }
        }

        // What YouTube offers to round a playlist out: never folded into the list above (`DetailPage.suggestedSongs`).
        if (suggested.isNotEmpty()) {
            item(key = "suggested-heading", contentType = "heading") {
                OrbitPageSection(stringResource(Res.string.cosmo_suggested), Modifier.padding(horizontal = side + 4.dp).padding(top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent))
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
                    modifier = Modifier.padding(horizontal = side).fillMaxWidth(),
                    trailing = {
                        // Adding it is the point of the row: a round plus at its end.
                        OrbitPageButton(CosmoIcons.Plus, "Add", { onAddSuggested(song) })
                    },
                )
            }
        }
    }

    // YouTube's own note: an album only, never a playlist (`DetailPage.description`).
    val description = page.description?.takeIf { it.isNotBlank() && page.type == BrowseType.ALBUM }
    val about: @Composable (Modifier) -> Unit = { m ->
        if (description != null) Column(m) {
            OrbitPageSection("About", Modifier.padding(start = 4.dp, top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent))
            OrbitAboutCard(description, emptyList(), Modifier.fillMaxWidth(), lines = 3)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = orbitPageSide(maxWidth)
        val top = contentPadding.calculateTopPadding()
        val bottom = contentPadding.calculateBottomPadding()
        if (maxWidth >= TWO_PANES) {
            // The tablet's album (the Music board's 9): the tall card and About in a pane of their own, the songs scrolling beside them.
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(PANE_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = side, top = top + PageMetrics.COVER_TOP_DP.dp, bottom = bottom + 24.dp),
                ) {
                    card(true, Modifier.fillMaxWidth())
                    about(Modifier.fillMaxWidth())
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(top = top, bottom = bottom + 24.dp),
                ) { list(side) }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = top + PageMetrics.COVER_TOP_DP.dp, bottom = bottom + 24.dp),
            ) {
                item(key = "card", contentType = "card") { card(false, Modifier.padding(horizontal = side).fillMaxWidth()) }
                list(side)
                if (description != null) item(key = "about", contentType = "about") { about(Modifier.padding(horizontal = side)) }
            }
        }
        // Select mode's actions, at the foot over the dock: play the chosen songs, or their menu (add to a playlist, download, ...).
        if (selecting) {
            OrbitSelectionBar(
                listOfNotNull(
                    OrbitBarAction(CosmoIcons.Play, "Play", enabled = selectedSongs.isNotEmpty()) { onSongClick(selectedSongs, 0) },
                    OrbitBarAction(CosmoIcons.Shuffle, shuffleLabel, enabled = selectedSongs.isNotEmpty()) { onShuffle(selectedSongs) },
                    onDownloadAll?.takeIf { !local }?.let { download -> OrbitBarAction(CosmoIcons.Download, "Download", enabled = selectedSongs.isNotEmpty()) { download(selectedSongs) } },
                    onMore?.let { more -> OrbitBarAction(CosmoIcons.MoreVertical, moreLabel, enabled = selectedSongs.isNotEmpty()) { more(selectedSongs) } },
                ),
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = bottom.coerceAtLeast(16.dp)),
            )
        }
    }
}

/** From this width the page is two panes ([PANE_WIDTH] for the card, the rest for the songs). */
private val TWO_PANES = 840.dp
private val PANE_WIDTH = 360.dp

/** A row's one trailing button: More, the song's menu (as a hold was before select mode took the hold). */
@Composable
private fun RowMore(onMore: (Song) -> Unit, song: Song) {
    val look = orbitLook()
    OrbitRoundButton(CosmoIcons.MoreVertical, "More", look.dim, { onMore(song) }, size = 40.dp, onInk = look.ground)
}

/**
 * The slim line over the list (the shared board's A1): the count and length in the mono face, then find in the list and the order as
 * bare round buttons. The order's menu also holds "Downloaded only" (when the page is not the device's own) and "Select", and
 * "Clear filters" while a filter narrows the list.
 */
@Composable
private fun ListLine(
    text: String,
    searching: Boolean,
    onFind: () -> Unit,
    findLabel: String,
    order: MusicListOrder,
    onOrder: (MusicListOrder) -> Unit,
    hasAddedDates: Boolean,
    downloadedOnly: Boolean,
    onDownloadedOnly: ((Boolean) -> Unit)?,
    onSelect: () -> Unit,
    onReset: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().testTag("music:list:controls"), verticalAlignment = Alignment.CenterVertically) {
        OrbitLabel(text, fonts, look.dim, Modifier.weight(1f))
        OrbitRoundButton(
            if (searching) CosmoIcons.Close else CosmoIcons.Search, findLabel, look.ink, onFind, Modifier.testTag("music:release:find"),
            size = 36.dp, on = searching, onInk = look.ground, touch = 48.dp,
        )
        Box {
            OrbitRoundButton(CosmoIcons.Sort, stringResource(Res.string.music_list_sort, orderLabel(order)), look.ink, { open = true }, Modifier.testTag("music:list:order"), size = 36.dp, on = onReset != null, onInk = look.ground, touch = 48.dp)
            OrbitMenu(
                open = open, onDismiss = { open = false }, fonts = fonts, look = look, header = "Order", width = 250.dp,
                items = MusicListOrder.entries.filter { hasAddedDates || it !in setOf(MusicListOrder.ADDED, MusicListOrder.ADDED_DESC) }.map { option ->
                    OrbitMenuItem(orderLabel(option), selected = option == order) { onOrder(option) }
                } + listOfNotNull(
                    onDownloadedOnly?.let { set -> OrbitMenuItem("Downloaded only", icon = CosmoIcons.Downloaded, selected = downloadedOnly, keepOpen = true, testTag = "music:list:downloaded") { set(!downloadedOnly) } },
                    OrbitMenuItem(stringResource(Res.string.music_list_select), icon = CosmoIcons.Check, testTag = "music:list:select", onClick = onSelect),
                    onReset?.let { OrbitMenuItem(stringResource(Res.string.music_list_reset), icon = CosmoIcons.Close, onClick = it) },
                ),
            )
        }
    }
}

/**
 * Keeps a playlist downloaded while its page is open: on Wi-Fi (an unmetered connection), the songs of [songs] that are neither on the
 * device nor fetched by this switch before ([KeepDownloaded.fresh]) are handed to [download], the app's own downloads, and remembered
 * for [playlistId]; so a song the person removes from the device is not fetched again. Away from the page the background pass
 * ([KeepDownloaded.run]) does the same.
 */
@Composable
private fun KeepDownloadedEffect(playlistId: String, songs: List<Song>, downloadedIds: Set<String>, download: (List<Song>) -> Unit) {
    val metered by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    val look = rememberCosmoLookSettings()
    val current by rememberUpdatedState(download)
    LaunchedEffect(playlistId, songs, downloadedIds, metered) {
        if (metered != false) return@LaunchedEffect
        val kept = look.keptSongs(playlistId)
        val fresh = KeepDownloaded.fresh(songs, { it in downloadedIds }, kept)
        if (fresh.isEmpty()) return@LaunchedEffect
        look.addKeptSongs(playlistId, fresh.map { it.videoId })
        current(fresh)
    }
}

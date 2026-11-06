package com.cosmotune.ui.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.audio.LibraryLoudness
import com.cosmotune.listening.LibraryViewOptions
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.ui.player.PlayerBackHandler
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.listening.LibraryColumnsBar
import com.cosmotune.ui.listening.LibraryColumnsLine
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
import com.cosmotune.ui.orbit.OrbitSearchPill
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSongRowFor
import com.cosmotune.ui.orbit.OrbitStatCard
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitRecordGrid
import java.io.File
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Downloads and Local Music in Orbit (Canvas repo, design/cosmotune/batch-a/board.html, section 1), one page for both hosts and both
 * folders: the heading with a mono count, one quiet stat card (Downloads: the space they take with its peach bar and what is left on
 * the disk), the search pill, chips for Songs, Artists and Albums and the sort, and under them the chip's page: songs as Home's rows,
 * artists as portraits, albums (the releases downloaded whole first) as records. An artist or an album opens its songs in place.
 * Holding a song in Downloads selects it (when the host can delete): a bar of chips then deletes, uploads or lets go of the chosen.
 */

/** A release kept whole on the device (an album or a playlist downloaded as one), which leads the Albums chip. */
@Immutable
data class DeviceCollection(
    val key: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val songs: List<Song>,
    val playlist: Boolean = false,
)

private enum class DeviceChip(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums") }

/** An artist or an album opened in place: its name, its picture and its songs. */
private class DeviceGroup(val key: String, val title: String, val subtitle: String, val cover: String?, val songs: List<Song>, val playlist: Boolean = false)

/**
 * The page. [songs] come sorted by the host in the order of [sortLabels]' [sortIndex] ([onSort] picks another). [columns] and
 * [onColumnsChange] are the library's columns and filters (LK-09), kept by the host per folder. [onDeleteSelected] (Downloads on the
 * phone) makes a hold select; [onSongLongPress] is then the row's more button. [emptyMessage] says why there are no songs at all,
 * and [onFindMusic] offers a search.
 */
@Composable
fun CosmoDeviceMusic(
    title: String,
    songs: List<Song>,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    isDownloads: Boolean = false,
    onSongSwipe: ((Song) -> Unit)? = null,
    onShuffle: ((List<Song>) -> Unit)? = null,
    collections: List<DeviceCollection> = emptyList(),
    sortLabels: List<String> = emptyList(),
    sortIndex: Int = 0,
    onSort: (Int) -> Unit = {},
    columns: LibraryViewOptions? = null,
    onColumnsChange: ((LibraryViewOptions) -> Unit)? = null,
    emptyMessage: String? = null,
    onFindMusic: (() -> Unit)? = null,
    onCollectionLongPress: ((String, List<Song>) -> Unit)? = null,
    onDeleteSelected: ((List<Song>) -> Unit)? = null,
    onUploadSelected: ((List<Song>) -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var chip by rememberSaveable { mutableStateOf(DeviceChip.SONGS) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var open by remember { mutableStateOf<DeviceGroup?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    val selecting = onDeleteSelected != null && selected.isNotEmpty()
    PlayerBackHandler(enabled = selecting) { selected = emptySet() }
    PlayerBackHandler(enabled = open != null && !selecting) { open = null }

    val analysisOf = { song: Song -> LibraryLoudness.analysisFor(song.videoId) }
    val genres = remember(songs) { LibraryViewOptions.genresOf(songs, analysisOf) }
    val shownSongs = remember(songs, query, columns) {
        val found = if (query.isBlank()) songs else songs.filter { it.matchesQuery(query) }
        columns?.apply(found, analysisOf) ?: found
    }
    val artists = remember(songs, query) {
        songs.groupBy { it.artist.ifBlank { "Unknown artist" } }.entries
            .filter { query.isBlank() || it.key.contains(query, ignoreCase = true) }
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { (name, rows) -> DeviceGroup("artist:$name", name, "${rows.size} ${if (rows.size == 1) "song" else "songs"}", rows.firstOrNull()?.thumbnailUrl, rows) }
    }
    val albums = remember(songs, collections, query) {
        deviceAlbums(songs, collections).filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true) }
    }
    val storage by produceState<DeviceStorage?>(null, songs) { value = withContext(Dispatchers.IO) { runCatching { deviceStorage(songs) }.getOrNull() } }

    BoxWithConstraints(modifier.fillMaxSize().testTag("music:device")) {
        val wide = maxWidth >= 840.dp
        val side = if (wide) 28.dp else 16.dp
        val sideColumn = (maxWidth / 3).coerceIn(300.dp, 380.dp)
        val listWidth = if (wide && open == null) maxWidth - sideColumn - side else maxWidth
        val (perRow, cell) = orbitRecordGrid(listWidth, side)
        val stat: @Composable (Modifier) -> Unit = { m -> DeviceStat(title, isDownloads, songs, storage, fonts, look, m) }
        val playing = playingRowMatcher(if (open != null) open!!.songs else shownSongs, currentSong)
        val songRow: @Composable (List<Song>, Int) -> Unit = { list, index ->
            val song = list[index]
            val now = playing(song)
            Column(Modifier.padding(horizontal = side, vertical = 4.dp).widthIn(max = 760.dp)) {
                OrbitSongRowFor(
                    song = song, fonts = fonts, look = look,
                    onClick = {
                        if (selecting) selected = selected.toggle(song.videoId) else onSongClick(list, index)
                    },
                    onLongClick = {
                        if (onDeleteSelected != null) selected = selected + song.videoId else onSongLongPress(song)
                    },
                    onSwipe = onSongSwipe?.let { swipe -> { swipe(song) } },
                    now = now, playing = now && isPlaying,
                    selected = if (selecting) song.videoId in selected else null,
                    trailing = if (onDeleteSelected != null && !selecting) {
                        {
                            song.durationText?.let { Text(it, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp)) }
                            OrbitRoundButton(CosmoIcons.MoreVertical, "More", look.ink, onClick = { onSongLongPress(song) }, size = 36.dp, modifier = Modifier.padding(start = 6.dp))
                        }
                    } else null,
                )
                if (columns != null) LibraryColumnsLine(song, columns, analysisOf(song), Modifier.padding(start = 66.dp, end = 12.dp, top = 2.dp))
            }
        }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxSize(), contentPadding = contentPadding) {
                val group = open
                if (group != null) {
                    item(key = "device:open") { GroupHeading(group, fonts, look, side, onBack = { open = null },
                        onPlay = { if (group.songs.isNotEmpty()) onSongClick(group.songs, 0) },
                        onShuffle = onShuffle?.let { shuffle -> { shuffle(group.songs) } },
                        onMore = onCollectionLongPress?.let { more -> { more(group.title, group.songs) } }) }
                    items(group.songs.size, key = { "device:open:${group.songs[it].videoId}:$it" }) { songRow(group.songs, it) }
                    return@LazyColumn
                }
                item(key = "device:heading") {
                    val artistCount = remember(songs) { songs.map { it.artist }.filter { it.isNotBlank() }.distinct().size }
                    OrbitPageHeading(
                        title = title,
                        label = if (songs.isEmpty()) if (isDownloads) "Kept on this device" else "Files on this device" else "${songs.size} songs · $artistCount artists",
                        fonts = fonts, look = look,
                        modifier = Modifier.padding(start = side, end = side, top = 12.dp, bottom = 18.dp),
                        trailing = if (onShuffle != null && shownSongs.isNotEmpty()) {
                            { OrbitRoundButton(CosmoIcons.Shuffle, "Shuffle", look.ink, onClick = { onShuffle(shownSongs) }, size = 44.dp) }
                        } else null,
                    )
                }
                if (!wide) item(key = "device:stat") { stat(Modifier.padding(horizontal = side).padding(bottom = 14.dp)) }
                if (songs.isEmpty()) {
                    item(key = "device:empty") {
                        Column(Modifier.padding(horizontal = side)) {
                            OrbitStatCard(
                                label = title,
                                title = if (isDownloads) "No downloads" else "No local music",
                                line = emptyMessage ?: if (isDownloads) "Downloaded songs appear here." else "Put audio files in your Music folder and open this page again.",
                                fonts = fonts, look = look,
                            )
                            if (onFindMusic != null) OrbitSelectChip("Search", true, fonts, look, onFindMusic, Modifier.padding(top = 12.dp), icon = CosmoIcons.Search)
                        }
                    }
                    return@LazyColumn
                }
                item(key = "device:search") {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    OrbitSearchPill(query, { query = it }, onSubmit = {}, placeholder = "Search ${title.lowercase(Locale.ROOT)}", fonts = fonts, look = look,
                        focusRequester = focus, modifier = Modifier.padding(horizontal = side).padding(bottom = 12.dp).widthIn(max = 760.dp))
                }
                item(key = "device:chips") {
                    if (selecting) {
                        SelectionChips(
                            count = selected.size, fonts = fonts, look = look, side = side,
                            allSelected = selected.containsAll(shownSongs.map { it.videoId }),
                            onSelectAll = { selected = shownSongs.mapTo(linkedSetOf()) { it.videoId } },
                            onDelete = { val chosen = songs.filter { it.videoId in selected }; selected = emptySet(); onDeleteSelected?.invoke(chosen) },
                            onUpload = onUploadSelected?.let { upload -> { val chosen = songs.filter { it.videoId in selected }; selected = emptySet(); upload(chosen) } },
                            onCancel = { selected = emptySet() },
                        )
                    } else {
                        OrbitChipRow(side) {
                            DeviceChip.entries.forEach { c ->
                                OrbitSelectChip(c.label, chip == c, fonts, look, { chip = c }, Modifier.testTag("device:chip:${c.name}"))
                            }
                            if (sortLabels.isNotEmpty()) {
                                Box {
                                    OrbitSelectChip(sortLabels[sortIndex.coerceIn(0, sortLabels.lastIndex)], false, fonts, look, { sortOpen = true }, Modifier.testTag("device:sort"), icon = CosmoIcons.Sort)
                                    OrbitMenu(
                                        open = sortOpen, onDismiss = { sortOpen = false }, fonts = fonts, look = look, header = "Sort by",
                                        items = sortLabels.mapIndexed { i, label -> OrbitMenuItem(label, selected = i == sortIndex) { onSort(i) } },
                                    )
                                }
                            }
                        }
                    }
                }
                if (chip == DeviceChip.SONGS && columns != null && onColumnsChange != null) {
                    item(key = "device:columns") { LibraryColumnsBar(columns, genres, onColumnsChange, Modifier.padding(horizontal = side).padding(top = 10.dp)) }
                }
                item(key = "device:gap") { Spacer(Modifier.height(10.dp)) }
                when (chip) {
                    DeviceChip.SONGS -> {
                        if (shownSongs.isEmpty()) item(key = "device:none") { NoMatch(fonts, look, side) }
                        items(shownSongs.size, key = { "device:song:${shownSongs[it].videoId}:$it" }) { songRow(shownSongs, it) }
                    }
                    DeviceChip.ARTISTS -> {
                        if (artists.isEmpty()) item(key = "device:none") { NoMatch(fonts, look, side) }
                        items(artists.chunked(perRow), key = { "device:artists:${it.first().key}" }) { row ->
                            Row(Modifier.padding(horizontal = side, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                row.forEach { artist ->
                                    OrbitPortrait(artist.title, artist.cover, cell, fonts, look, onClick = { open = artist },
                                        onLongClick = onCollectionLongPress?.let { more -> { more(artist.title, artist.songs) } })
                                }
                            }
                        }
                    }
                    DeviceChip.ALBUMS -> {
                        if (albums.isEmpty()) item(key = "device:none") { NoMatch(fonts, look, side) }
                        items(albums.chunked(perRow), key = { "device:albums:${it.first().key}" }) { row ->
                            Row(Modifier.padding(horizontal = side, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                row.forEach { album ->
                                    OrbitRecord(
                                        album.title, album.subtitle, album.cover, fonts, look,
                                        onClick = { open = album },
                                        onPlay = { if (album.songs.isNotEmpty()) onSongClick(album.songs, 0) },
                                        onLongClick = onCollectionLongPress?.let { more -> { more(album.title, album.songs) } },
                                        size = cell,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (wide && open == null) {
                Column(Modifier.width(sideColumn).padding(top = contentPadding.calculateTopPadding() + 112.dp, end = side)) { stat(Modifier) }
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

private fun Song.matchesQuery(query: String): Boolean =
    title.contains(query, ignoreCase = true) || artist.contains(query, ignoreCase = true) || albumName.orEmpty().contains(query, ignoreCase = true)

/** The albums of [songs]: the releases kept whole first (they are the ones asked for by name), then the album tags of the rest. */
private fun deviceAlbums(songs: List<Song>, collections: List<DeviceCollection>): List<DeviceGroup> {
    val kept = collections.map { DeviceGroup("collection:${it.key}", it.title, listOfNotNull(if (it.playlist) "Playlist" else "Album", it.artist.takeIf(String::isNotBlank)).joinToString(" · "), it.thumbnailUrl, it.songs, it.playlist) }
    val covered = collections.flatMapTo(HashSet()) { c -> c.songs.map { it.videoId } }
    val tagged = songs.filter { it.videoId !in covered }
        .groupBy { it.albumName?.takeIf(String::isNotBlank) ?: "Unknown album" }
        .entries.sortedBy { it.key.lowercase(Locale.ROOT) }
        .map { (name, rows) ->
            val artist = rows.map { it.artist }.distinct().singleOrNull().orEmpty()
            DeviceGroup("album:$name", name, listOfNotNull("Album", artist.takeIf(String::isNotBlank)).joinToString(" · "), rows.firstOrNull()?.thumbnailUrl, rows)
        }
    return kept + tagged
}

/** How much room the folder's files take and what is left on their disk; null fields when the files cannot be found to measure. */
private class DeviceStorage(val bytes: Long, val counted: Int, val free: Long?)

private fun Song.file(): File? =
    localPath?.let(::File) ?: localUri?.takeIf { it.startsWith("file:") }?.let { runCatching { File(URI(it)) }.getOrNull() }

private fun deviceStorage(songs: List<Song>): DeviceStorage {
    var bytes = 0L
    var counted = 0
    var free: Long? = null
    for (song in songs) {
        val file = song.file()?.takeIf { it.isFile } ?: continue
        bytes += file.length()
        counted++
        if (free == null) free = file.parentFile?.usableSpace?.takeIf { it > 0 }
    }
    return DeviceStorage(bytes, counted, free)
}

private fun bytesText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
}

/** The quiet stat card: Downloads shows the space used with its peach bar and what is free; Local Music the size of its files. */
@Composable
private fun DeviceStat(title: String, isDownloads: Boolean, songs: List<Song>, storage: DeviceStorage?, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val measured = storage?.takeIf { it.counted > 0 }
    val count = "${songs.size} ${if (songs.size == 1) "song" else "songs"}"
    val free = measured?.free
    OrbitStatCard(
        label = "$title · on this device",
        title = if (measured != null) "$count · ${bytesText(measured.bytes)}" else count,
        line = when {
            free != null -> "${bytesText(free)} free"
            songs.isEmpty() -> null
            else -> if (isDownloads) "Plays without a connection" else "Files found on this device"
        },
        fonts = fonts, look = look, modifier = modifier.fillMaxWidth(),
        fraction = if (isDownloads && measured != null && free != null) measured.bytes.toFloat() / (measured.bytes + free).toFloat() else null,
    )
}

@Composable
private fun NoMatch(fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    OrbitStatCard("Search", "No matching music", "Try a different search.", fonts, look, Modifier.padding(horizontal = side))
}

/** While songs are chosen: how many, and chips to choose them all, delete them, upload them, or let go. */
@Composable
private fun SelectionChips(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onUpload: (() -> Unit)?,
    onCancel: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    side: Dp,
) {
    Column {
        OrbitLabel("$count selected", fonts, look.dim, Modifier.padding(start = side + 6.dp, bottom = 8.dp))
        OrbitChipRow(side) {
            if (!allSelected) OrbitSelectChip("Select all", false, fonts, look, onSelectAll, icon = CosmoIcons.Check)
            OrbitSelectChip("Delete", true, fonts, look, onDelete, Modifier.testTag("device:delete"), icon = CosmoIcons.Close)
            if (onUpload != null) OrbitSelectChip("Upload to WebDAV", false, fonts, look, onUpload, icon = CosmoIcons.Share)
            OrbitSelectChip("Cancel", false, fonts, look, onCancel)
        }
    }
}

/** The head of an artist or an album opened in place: back, its disc-free title and count, Play in peach, shuffle and more. */
@Composable
private fun GroupHeading(
    group: DeviceGroup,
    fonts: OrbitFonts,
    look: OrbitLook,
    side: Dp,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    onMore: (() -> Unit)?,
) {
    Column(Modifier.padding(start = side, end = side, top = 8.dp, bottom = 14.dp)) {
        OrbitRoundButton(CosmoIcons.ArrowLeft, "Back", look.ink, onBack, Modifier.padding(bottom = 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                OrbitLabel(group.subtitle, fonts, look.dim)
                Text(group.title, style = fonts.heroTitle, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            if (onMore != null) OrbitRoundButton(CosmoIcons.More, "More", look.ink, onMore, Modifier.padding(start = 8.dp))
            if (onShuffle != null) OrbitRoundButton(CosmoIcons.Shuffle, "Shuffle", look.ink, onShuffle, Modifier.padding(start = 8.dp))
            OrbitPlayButton(playing = false, onClick = onPlay, look = look, modifier = Modifier.padding(start = 10.dp), size = 52.dp)
        }
    }
}

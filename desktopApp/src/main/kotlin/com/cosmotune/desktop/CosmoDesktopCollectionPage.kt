package com.cosmotune.desktop

import com.cosmotune.music.desktop.desktopSongContextMenu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cosmotune.design.CollectionColumns
import com.cosmotune.design.CoverArt
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.bridge.DropdownMenu
import com.cosmotune.ui.bridge.DropdownMenuItem
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.CoverFrame
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.durationMillis
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.data.model.isUnresolvedSpotify
import com.cosmotune.music.desktop.DesktopArtwork
import com.cosmotune.music.desktop.DesktopCanvasArtwork
import com.cosmotune.music.desktop.DesktopCanvasClient
import com.cosmotune.music.desktop.DesktopCanvasView
import com.cosmotune.music.desktop.DesktopCollection
import com.cosmotune.music.desktop.DesktopStrings
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_song_count
import com.cosmotune.music.sharedui.resources.cosmo_type_album
import com.cosmotune.music.sharedui.resources.cosmo_type_artist
import com.cosmotune.music.sharedui.resources.cosmo_type_ep
import com.cosmotune.music.sharedui.resources.cosmo_type_playlist
import com.cosmotune.music.sharedui.resources.cosmo_type_single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * An album or a playlist in the new look (`V2.desktop`, `v2/v2.js:172-183`, `screens-v2.css:208-220`): the hero (the cover, what
 * it is, its name, who made it, and the actions) and under it the tracks in four columns.
 */

/** `.d2-main { padding: 16px 28px }`. */
private val PAGE_PADDING_X = 28.dp
private val PAGE_PADDING_TOP = 16.dp

/** `.d2-hero`: the cover is 232 dp (radius 28), 28 dp from the words, which stand on its foot. */
private val HERO_COVER = 232.dp
private val HERO_COVER_RADIUS = 28.dp
private val HERO_GAP = 28.dp

/** `.d2-tracks { gap: 2px }`, and room under the last row for whatever floats over the page. */
private val ROW_GAP = 2.dp
private val PAGE_FOOT = 32.dp

/** A circle the artist's initial stands in (`.al-artist .person`): 28 dp. */
private val CREDIT_CIRCLE = 28.dp

/**
 * The collection page of the new look. It takes the old page's parameters, in the same order, and two more at the end:
 * [nowPlaying] (the song that is playing, whose row is washed and has the bars in place of its number) and [isPlaying] (the bars
 * move; from [LocalCosmoPlaying], which the root fills with the player's own flag). The old page reads the playing song from a local
 * that only its own file can see, so its delegate passes [nowPlaying] and leaves [isPlaying] at its default.
 *
 * What the old page did it still does: the list filter, the next page of tracks when the last row shows, the motion artwork in the
 * cover when [animatedCanvas], the more menu with download all, share, rename and delete (the last two for a playlist of the
 * account's own), a Spotify track with no match dimmed and inert, and the stripe following the place on screen, not the number.
 */
@Composable
internal fun CosmoDesktopCollectionPage(
    collection: DesktopCollection,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onRemoveFromPlaylist: ((Song) -> Unit)?,
    likedIds: Set<String>,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onDownloadAll: (List<Song>) -> Unit,
    animatedCanvas: Boolean,
    onToggleLike: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    downloadedIds: Set<String>,
    downloadInProgress: Set<String>,
    contentPadding: PaddingValues,
    onReorder: (() -> Unit)? = null,
    nowPlaying: Song? = null,
    isPlaying: Boolean = LocalCosmoPlaying.current,
) {
    val songs = collection.songs
    // "Search this list": a filter over the rows already loaded.
    var searching by remember(collection.browseId) { mutableStateOf(false) }
    var listQuery by remember(collection.browseId) { mutableStateOf("") }
    // The row that was played lights, not every namesake: see [playingRowMatcher].
    val isCurrentRow = remember(songs, nowPlaying) { playingRowMatcher(songs, nowPlaying) }
    val shownSongs = remember(songs, listQuery) {
        val needle = listQuery.trim()
        songs.withIndex().filter { (_, song) ->
            needle.isBlank() || song.title.contains(needle, ignoreCase = true) ||
                song.artist.contains(needle, ignoreCase = true) ||
                song.albumName.orEmpty().contains(needle, ignoreCase = true)
        }
    }
    val isAlbum = collection.type == BrowseType.ALBUM
    // The album column is for rows that name an album: an album's rows are all on it, and a playlist whose feed names none of them
    // would have a dash on every row. Decided on every row loaded, not on the filtered ones, so the column does not come and go while typing.
    val showAlbum = remember(songs, isAlbum) { CollectionColumns.showsAlbum(isAlbum, songs.map { it.albumName }) }
    val bottom = contentPadding.calculateBottomPadding()
    val direction = LocalLayoutDirection.current
    Box(
        Modifier.fillMaxSize().padding(
            start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction),
            top = contentPadding.calculateTopPadding(),
        ),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = PAGE_PADDING_X, top = PAGE_PADDING_TOP, end = PAGE_PADDING_X, bottom = PAGE_FOOT + bottom),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            item(key = "collection-hero") {
                CollectionHero(
                    collection = collection,
                    searching = searching,
                    onToggleSearching = {
                        searching = !searching
                        if (!searching) listQuery = ""
                    },
                    animatedCanvas = animatedCanvas,
                    onPlaySongs = onPlaySongs,
                    onShuffle = onShuffle,
                    onDownloadAll = onDownloadAll,
                    onShare = onShare,
                    onRename = onRename,
                    onDelete = onDelete,
                    onReorder = onReorder,
                )
            }
            if (searching) {
                item(key = "collection-search") {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    CosmoSearchField(
                        query = listQuery,
                        onQueryChange = { listQuery = it },
                        onSubmit = {},
                        placeholder = DesktopStrings["search_this_list", "Search this list"],
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        focusRequester = focus,
                        height = 44.dp,
                    )
                }
            }
            itemsIndexed(shownSongs, key = { _, (index, song) -> "${song.videoId}-$index" }) { position, (index, song) ->
                CollectionTrackRow(
                    song = song,
                    number = index + 1,
                    // Striped by place on screen, not track number, so a filtered list still alternates.
                    striped = position % 2 == 1,
                    showAlbum = showAlbum,
                    isCurrent = isCurrentRow(song),
                    isPlaying = isPlaying,
                    liked = song.videoId in likedIds,
                    downloaded = song.videoId in downloadedIds,
                    downloadInProgress = song.videoId in downloadInProgress,
                    onClick = { onPlaySongs(songs, index) },
                    onToggleLike = onToggleLike,
                    onDownload = onDownload,
                    onAddToPlaylist = onAddToPlaylist,
                    onRemove = onRemoveFromPlaylist?.takeIf { song.setVideoId != null },
                    reserveRemove = onRemoveFromPlaylist != null,
                )
            }
            if (collection.continuation != null) {
                // Reaching this row is what asks for the next page, the way the home feed pages.
                item(key = "collection-more") {
                    LaunchedEffect(songs.size) { onLoadMore() }
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) CosmoLoadingBlob(Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

// ---- the hero ----

@Composable
private fun CollectionHero(
    collection: DesktopCollection,
    searching: Boolean,
    onToggleSearching: () -> Unit,
    animatedCanvas: Boolean,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onDownloadAll: (List<Song>) -> Unit,
    onShare: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onReorder: (() -> Unit)? = null,
) {
    val songs = collection.songs
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val parts = remember(collection.subtitle) {
        collection.subtitle.split(" • ", " · ", " | ").map(String::trim).filter(String::isNotBlank)
    }
    val credit = remember(parts) { parts.firstOrNull(::isCredit).orEmpty() }
    val year = remember(parts) { parts.firstOrNull { it.matches(YEAR) } }
    val typeLabel = collectionTypeLabel(collection.type, parts)
    val countText = if (songs.isNotEmpty()) pluralStringResource(SharedRes.plurals.cosmo_song_count, songs.size, songs.size) else null
    val seconds = remember(songs) { songs.sumOf { it.durationText.durationMillis() / 1_000L } }
    val facts = listOfNotNull(year, countText, if (seconds > 0) formatDuration(seconds) else null).joinToString(" · ")
    // The services hang motion artwork off the release, so a page for one asks for it directly rather than picking a track and
    // hoping it sits on the right edition.
    val albumCanvas by produceState<DesktopCanvasArtwork?>(null, collection.title, animatedCanvas) {
        value = if (!animatedCanvas) {
            null
        } else {
            withContext(Dispatchers.IO) { DesktopCanvasClient.lookupAlbum(collection.title, songs.firstOrNull()?.artist.orEmpty()) }
        }
    }
    val density = LocalDensity.current.density
    val coverPx = remember(density) { CoverArt.px(HERO_COVER.value, density) }
    // `.d2-tracks` starts 14 dp under the hero, whose own foot is 8: the two rows of the list are 2 apart already.
    Row(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(HERO_GAP),
        verticalAlignment = Alignment.Bottom,
    ) {
        CoverFrame(size = HERO_COVER, radius = HERO_COVER_RADIUS, shadow = true) {
            DesktopArtwork(collection.thumbnailUrl ?: songs.firstOrNull()?.thumbnailUrl, Modifier.fillMaxSize(), px = coverPx)
            albumCanvas?.let { DesktopCanvasView(it.url, Modifier.fillMaxSize(), fallbackUrl = it.fallbackUrl) }
        }
        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
            Text(
                text = typeLabel,
                style = type.forText(overlineStyle(type.textFamily), typeLabel),
                color = Color(palette.textSecondary),
                maxLines = 1,
            )
            Text(
                text = collection.title,
                style = type.forText(type.display(TypeScale.DISPLAY_L), collection.title),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (credit.isNotBlank() || facts.isNotBlank()) {
                Row(
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (credit.isNotBlank()) {
                        CreditCircle(credit, colors)
                        Text(
                            text = credit,
                            style = type.forText(type.body(weight = FontWeight(650)).copy(fontSize = 15.sp, lineHeight = 15.sp * 1.3f), credit),
                            color = Color(palette.textPrimary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (facts.isNotBlank()) {
                        Text(
                            text = facts,
                            style = type.desktopRowDuration().copy(fontSize = 13.sp),
                            color = Color(palette.textTertiary),
                            maxLines = 1,
                        )
                    }
                }
            }
            HeroActions(
                searching = searching,
                onPlay = { if (songs.isNotEmpty()) onPlaySongs(songs, 0) },
                onShuffle = { onShuffle(songs) },
                onToggleSearching = onToggleSearching,
                onDownloadAll = { onDownloadAll(songs) },
                onShare = onShare,
                onRename = onRename,
                onDelete = onDelete,
                onReorder = onReorder,
                sourceLibrary = { com.cosmotune.music.desktop.DesktopSourceLibraryButton(collection) },
            )
        }
    }
}

/** `.al-actions` in the hero: Play, Shuffle, find in the list, and More with the old page's items. */
@Composable
private fun HeroActions(
    searching: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSearching: () -> Unit,
    onDownloadAll: () -> Unit,
    onShare: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onReorder: (() -> Unit)? = null,
    sourceLibrary: @Composable () -> Unit = {},
) {
    var moreOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CosmoButton(
            text = DesktopStrings["play", "Play"],
            onClick = onPlay,
            kind = CosmoButtonKind.PRIMARY,
            size = CosmoButtonSize.LARGE,
            icon = CosmoIcons.Play,
        )
        CosmoIconButton(
            icon = CosmoIcons.Shuffle,
            contentDescription = DesktopStrings["shuffle", "Shuffle"],
            onClick = onShuffle,
            style = CosmoIconButtonStyle.TONAL,
        )
        CosmoIconButton(
            icon = if (searching) CosmoIcons.Close else CosmoIcons.Search,
            contentDescription = if (searching) DesktopStrings["close_search", "Close search"] else DesktopStrings["search_this_list", "Search this list"],
            onClick = onToggleSearching,
            style = CosmoIconButtonStyle.TONAL,
        )
        sourceLibrary()
        Box {
            CosmoIconButton(
                icon = CosmoIcons.More,
                contentDescription = DesktopStrings["more", "More"],
                onClick = { moreOpen = true },
                style = CosmoIconButtonStyle.PLAIN,
            )
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                DropdownMenuItem(
                    text = { Text(DesktopStrings["download_all", "Download all"]) },
                    leadingIcon = { Icon(CosmoIcons.Download, null) },
                    onClick = {
                        moreOpen = false
                        onDownloadAll()
                    },
                )
                onShare?.let { share ->
                    DropdownMenuItem(
                        text = { Text(DesktopStrings["share", "Share"]) },
                        leadingIcon = { Icon(CosmoIcons.Share, null) },
                        onClick = {
                            moreOpen = false
                            share()
                        },
                    )
                }
                onRename?.let { rename ->
                    DropdownMenuItem(
                        text = { Text(DesktopStrings["rename", "Rename"]) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            moreOpen = false
                            rename()
                        },
                    )
                }
                onReorder?.let { reorder ->
                    DropdownMenuItem(
                        text = { Text(DesktopStrings["reorder_playlist", "Edit order"]) },
                        leadingIcon = { Icon(Icons.Rounded.DragHandle, null) },
                        onClick = {
                            moreOpen = false
                            reorder()
                        },
                    )
                }
                onDelete?.let { delete ->
                    DropdownMenuItem(
                        text = { Text(DesktopStrings["delete", "Delete"]) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = {
                            moreOpen = false
                            delete()
                        },
                    )
                }
            }
        }
    }
}

/** `.al-artist .person`: the first letter of who made it, on the cover's container, 28 dp round. */
@Composable
private fun CreditCircle(credit: String, colors: CosmoColors) {
    val type = LocalCosmoType.current
    Box(
        Modifier
            .size(CREDIT_CIRCLE)
            .clip(CircleShape)
            .drawBehind { drawRect(colors.live(CoverField.ACCENT_CONTAINER)) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = credit.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(),
            style = type.body(weight = FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 13.sp),
            color = Color(colors.palette.onAccentContainer),
            maxLines = 1,
        )
    }
}

/** `.t-overline`: 12 sp, weight 700, a little tracking. */
private fun overlineStyle(family: FontFamily): TextStyle =
    TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 12.sp * 1.3f, letterSpacing = 0.01.em)

// ---- the words of the hero ----

private val YEAR = Regex("\\d{4}")
private val CLOCK = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")
private val TALLY = Regex("[0-9,.]+\\s*(?:songs?|tracks?)", RegexOption.IGNORE_CASE)
private val TYPE_WORDS = setOf("album", "single", "ep", "playlist", "artist")

/** Whether a piece of the subtitle names who made the thing: not the kind, the year, a length or a count. */
private fun isCredit(part: String): Boolean =
    part.lowercase() !in TYPE_WORDS && !part.matches(YEAR) && !part.matches(CLOCK) && !part.matches(TALLY)

/** What the thing is, from the kind the page was opened as and, for a release, from the words of its subtitle (single, EP). */
@Composable
private fun collectionTypeLabel(type: BrowseType, subtitleParts: List<String>): String = when (type) {
    BrowseType.ALBUM -> when {
        subtitleParts.any { it.equals("single", ignoreCase = true) } -> stringResource(SharedRes.string.cosmo_type_single)
        subtitleParts.any { it.equals("ep", ignoreCase = true) } -> stringResource(SharedRes.string.cosmo_type_ep)
        else -> stringResource(SharedRes.string.cosmo_type_album)
    }
    BrowseType.PLAYLIST -> stringResource(SharedRes.string.cosmo_type_playlist)
    BrowseType.ARTIST -> stringResource(SharedRes.string.cosmo_type_artist)
    BrowseType.OTHER -> DesktopStrings["d_collection", "Collection"]
}

/** "38 min", or "1 hr 4 min" from an hour up. */
private fun formatDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60L
    return if (minutes < 60L) "$minutes min" else "${minutes / 60L} hr ${minutes % 60L} min"
}

// ---- a track ----

/**
 * `.d2-tracks li`: four columns, the number (or the bars) in 36 dp, the title over the artist, the album (only when [showAlbum]: a
 * release has none to say, and a playlist that names no album would show a dash on every row), the actions and the duration in 60 dp. A Spotify track that has no match is dimmed and does
 * nothing; one still being matched shows a spinner for its number.
 */
@Composable
private fun CollectionTrackRow(
    song: Song,
    number: Int,
    striped: Boolean,
    showAlbum: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    liked: Boolean,
    downloaded: Boolean,
    downloadInProgress: Boolean,
    onClick: () -> Unit,
    onToggleLike: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onRemove: ((Song) -> Unit)?,
    reserveRemove: Boolean,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    // A Spotify track still being matched to a service, or with no match: listed, but there is nothing yet to play, like, save or remove.
    val unresolved = song.isUnresolvedSpotify
    val chrome = rememberCosmoRowChrome(current = isCurrent)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DESKTOP_ROW_HEIGHT)
            .alpha(if (song.isMatchMissing) MISSING_ALPHA else 1f)
            .desktopSongContextMenu(song, enabled = !unresolved)
            .cosmoDesktopRow(chrome, colors, striped = striped, enabled = !unresolved, onClick = onClick)
            .padding(horizontal = DESKTOP_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(DESKTOP_NUMBER_COLUMN), contentAlignment = Alignment.CenterStart) {
            when {
                song.isMatchPending -> CosmoLoadingBlob(Modifier.size(14.dp))
                isCurrent -> CosmoDesktopEqualiser(playing = isPlaying)
                else -> DesktopRowNumber(number)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = type.forText(type.desktopRowTitle(), song.title),
                color = Color(if (isCurrent) palette.accentStrong else palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = type.forText(type.desktopRowSubtitle(), song.artist),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showAlbum) {
            Text(
                text = song.albumName ?: "—",
                // A release's rows have no album to name; a playlist's do, and its names need more than the board's 90 dp.
                modifier = Modifier.weight(0.6f).padding(horizontal = 12.dp),
                style = type.forText(type.desktopRowSubtitle().copy(fontSize = 13.sp), song.albumName.orEmpty()),
                color = Color(palette.textTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!unresolved) {
            CosmoDesktopRowActions(
                song = song,
                reveal = chrome.reveal,
                liked = liked,
                onToggleLike = onToggleLike,
                onDownload = onDownload,
                downloaded = downloaded,
                downloadInProgress = downloadInProgress,
                onAddToPlaylist = onAddToPlaylist,
                onRemove = onRemove,
                reserveRemove = reserveRemove,
            )
        } else {
            Spacer(Modifier.width(DESKTOP_ROW_ACTION * (if (reserveRemove) 4 else 3)))
        }
        Text(
            text = song.durationText ?: "—",
            modifier = Modifier.width(DESKTOP_DURATION_COLUMN),
            style = type.desktopRowDuration(),
            color = Color(palette.textTertiary),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** A Spotify track with no match is drawn at this opacity: the old page's 45 %. */
private const val MISSING_ALPHA = 0.45f

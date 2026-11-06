/*
 * Source: FORK.md companion source record TV-0028
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.playback.PlayerState
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.ui.MainViewModel
import com.cosmotune.music.ui.tv.components.ProvideTvComfortScrolling
import com.cosmotune.music.ui.tv.components.TvActivityIndicator
import com.cosmotune.music.ui.tv.components.TvArtwork
import com.cosmotune.music.ui.tv.components.TvButton
import com.cosmotune.music.ui.tv.components.TvChromeScrollEffect
import com.cosmotune.music.ui.tv.components.TvEmptyState
import com.cosmotune.music.ui.tv.components.TvErrorState
import com.cosmotune.music.ui.tv.components.TvLockup
import com.cosmotune.music.ui.tv.components.TvShelf
import com.cosmotune.music.ui.tv.components.TvShelfTitle
import com.cosmotune.music.ui.tv.components.TvSongRow
import com.cosmotune.music.ui.tv.components.TvColourCard
import com.cosmotune.ui.orbit.coverFill
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.cosmotune.music.ui.tv.dialogs.TvSongMenu
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.TvType
import kotlinx.coroutines.launch

@Composable
fun TvDetailScreen(
    browseId: String,
    initialTitle: String,
    initialSubtitle: String,
    initialThumbnailUrl: String?,
    type: BrowseType,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detailStack by viewModel.detailStack.collectAsState()
    val page = detailStack.lastOrNull { it.browseId == browseId }
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val reopen = { viewModel.openDetail(browseId, initialTitle, initialSubtitle, initialThumbnailUrl, type) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        reopen()
    }

    LaunchedEffect(browseId) { if (page == null) reopen() }

    val play: (List<Song>, Int) -> Unit = { songs, index ->
        scope.launch { mediaController?.playSongs(songs, index) }
        onNavigateToNowPlaying()
    }
    val shuffle: (List<Song>) -> Unit = { songs -> if (songs.isNotEmpty()) play(songs.shuffled(), 0) }

    if ((page?.type ?: type) == BrowseType.ARTIST) {
        TvArtistContent(
            page = page,
            title = page?.title ?: initialTitle,
            artworkUrl = page?.thumbnailUrl ?: initialThumbnailUrl,
            playingId = playerState.song?.videoId,
            isPlaying = playerState.isPlaying,
            onPlay = play,
            onShuffle = shuffle,
            onSongMenu = { menuSong = it },
            onToggleSubscription = { viewModel.toggleSubscription(browseId) },
            onRetry = reopen,
            onNavigateToDetail = onNavigateToDetail,
            modifier = modifier,
        )
    } else {
        TvReleaseContent(
            page = page,
            title = page?.title ?: initialTitle,
            subtitle = page?.subtitle ?: initialSubtitle,
            artworkUrl = page?.thumbnailUrl ?: initialThumbnailUrl,
            type = page?.type ?: type,
            playingId = playerState.song?.videoId,
            isPlaying = playerState.isPlaying,
            onPlay = play,
            onShuffle = shuffle,
            onSongMenu = { menuSong = it },
            onRetry = reopen,
            onRequestPermission = { permissionLauncher.launch(permissions) },
            onNavigateToDetail = onNavigateToDetail,
            modifier = modifier,
        )
    }

    menuSong?.let { song ->
        TvSongMenu(
            song = song,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onDismiss = { menuSong = null },
        )
    }
}

/**
 * An album or a playlist as the Orbit album page: the colour card — what it is
 * and how long it runs, the title, the artist, the blurb, Shuffle, the cover as a
 * disc and Play in the bite — over the tracks as Orbit rows. Albums number their
 * tracks; playlists show each track's own disc. The page is washed from the top
 * in the cover's colour.
 */
@Composable
private fun TvReleaseContent(
    page: DetailPage?,
    title: String,
    subtitle: String,
    artworkUrl: String?,
    type: BrowseType,
    playingId: String?,
    isPlaying: Boolean,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSongMenu: (Song) -> Unit,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val songsState = page?.songs
    val songs = (songsState as? UiState.Success)?.data.orEmpty()
    val suggested = page?.suggestedSongs.orEmpty()
    val numbered = type == BrowseType.ALBUM
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    TvChromeScrollEffect(listState)

    // The cover's colour, washed down from the top of the page.
    val (tint, _) = coverFill(artworkUrl, title)

    ProvideTvComfortScrolling(top = TvDimensions.ContentTop, bottom = 58.dp) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0f to tint.copy(alpha = 0.55f),
                            0.6f to Color.Transparent,
                        ),
                    )
                },
            contentPadding = PaddingValues(top = TvDimensions.ContentTop + 5.dp, bottom = 45.dp),
        ) {
            item(key = "header") {
                TvReleaseHeader(
                    title = title,
                    subtitle = subtitle,
                    artworkUrl = artworkUrl,
                    kind = if (numbered) "Album" else "Playlist",
                    playing = isPlaying && songs.any { it.videoId == playingId },
                    summary = songs.summary(),
                    description = page?.description,
                    canPlay = songs.isNotEmpty(),
                    onPlay = { onPlay(songs, 0) },
                    onShuffle = { onShuffle(songs) },
                    onActionsFocused = { scope.launch { listState.animateScrollToItem(0) } },
                )
            }

            when (songsState) {
                null, UiState.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        TvActivityIndicator()
                    }
                }
                is UiState.Error -> item(key = "error") {
                    if (songsState.message.contains("permission", ignoreCase = true)) {
                        TvEmptyState(
                            title = "Allow Access to Music",
                            message = "Cosmotune needs permission to read music on this TV and on connected USB drives.",
                            modifier = Modifier.height(224.dp),
                            action = { TvButton(text = "Allow Access", onClick = onRequestPermission) },
                        )
                    } else {
                        TvErrorState(message = songsState.message, onRetry = onRetry, modifier = Modifier.height(224.dp))
                    }
                }
                is UiState.Success -> if (songs.isEmpty()) {
                    item(key = "empty") {
                        TvEmptyState(
                            title = "No Songs",
                            message = "There's nothing to play here yet.",
                            modifier = Modifier.height(208.dp),
                        )
                    }
                } else {
                    itemsIndexed(songs, key = { index, song -> "${song.videoId}#$index" }) { index, song ->
                        TvTrackLine(
                            song = song,
                            number = if (numbered) index + 1 else null,
                            isCurrent = song.videoId == playingId,
                            isPlaying = isPlaying,
                            divider = index < songs.lastIndex,
                            onClick = { onPlay(songs, index) },
                            onLongClick = { onSongMenu(song) },
                        )
                    }
                }
            }

            // Tracks YouTube offers to round a playlist out, kept apart from
            // the playlist's own — as the phone does.
            if (suggested.isNotEmpty()) {
                item(key = "suggestedTitle") {
                    TvShelfTitle(title = "Suggestions", modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
                }
                itemsIndexed(suggested, key = { index, song -> "s:${song.videoId}#$index" }) { index, song ->
                    TvTrackLine(
                        song = song,
                        number = null,
                        isCurrent = song.videoId == playingId,
                        isPlaying = isPlaying,
                        divider = index < suggested.lastIndex,
                        onClick = { onPlay(suggested, index) },
                        onLongClick = { onSongMenu(song) },
                    )
                }
            }

            page?.sections?.forEachIndexed { index, shelf ->
                if (shelf.items.isEmpty()) return@forEachIndexed
                item(key = "section#$index") {
                    TvShelf(
                        title = shelf.title,
                        items = shelf.items,
                        key = { it.videoId ?: it.browseId ?: it.title },
                        modifier = Modifier.padding(top = 21.dp),
                    ) { item ->
                        TvLockup(
                            title = item.title,
                            subtitle = item.subtitle,
                            artworkUrl = item.thumbnailUrl,
                            circle = item.browseId?.startsWith("UC") == true,
                            width = 131.dp,
                            onClick = { openShelfItem(item, { song -> onPlay(listOf(song), 0) }, onNavigateToDetail) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The release's colour card: what it is and how long it runs, the title, the artist, the blurb and Shuffle, the cover as a disc,
 * and Play in the bite, which takes focus as the page opens. Focus anywhere on it scrolls the page home.
 */
@Composable
private fun TvReleaseHeader(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    kind: String,
    playing: Boolean,
    summary: String?,
    description: String?,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onActionsFocused: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(canPlay) { if (canPlay) runCatching { playFocus.requestFocus() } }
    TvColourCard(
        cover = artworkUrl,
        key = title,
        label = listOfNotNull(kind, summary).joinToString(" · "),
        title = title,
        subtitle = subtitle,
        playing = playing,
        onPlay = onPlay,
        playEnabled = canPlay,
        playFocus = playFocus,
        ring = false,
        height = 236.dp,
        modifier = Modifier
            .padding(horizontal = TvDimensions.SafeMarginHorizontal)
            .padding(top = 6.dp, bottom = 24.dp)
            .onFocusChanged { if (it.hasFocus) onActionsFocused() },
        below = {
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = TvType.Callout,
                    color = TvGlass.Ink.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            TvShuffleButton(enabled = canPlay, onClick = onShuffle, modifier = Modifier.padding(top = 14.dp))
        },
    )
}

/** A track line: a [TvSongRow] on the safe margin, a small gap under it when another follows. */
@Composable
private fun TvTrackLine(
    song: Song,
    number: Int?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal).padding(bottom = if (divider) 6.dp else 0.dp)) {
        TvSongRow(
            song = song,
            number = number,
            showArtwork = number == null,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

/** "12 songs, 45 minutes" — from the tracks' own durations. */
private fun List<Song>.summary(): String? {
    if (isEmpty()) return null
    val totalSeconds = sumOf { song ->
        song.durationText
            ?.split(":")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.fold(0L) { acc, part -> acc * 60 + part }
            ?: 0L
    }
    val count = if (size == 1) "1 song" else "$size songs"
    val minutes = totalSeconds / 60
    return when {
        totalSeconds <= 0 -> count
        minutes >= 60 -> "$count, ${minutes / 60} hr ${minutes % 60} min"
        else -> "$count, $minutes minutes"
    }
}

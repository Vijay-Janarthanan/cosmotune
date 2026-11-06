/*
 * Source: FORK.md companion source record TV-0030
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.ui.MainViewModel
import com.cosmotune.music.ui.tv.components.TvActivityIndicator
import com.cosmotune.music.ui.tv.components.TvColourCard
import com.cosmotune.music.playback.PlayerState
import com.cosmotune.music.ui.tv.player.formatClock
import com.cosmotune.music.ui.tv.components.TvChromeScrollEffect
import com.cosmotune.music.ui.tv.components.TvEmptyState
import com.cosmotune.music.ui.tv.components.TvErrorState
import com.cosmotune.music.ui.tv.components.ProvideTvFeedScrolling
import com.cosmotune.music.ui.tv.components.TvLockup
import com.cosmotune.music.ui.tv.components.TvShelf
import com.cosmotune.music.ui.tv.components.TvShelfPlaceholder
import com.cosmotune.music.ui.tv.dialogs.TvSongMenu
import com.cosmotune.music.ui.tv.theme.TvDimensions
import kotlinx.coroutines.launch

@Composable
fun TvHomeScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeState by viewModel.home.collectAsState()
    val loadingMore by viewModel.homeLoadingMore.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }

    when (val state = homeState) {
        is UiState.Loading -> TvShelvesLoading(modifier)
        is UiState.Error -> TvErrorState(
            message = state.message,
            onRetry = { viewModel.refresh(MainViewModel.Feed.HOME) },
            modifier = modifier,
        )
        is UiState.Success -> {
            if (state.data.isEmpty()) {
                TvEmptyState(
                    title = "Nothing Here Yet",
                    message = "Sign in from Settings, or check your connection, to see your recommendations.",
                    modifier = modifier,
                )
            } else {
                val onPlaySong: (Song) -> Unit = { song ->
                    scope.launch { mediaController?.playSongs(listOf(song), 0) }
                    onNavigateToNowPlaying()
                }
                TvShelfFeed(
                    shelves = state.data,
                    loadingMore = loadingMore,
                    onNearEnd = { viewModel.loadMoreHome() },
                    onPlaySong = onPlaySong,
                    onSongMenu = { menuSong = it },
                    onNavigateToDetail = onNavigateToDetail,
                    picksFirst = true,
                    playingId = playerState.song?.videoId,
                    modifier = modifier,
                    header = {
                        TvHomeCard(
                            playerState = playerState,
                            first = state.data.firstOrNull()?.items?.firstOrNull(),
                            onOpenNowPlaying = onNavigateToNowPlaying,
                            onTogglePlay = { mediaController?.let { if (it.isPlaying) it.pause() else it.play() } },
                            onOpenItem = { openShelfItem(it, onPlaySong, onNavigateToDetail) },
                        )
                    },
                )
            }
        }
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
 * Home's colour card (board batch-be section E): the song that is playing — "NOW ORBITING · 1:52 / 4:05", its title and artist,
 * its disc with the orbit, play/pause in the bite — or, with nothing playing, the first pick to start from. Select on the card
 * opens Now Playing (or the pick); select on the bite plays or pauses.
 */
@Composable
private fun TvHomeCard(
    playerState: PlayerState,
    first: ShelfItem?,
    onOpenNowPlaying: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenItem: (ShelfItem) -> Unit,
) {
    val song = playerState.song
    val modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal).padding(top = 8.dp, bottom = 26.dp)
    if (song != null) {
        val position = playerState.position
        val duration = playerState.durationMs
        val second by remember(position) { derivedStateOf { position.positionMs / 1000L } }
        val state = if (playerState.isPlaying) "Now orbiting" else "Paused"
        TvColourCard(
            cover = song.thumbnailUrl,
            key = song.videoId,
            label = if (duration > 0) state + " · " + formatClock(second * 1000L) + " / " + formatClock(duration) else state,
            title = song.title,
            subtitle = song.artist,
            playing = playerState.isPlaying,
            onPlay = onTogglePlay,
            onClick = onOpenNowPlaying,
            progress = { if (duration > 0) position.positionMs.toFloat() / duration else null },
            spinning = playerState.isPlaying,
            modifier = modifier,
        )
    } else if (first != null) {
        TvColourCard(
            cover = first.thumbnailUrl,
            key = first.videoId ?: first.browseId ?: first.title,
            label = "Start here",
            title = first.title,
            subtitle = first.subtitle,
            playing = false,
            onPlay = { onOpenItem(first) },
            onClick = { onOpenItem(first) },
            ring = false,
            modifier = modifier,
        )
    }
}

/** Placeholder shelves while a feed loads. */
@Composable
internal fun TvShelvesLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = TvDimensions.ContentTop + 6.dp),
        verticalArrangement = Arrangement.spacedBy(TvDimensions.ShelfSpacing),
    ) {
        TvShelfPlaceholder(width = 160.dp, count = 5)
        TvShelfPlaceholder()
    }
}

/**
 * A vertical run of shelves under the chips — Home, an Explore category, an
 * artist's releases: rows of discs, one row in focus at a time. With
 * [picksFirst] the first shelf is the quick picks, a little smaller and closer
 * together, as the board draws them. [playingId]'s disc wears the orbit.
 */
@Composable
internal fun TvShelfFeed(
    shelves: List<HomeShelf>,
    onPlaySong: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    modifier: Modifier = Modifier,
    picksFirst: Boolean = false,
    loadingMore: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    playingId: String? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    TvChromeScrollEffect(listState)

    if (onNearEnd != null) {
        val nearEnd by remember(listState) {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, shelves.size) { if (nearEnd) onNearEnd() }
    }

    // Where every focused shelf's discs stop: where the first shelf's rest when the
    // page is at its top (ContentTop, a header such as Home's colour card, the
    // shelf title's line, the row's own top padding), so focusing the first shelf
    // keeps the card above it in view. Never so low that a shelf and its captions
    // would not fit under it.
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    val gap = TvDimensions.ShelfSpacing - 11.dp
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val natural = TvDimensions.ContentTop + (if (headerHeight > 0.dp) headerHeight + gap else 0.dp) + 27.dp + 11.dp
    val anchor = natural.coerceAtMost(maxHeight - 230.dp).coerceAtLeast(TvDimensions.ContentTop + 38.dp)
    ProvideTvFeedScrolling(anchor = anchor) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = TvDimensions.ContentTop, bottom = 45.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (header != null) {
            item(key = "header", contentType = "header") {
                Box(Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) { header() }
            }
        }
        itemsIndexed(
            items = shelves,
            key = { index, shelf -> "${shelf.title}#$index" },
            contentType = { index, shelf ->
                when {
                    picksFirst && index == 0 -> "picks"
                    shelf.isArtistShelf() -> "artists"
                    else -> "lockups"
                }
            },
        ) { index, shelf ->
            val onItem: (ShelfItem) -> Unit = { item ->
                openShelfItem(item, onPlaySong, onNavigateToDetail)
            }
            val onItemMenu: (ShelfItem) -> (() -> Unit)? = { item ->
                item.toSong()?.let { song -> { onSongMenu(song) } }
            }
            when {
                picksFirst && index == 0 -> TvShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                    items = shelf.items,
                    key = { it.itemKey() },
                    spacing = 22.dp,
                ) { item ->
                    TvLockup(
                        title = item.title,
                        subtitle = item.subtitle,
                        artworkUrl = item.thumbnailUrl,
                        width = 112.dp,
                        now = item.videoId != null && item.videoId == playingId,
                        onClick = { onItem(item) },
                        onLongClick = onItemMenu(item),
                    )
                }
                shelf.isArtistShelf() -> TvShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                    items = shelf.items,
                    key = { it.itemKey() },
                ) { item ->
                    TvLockup(
                        title = item.title,
                        subtitle = null,
                        artworkUrl = item.thumbnailUrl,
                        circle = true,
                        width = 120.dp,
                        onClick = { onItem(item) },
                    )
                }
                else -> TvShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                    items = shelf.items,
                    key = { it.itemKey() },
                ) { item ->
                    TvLockup(
                        title = item.title,
                        subtitle = item.subtitle,
                        artworkUrl = item.thumbnailUrl,
                        width = 131.dp,
                        now = item.videoId != null && item.videoId == playingId,
                        onClick = { onItem(item) },
                        onLongClick = onItemMenu(item),
                    )
                }
            }
        }
        if (loadingMore) {
            item(key = "more", contentType = "more") {
                Box(modifier = Modifier.fillMaxWidth().padding(19.dp), contentAlignment = Alignment.Center) {
                    TvActivityIndicator()
                }
            }
        }
    }
    }
    }
}

private fun ShelfItem.itemKey(): String = videoId ?: browseId ?: title

private fun HomeShelf.isArtistShelf(): Boolean =
    items.isNotEmpty() && items.count { it.browseId?.startsWith("UC") == true } * 2 > items.size

internal fun ShelfItem.toSong(): Song? = videoId?.let {
    Song(videoId = it, title = title, artist = subtitle, thumbnailUrl = thumbnailUrl)
}

internal fun browseTypeOf(browseId: String): BrowseType = when {
    browseId.startsWith("UC") || browseId.startsWith("FEmusic_artist") -> BrowseType.ARTIST
    browseId.startsWith("MPRE") || browseId.startsWith("FEmusic_album") -> BrowseType.ALBUM
    browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") || browseId == "LM" -> BrowseType.PLAYLIST
    else -> BrowseType.OTHER
}

internal fun openShelfItem(
    item: ShelfItem,
    onPlaySong: (Song) -> Unit,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
) {
    val song = item.toSong()
    val browseId = item.browseId
    when {
        song != null -> onPlaySong(song)
        browseId != null -> onNavigateToDetail(browseId, item.title, item.subtitle, item.thumbnailUrl, browseTypeOf(browseId))
    }
}

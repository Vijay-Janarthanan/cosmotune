package com.cosmotune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import com.cosmotune.design.ArtistPlay
import com.cosmotune.design.ShellMetrics
import com.cosmotune.music.data.model.ArtistPage
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.SubscriptionState
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.desktop.DesktopStrings
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitArtistPage
import com.cosmotune.ui.orbit.OrbitMenuAction
import com.cosmotune.ui.orbit.orbitStat
import androidx.compose.ui.unit.dp

/*
 * An artist in Orbit (Canvas repo, design/cosmotune/pages-orbit/artist.html): the shared [OrbitArtistPage], given this host's page.
 */

/** The desktop's page margin, the same as Home's and the album and playlist page's. */
private val COLUMNS_PADDING = ShellMetrics.DESKTOP_PAGE_MARGIN_DP.dp

/** An artist's top songs, as many as the old page and the phone queue; the page lists the first few and "Show all" the rest. */
private const val POPULAR_QUEUE = 20

/**
 * The artist page. It takes the old page's parameters, in the same order, then [artistId] (the channel, for More's "Copy link") and
 * [onStartRadio] (Radio: a station from the first top song), and at the end [nowPlaying] (the song that is playing: its top-song row
 * is peach with the orbit), [isPlaying] (from [LocalCosmoPlaying]) and [onPlayPause] (from [LocalCosmoPlayPause]). The old page
 * reads the playing song from a local that only its own file can see, so its delegate passes [nowPlaying].
 *
 * While the page loads, the card stands with the name the link knew and a skeleton under it; a failure is a message with Retry. A
 * guest has no [onToggleSubscription] and so no Follow. While the song that is loaded is one of the top songs, the bite is the
 * player's play/pause ([ArtistPlay]), not a restart of the list.
 */
@Composable
internal fun CosmoDesktopArtistPage(
    state: UiState<ArtistPage>,
    fallbackName: String,
    onRetry: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onOpenMenu: (Song) -> Unit,
    onShelfItemClick: (ShelfItem, String?) -> Unit,
    onToggleSubscription: ((SubscriptionState) -> Unit)?,
    contentPadding: PaddingValues,
    artistId: String? = null,
    onStartRadio: ((Song) -> Unit)? = null,
    nowPlaying: Song? = null,
    isPlaying: Boolean = LocalCosmoPlaying.current,
    onPlayPause: (() -> Unit)? = LocalCosmoPlayPause.current,
) {
    val direction = LocalLayoutDirection.current
    Box(
        Modifier.fillMaxSize().padding(
            start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction),
            top = contentPadding.calculateTopPadding(),
        ),
    ) {
        if (state is UiState.Error) {
            MessageState(message = state.message, actionLabel = DesktopStrings["retry", "Retry"], onAction = onRetry)
            return@Box
        }
        val artist = (state as? UiState.Success)?.data
        val name = artist?.name?.takeIf(String::isNotBlank) ?: fallbackName
        val top = remember(artist?.songs) { artist?.songs.orEmpty().take(POPULAR_QUEUE) }
        val subscription = artist?.subscription?.takeIf { onToggleSubscription != null }
        val clipboard = LocalClipboardManager.current
        OrbitArtistPage(
            name = name,
            photo = artist?.thumbnailUrl,
            listeners = artist?.monthlyListenerCount ?: artist?.subscriberCountText,
            description = artist?.description,
            stats = listOfNotNull(orbitStat(artist?.monthlyListenerCount), orbitStat(artist?.subscriberCountText)),
            songs = top,
            sections = artist?.sections.orEmpty(),
            currentSong = nowPlaying,
            isPlaying = isPlaying,
            listState = rememberLazyListState(),
            contentPadding = PaddingValues(top = 16.dp, bottom = contentPadding.calculateBottomPadding()),
            following = subscription?.subscribed,
            onToggleFollow = subscription?.let { s -> onToggleSubscription?.let { toggle -> { toggle(s) } } },
            playing = ArtistPlay.showsPause(top, nowPlaying, isPlaying),
            onPlay = {
                when (ArtistPlay.tap(top, nowPlaying, canToggle = onPlayPause != null)) {
                    ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()
                    ArtistPlay.Tap.PLAY_LIST -> if (top.isNotEmpty()) onPlaySongs(top, 0)
                }
            },
            onShuffle = { onShuffle(top) },
            onRadio = onStartRadio?.let { start -> { top.firstOrNull()?.let(start) } },
            onSongClick = { index -> onPlaySongs(top, index) },
            // Hold or right click: the same menu the dots open.
            onSongLongPress = onOpenMenu,
            onSongMore = onOpenMenu,
            onItemClick = { item, shelf -> onShelfItemClick(item, shelf.title) },
            menu = listOfNotNull(artistId?.takeIf { it.startsWith("UC") }?.let { id ->
                OrbitMenuAction(DesktopStrings["copy_link", "Copy link"], CosmoIcons.Share) {
                    clipboard.setText(AnnotatedString("https://music.youtube.com/channel/$id"))
                }
            }),
            loading = state is UiState.Loading,
            margin = COLUMNS_PADDING,
        )
    }
}

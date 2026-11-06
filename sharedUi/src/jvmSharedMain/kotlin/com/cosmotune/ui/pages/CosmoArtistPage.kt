package com.cosmotune.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.cosmotune.design.ArtistPlay
import com.cosmotune.design.ReleaseMeta
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.HEADER_ART_PX
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryState
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitArtistPage
import com.cosmotune.ui.orbit.OrbitLatestRelease
import com.cosmotune.ui.orbit.OrbitMenuAction
import com.cosmotune.ui.orbit.orbitStat
import com.cosmotune.ui.theme.LocalReducedMotion

/**
 * An artist on the phone, in Orbit ([OrbitArtistPage], Canvas repo design/cosmotune/pages-orbit/artist.html): the artist card in the
 * artist's own colours with the moving portrait, Following, Radio and Shuffle, Play in the bite; top songs; the latest release with
 * its save toggle; the feed's shelves (videos, live performances, albums, singles and EPs, featured on, fans also like); About; the
 * signature. "Show all" on a long shelf opens [CosmoShelfGridPage] in this page's place ([activeShelf] is the root's, which also
 * takes the back button).
 *
 * [heroUrl] is a picture to use instead of the page's own (Apple's is found by the page itself). The counts are shown as [DetailPage]
 * has them: the caller says them in the app's language.
 *
 * [onPlayPause] is the player's own toggle: while the song that is loaded is one of this artist's, the bite reads as pause while the
 * audio plays and a tap toggles the player instead of starting the list over ([ArtistPlay]); without it, or while the player is on
 * something else, a tap plays the list from the top. [onStartRadio] starts a station from the artist's first song; null hides Radio.
 */
@Composable
fun CosmoArtistPage(
    page: DetailPage,
    heroUrl: String?,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
    activeShelf: HomeShelf?,
    onActiveShelfChange: (HomeShelf?) -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onSectionItemLongPress: ((ShelfItem) -> Unit)?,
    onToggleSubscription: (() -> Unit)?,
    releaseLibrary: Map<String, LibraryState>,
    onLoadReleaseLibrary: ((String) -> Unit)?,
    onToggleReleaseLibrary: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    onStartRadio: ((Song) -> Unit)? = null,
    onPlayPause: (() -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    val songs = (page.songs as? UiState.Success)?.data.orEmpty()
    // Whether the latest release's album is already saved is not on the shelf item: it is read off the album once, as soon as the card is known.
    val latest = remember(page.sections) { ReleaseMeta.topRelease(page.sections) }
    val latestId = latest?.browseId
    LaunchedEffect(latestId) { latestId?.let { onLoadReleaseLibrary?.invoke(it) } }

    AnimatedContent(
        targetState = activeShelf,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(220)) togetherWith fadeOut(tween(180))
        },
        label = "artist shelf",
        modifier = modifier.fillMaxSize(),
    ) { shelf ->
        if (shelf == null) {
            val subscription = page.subscription?.takeIf { onToggleSubscription != null }
            val clipboard = LocalClipboardManager.current
            val channel = page.browseId.takeIf { it.startsWith("UC") }
            OrbitArtistPage(
                name = page.title,
                photo = heroUrl ?: page.thumbnailUrl.artworkAt(HEADER_ART_PX),
                listeners = page.monthlyListenerCount ?: page.subscriberCountText,
                description = page.description,
                stats = listOfNotNull(orbitStat(page.monthlyListenerCount), orbitStat(page.subscriberCountText)),
                songs = songs,
                sections = page.sections,
                currentSong = currentSong,
                isPlaying = isPlaying,
                listState = listState,
                contentPadding = contentPadding,
                following = subscription?.subscribed,
                onToggleFollow = onToggleSubscription.takeIf { subscription != null },
                // The bite is a pause button while one of this artist's songs is the one playing.
                playing = ArtistPlay.showsPause(songs, currentSong, isPlaying),
                onPlay = {
                    when (ArtistPlay.tap(songs, currentSong, canToggle = onPlayPause != null)) {
                        ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()
                        ArtistPlay.Tap.PLAY_LIST -> if (songs.isNotEmpty()) onSongClick(songs, 0)
                    }
                },
                onShuffle = { onShuffle(songs) },
                onRadio = onStartRadio?.let { start -> { songs.firstOrNull()?.let(start) } },
                onSongClick = { index -> onSongClick(songs, index) },
                onSongLongPress = onSongLongPress,
                onSongSwipe = onSongSwipe,
                onItemClick = { item, _ -> onSectionItemClick(item) },
                onItemLongPress = onSectionItemLongPress,
                onShowAll = onActiveShelfChange,
                menu = listOfNotNull(channel?.let { id ->
                    OrbitMenuAction("Copy link", CosmoIcons.Share) { clipboard.setText(AnnotatedString("https://music.youtube.com/channel/$id")) }
                }),
                afterTopSongs = latest?.let { item ->
                    {
                        OrbitLatestRelease(
                            item = item,
                            year = ReleaseMeta.releaseYear(item),
                            saved = item.browseId?.let { releaseLibrary[it]?.saved },
                            onClick = { onSectionItemClick(item) },
                            onLongClick = onSectionItemLongPress?.let { held -> { held(item) } },
                            onToggleSaved = onToggleReleaseLibrary?.let { toggle -> item.browseId?.let { id -> { toggle(id) } } },
                        )
                    }
                },
                loading = page.songs is UiState.Loading,
                error = (page.songs as? UiState.Error)?.message,
            )
        } else {
            CosmoShelfGridPage(
                shelf = shelf,
                onItemClick = onSectionItemClick,
                onItemLongPress = onSectionItemLongPress,
                contentPadding = contentPadding,
            )
        }
    }
}

package com.cosmotune.desktop

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ArtistPlay
import com.cosmotune.design.ShellMetrics
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.desktop.DesktopArtwork
import com.music.bitchord.desktop.DesktopStrings
import com.music.bitchord.desktop.browseTypeOf
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.cosmo_about
import com.music.bitchord.sharedui.resources.cosmo_follow
import com.music.bitchord.sharedui.resources.cosmo_following
import com.music.bitchord.sharedui.resources.cosmo_popular
import com.music.bitchord.sharedui.resources.cosmo_state_empty
import com.music.bitchord.sharedui.resources.show_all
import com.music.bitchord.ui.components.MessageState
import org.jetbrains.compose.resources.stringResource

/*
 * An artist in the new look (`pageArtistDesktop`, `screens-more-a.js:177-181`): a banner with the name across its foot, the actions,
 * Popular beside About, and the shelves of what they have released.
 */

/** `pageArtistDesktop`: the banner is 300 dp tall, its words 32 dp in from the left and 22 dp up from the foot. */
private val BANNER_HEIGHT = 300.dp
private val BANNER_TEXT_START = 32.dp
private val BANNER_TEXT_BOTTOM = 22.dp

/** The banner's picture is clear down to 30 % of its height, then fades into the page (`transparent 30%`). */
private const val BANNER_FADE_FROM = 0.3f

/**
 * The two columns' padding and gap, and their widths: 1.4 to 1. The padding is the desktop's page margin, the same as Home's and the
 * album and playlist page's (the board's 32 is the older one; `.d2-main` and `.dh-bento` say 28).
 */
private val COLUMNS_PADDING = ShellMetrics.DESKTOP_PAGE_MARGIN_DP.dp
private val COLUMNS_GAP = 28.dp
private const val POPULAR_WEIGHT = 1.4f
private const val ABOUT_WEIGHT = 1f

/** An artist's top songs, as many as the old page and the phone queue; the page lists the first few and "Show all" the rest. */
private const val POPULAR_QUEUE = 20
private const val POPULAR_SHOWN = 5

/** The cards of the shelves are 150 dp wide. */
private val SHELF_CARD = 150.dp

/**
 * The artist page of the new look. It takes the old page's parameters, in the same order, and two more at the end: [nowPlaying] (the
 * song that is playing: its row in Popular is washed and shows the bars) and [isPlaying] (the bars move; from [LocalCosmoPlaying],
 * which the root fills with the player's own flag). The old page reads the playing song from a local that only its own file can see,
 * so its delegate passes [nowPlaying] and leaves [isPlaying] at its default.
 *
 * A guest has no [onToggleSubscription] and so no Follow button. There is no More button: the old page had no menu for an artist and
 * nothing here could fill one.
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
        when (state) {
            UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CosmoLoadingBlob(Modifier.size(48.dp))
            }
            is UiState.Error -> MessageState(
                message = state.message,
                actionLabel = DesktopStrings["retry", "Retry"],
                onAction = onRetry,
            )
            is UiState.Success -> ArtistContent(
                artist = state.data,
                fallbackName = fallbackName,
                bottomInset = contentPadding.calculateBottomPadding(),
                onPlaySongs = onPlaySongs,
                onShuffle = onShuffle,
                onOpenMenu = onOpenMenu,
                onShelfItemClick = onShelfItemClick,
                onToggleSubscription = onToggleSubscription,
                nowPlaying = nowPlaying,
                isPlaying = isPlaying,
                onPlayPause = onPlayPause,
            )
        }
    }
}

@Composable
private fun ArtistContent(
    artist: ArtistPage,
    fallbackName: String,
    bottomInset: Dp,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onOpenMenu: (Song) -> Unit,
    onShelfItemClick: (ShelfItem, String?) -> Unit,
    onToggleSubscription: ((SubscriptionState) -> Unit)?,
    nowPlaying: Song?,
    isPlaying: Boolean,
    onPlayPause: (() -> Unit)?,
) {
    val name = artist.name?.takeIf(String::isNotBlank) ?: fallbackName
    val top = remember(artist.songs) { artist.songs.take(POPULAR_QUEUE) }
    val blurb = artist.description?.takeIf(String::isNotBlank)
    val listeners = listOfNotNull(artist.monthlyListenerCount, artist.subscriberCountText)
        .filter(String::isNotBlank)
        .joinToString(" · ")
        .takeIf(String::isNotBlank)
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp + bottomInset)) {
        item(key = "artist-banner") { ArtistBanner(artist.thumbnailUrl, name, listeners) }
        item(key = "artist-actions") {
            ArtistActions(
                top = top,
                subscription = artist.subscription,
                nowPlaying = nowPlaying,
                isPlaying = isPlaying,
                onPlaySongs = onPlaySongs,
                onShuffle = onShuffle,
                onToggleSubscription = onToggleSubscription,
                onPlayPause = onPlayPause,
            )
        }
        if (top.isNotEmpty() || blurb != null) {
            item(key = "artist-columns") {
                Row(
                    Modifier.fillMaxWidth().padding(start = COLUMNS_PADDING, end = COLUMNS_PADDING, top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(COLUMNS_GAP),
                ) {
                    if (top.isNotEmpty()) {
                        Popular(
                            top = top,
                            modifier = Modifier.weight(if (blurb != null) POPULAR_WEIGHT else 1f),
                            nowPlaying = nowPlaying,
                            isPlaying = isPlaying,
                            onPlaySongs = onPlaySongs,
                            onOpenMenu = onOpenMenu,
                        )
                    }
                    if (blurb != null) About(blurb, Modifier.weight(ABOUT_WEIGHT))
                }
            }
        }
        itemsIndexed(artist.sections, key = { index, shelf -> "artist-shelf-$index-${shelf.title}" }) { _, shelf ->
            // An artists' shelf ("Fans might also like") is round, as the phone's is; the rest are covers.
            val artists = shelf.items.isNotEmpty() && shelf.items.all { it.videoId == null && browseTypeOf(it.browseId.orEmpty()) == BrowseType.ARTIST }
            CosmoShelf(
                title = shelf.title,
                items = shelf.items,
                onItemClick = { onShelfItemClick(it, shelf.title) },
                // Inset to the columns' edge: the shelf's own margin is the phone's 16.
                modifier = Modifier.padding(horizontal = COLUMNS_PADDING - 16.dp),
                subtitle = shelf.subtitle.takeIf(String::isNotBlank),
                cardWidth = SHELF_CARD,
                circles = artists,
                currentSong = nowPlaying,
                isPlaying = isPlaying,
            )
        }
        if (top.isEmpty() && artist.sections.isEmpty() && blurb == null) {
            item(key = "artist-empty") {
                MessageState(message = stringResource(SharedRes.string.cosmo_state_empty))
            }
        }
    }
}

/**
 * The banner (`pageArtistDesktop`): the picture 300 dp tall, clear down to 30 % of its height and then fading into the page, with the
 * listeners' line and the name, in `display(DISPLAY_XL)`, at its foot.
 *
 * The board fades the picture to the page's colour. The page's ground is a vertical gradient that darkens down the window and the
 * banner scrolls over it, so painting one fixed colour over the picture's foot (`backgroundTop`) left a step against the ground
 * under it (30, 47, 29 over 20, 33, 20 in a render). The fade is therefore an alpha mask on the picture, and what shows through
 * is the ground itself, wherever the banner is.
 */
@Composable
private fun ArtistBanner(url: String?, name: String, listeners: String?) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Box(Modifier.fillMaxWidth().height(BANNER_HEIGHT)) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(BANNER_FADE_MASK, blendMode = BlendMode.DstIn)
                },
        ) {
            if (url != null) {
                DesktopArtwork(url, Modifier.fillMaxSize(), px = HEADER_ART_PX)
            } else {
                Box(Modifier.fillMaxSize().drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) })
            }
        }
        BannerWords(name, listeners, Modifier.align(Alignment.BottomStart), palette.textPrimary, type)
    }
}

/** What the fade keeps of the picture: all of it down to [BANNER_FADE_FROM] of the height, then less and less, none at the foot. Only its alpha is used. */
private val BANNER_FADE_MASK = Brush.verticalGradient(0f to Color.Black, BANNER_FADE_FROM to Color.Black, 1f to Color.Transparent)

@Composable
private fun BannerWords(name: String, listeners: String?, modifier: Modifier, ink: Int, type: CosmoType) {
    Column(modifier.padding(start = BANNER_TEXT_START, end = BANNER_TEXT_START, bottom = BANNER_TEXT_BOTTOM)) {
        if (listeners != null) {
            Text(
                text = listeners,
                style = type.forText(type.body(TypeScale.BODY, FontWeight.Normal).copy(fontSize = 13.sp, lineHeight = 13.sp * 1.4f), listeners),
                color = Color(ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = name,
            style = type.forText(type.display(TypeScale.DISPLAY_XL), name),
            color = Color(ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The actions under the banner: Play (the hero button at 56 dp), Shuffle, and Follow or Following when the account can. While the song
 * that is loaded is one of [top], Play is the player's play/pause ([onPlayPause]: pause while it plays, a tap toggles) and not a
 * restart of the list ([ArtistPlay]).
 */
@Composable
private fun ArtistActions(
    top: List<Song>,
    subscription: SubscriptionState?,
    nowPlaying: Song?,
    isPlaying: Boolean,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onToggleSubscription: ((SubscriptionState) -> Unit)?,
    onPlayPause: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = COLUMNS_PADDING, end = COLUMNS_PADDING, top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayMorphButton(
            playing = ArtistPlay.showsPause(top, nowPlaying, isPlaying),
            loading = false,
            onClick = {
                when (ArtistPlay.tap(top, nowPlaying, canToggle = onPlayPause != null)) {
                    ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()
                    ArtistPlay.Tap.PLAY_LIST -> if (top.isNotEmpty()) onPlaySongs(top, 0)
                }
            },
            size = 56.dp,
        )
        CosmoIconButton(
            icon = CosmoIcons.Shuffle,
            contentDescription = DesktopStrings["shuffle", "Shuffle"],
            onClick = { onShuffle(top) },
            style = CosmoIconButtonStyle.TONAL,
        )
        if (subscription != null && onToggleSubscription != null) {
            CosmoButton(
                text = stringResource(if (subscription.subscribed) SharedRes.string.cosmo_following else SharedRes.string.cosmo_follow),
                onClick = { onToggleSubscription(subscription) },
                kind = CosmoButtonKind.TONAL,
                size = CosmoButtonSize.SMALL,
                icon = if (subscription.subscribed) CosmoIcons.Check else null,
            )
        }
    }
}

/** Popular: the first rows of the top songs, numbered, each with its own more button; the rest behind "Show all". */
@Composable
private fun Popular(
    top: List<Song>,
    modifier: Modifier,
    nowPlaying: Song?,
    isPlaying: Boolean,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenMenu: (Song) -> Unit,
) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    var showAll by remember(top) { mutableStateOf(false) }
    val shown = if (showAll || top.size <= POPULAR_SHOWN) top else top.take(POPULAR_SHOWN)
    Column(modifier) {
        Text(
            text = stringResource(SharedRes.string.cosmo_popular),
            style = type.title(TypeScale.TITLE_L),
            color = Color(colors.palette.textPrimary),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Column {
            shown.forEachIndexed { index, song ->
                key(song.videoId, index) {
                    PopularRow(song, index, top, nowPlaying, isPlaying, onPlaySongs, onOpenMenu)
                }
            }
        }
        if (top.size > POPULAR_SHOWN && !showAll) {
            CosmoButton(
                text = stringResource(SharedRes.string.show_all),
                onClick = { showAll = true },
                modifier = Modifier.padding(top = 4.dp),
                kind = CosmoButtonKind.TEXT,
                size = CosmoButtonSize.SMALL,
            )
        }
    }
}

@Composable
private fun PopularRow(
    song: Song,
    index: Int,
    top: List<Song>,
    nowPlaying: Song?,
    isPlaying: Boolean,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenMenu: (Song) -> Unit,
) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    CosmoListRow(
        title = song.title,
        onClick = { onPlaySongs(top, index) },
        subtitle = song.albumName?.takeIf(String::isNotBlank) ?: song.artist.takeIf(String::isNotBlank),
        leading = CosmoRowLeading.Number(index + 1),
        isCurrent = song.isSameTrackAs(nowPlaying),
        isPlaying = isPlaying,
        // Hold or right click: the same menu the dots open.
        onLongPress = { onOpenMenu(song) },
        trailing = {
            if (song.durationText != null) {
                Text(
                    text = song.durationText.orEmpty(),
                    style = type.desktopRowDuration(),
                    color = Color(colors.palette.textTertiary),
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            CosmoIconButton(
                icon = CosmoIcons.MoreVertical,
                contentDescription = DesktopStrings["more", "More"],
                onClick = { onOpenMenu(song) },
                size = 40.dp,
            )
        },
    )
}

/** About: the artist's own words, 14 sp in `textSecondary` on lines 1.6 tall. */
@Composable
private fun About(blurb: String, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    Column(modifier) {
        Text(
            text = stringResource(SharedRes.string.cosmo_about),
            style = type.title(TypeScale.TITLE_L),
            color = Color(colors.palette.textPrimary),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            text = blurb,
            style = type.forText(
                TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 14.sp * 1.6f),
                blurb,
            ),
            color = Color(colors.palette.textSecondary),
        )
    }
}

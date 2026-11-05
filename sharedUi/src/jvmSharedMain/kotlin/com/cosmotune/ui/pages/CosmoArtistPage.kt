package com.cosmotune.ui.pages

import com.cosmotune.music.sharedui.resources.play
import com.cosmotune.music.sharedui.resources.pause
import com.cosmotune.ui.kit.KitPlayCircle
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitActionRow
import com.cosmotune.ui.blocks.CosmoSectionHeader
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.design.ArtistPlay
import com.cosmotune.design.ReleaseMeta
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.blocks.CosmoSongRow
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.tabular
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.HEADER_ART_PX
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryState
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.SubscriptionState
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_follow
import com.cosmotune.music.sharedui.resources.cosmo_following
import com.cosmotune.music.sharedui.resources.cosmo_latest_release
import com.cosmotune.music.sharedui.resources.cosmo_popular
import com.cosmotune.music.sharedui.resources.cosmo_save
import com.cosmotune.music.sharedui.resources.cosmo_saved
import com.cosmotune.music.sharedui.resources.cosmo_shuffle
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import org.jetbrains.compose.resources.stringResource

/**
 * An artist (DESIGN_SYSTEM.md 6.1): one list on the page's ground. From the top: the hero, 280 dp of the artist's picture ([heroUrl],
 * Apple's, when it was found, else the page's own), full bleed, fading to the ground over its bottom 40 %, with the name and the
 * listener count on the fade; Follow, Shuffle and the play circle; Popular (five songs); the latest release as a row with its save toggle; a shelf for every group of releases and related artists ("Show all" opens [CosmoShelfGridPage]
 * in this page's place: [activeShelf] is the root's, which also takes the back button); and About with the artist's counts.
 *
 * The banner runs under the bar (only the bottom of [contentPadding] is the list's). The counts are shown as [DetailPage] has them:
 * the caller says them in the app's language.
 *
 * [onPlayPause] is the player's own toggle: while the song that is loaded is one of this artist's, the big play button reads as pause
 * while the audio plays and a tap toggles the player instead of starting the list over ([ArtistPlay]); without it, or while the
 * player is on something else, a tap plays the list from the top.
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
            ArtistList(
                page = page,
                songs = songs,
                heroUrl = heroUrl,
                latest = latest,
                currentSong = currentSong,
                isPlaying = isPlaying,
                listState = listState,
                contentPadding = contentPadding,
                onActiveShelfChange = onActiveShelfChange,
                onSongClick = onSongClick,
                onSongLongPress = onSongLongPress,
                onSongSwipe = onSongSwipe,
                onShuffle = onShuffle,
                onSectionItemClick = onSectionItemClick,
                onSectionItemLongPress = onSectionItemLongPress,
                onToggleSubscription = onToggleSubscription,
                releaseLibrary = releaseLibrary,
                onToggleReleaseLibrary = onToggleReleaseLibrary,
                onPlayPause = onPlayPause,
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

@Composable
private fun ArtistList(
    page: DetailPage,
    songs: List<Song>,
    heroUrl: String?,
    latest: ShelfItem?,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onActiveShelfChange: (HomeShelf?) -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onSectionItemLongPress: ((ShelfItem) -> Unit)?,
    onToggleSubscription: (() -> Unit)?,
    releaseLibrary: Map<String, LibraryState>,
    onToggleReleaseLibrary: ((String) -> Unit)?,
    onPlayPause: (() -> Unit)?,
) {
    val popular = remember(songs) { songs.take(PageMetrics.POPULAR_COUNT) }
    val subscription = page.subscription?.takeIf { onToggleSubscription != null }
    // The play button is a pause button while one of this artist's songs is the one playing.
    val artistPlaying = ArtistPlay.showsPause(songs, currentSong, isPlaying)
    val description = page.description?.takeIf { it.isNotBlank() }
    val hasStats = page.subscriberCountText != null || page.monthlyListenerCount != null
    val visuals = rememberArtistVisuals(page.title)

    // The banner runs up under the bar, so only the foot of the caller's padding is the list's.
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
    ) {
        item(key = "banner", contentType = "banner") {
            ArtistBanner(
                name = page.title,
                imageUrl = heroUrl ?: page.thumbnailUrl.artworkAt(HEADER_ART_PX),
                line = page.monthlyListenerCount ?: page.subscriberCountText,
                visuals = visuals,
            )
        }

        when (val state = page.songs) {
            is UiState.Loading -> pageSkeleton("skeleton:artist", rowDp = PageMetrics.ROW_DP)
            is UiState.Error -> item(key = "error") { MessageState(state.message) }
            is UiState.Success -> {
                if (songs.isNotEmpty() || subscription != null) {
                    item(key = "actions", contentType = "actions") {
                        ArtistActions(
                            subscription = subscription,
                            onToggleSubscription = onToggleSubscription,
                            hasSongs = songs.isNotEmpty(),
                            playing = artistPlaying,
                            onShuffle = { onShuffle(songs) },
                            onPlay = {
                                // On one of the artist's own songs the button is the player's play/pause; elsewhere it plays the list.
                                when (ArtistPlay.tap(songs, currentSong, canToggle = onPlayPause != null)) {
                                    ArtistPlay.Tap.TOGGLE -> onPlayPause?.invoke()
                                    ArtistPlay.Tap.PLAY_LIST -> onSongClick(songs, 0)
                                }
                            },
                        )
                    }
                }
                if (popular.isNotEmpty()) {
                    item(key = "popular-heading", contentType = "heading") { CosmoSectionHeader(stringResource(Res.string.cosmo_popular), Modifier.padding(top = 16.dp)) }
                    val isCurrentRow = playingRowMatcher(popular, currentSong)
                    itemsIndexed(
                        items = popular,
                        key = { index, song -> "popular-$index-${song.videoId}" },
                        contentType = { _, _ -> "popular" },
                    ) { index, song ->
                        val isCurrent = isCurrentRow(song)
                        CosmoSongRow(
                            song = song,
                            onClick = { onSongClick(songs, index) },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            trackNumber = index + 1,
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && isPlaying,
                            // The catalogue gives no play counts, so the end of the row stays the duration.
                            trailingText = null,
                        )
                    }
                }
            }
        }

        if (latest != null) {
            item(key = "latest-release", contentType = "latest") {
                val saved = latest.browseId?.let { releaseLibrary[it]?.saved }
                LatestReleaseTile(
                    item = latest,
                    year = ReleaseMeta.releaseYear(latest),
                    saved = saved,
                    onClick = { onSectionItemClick(latest) },
                    onLongPress = onSectionItemLongPress?.let { held -> { held(latest) } },
                    onToggleSaved = onToggleReleaseLibrary?.let { toggle -> latest.browseId?.let { id -> { toggle(id) } } },
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        // Albums, singles and EPs, videos and related artists: a shelf for each group YouTube sends.
        itemsIndexed(
            items = page.sections,
            key = { index, shelf -> "shelf-$index-${shelf.title}" },
            contentType = { _, _ -> "shelf" },
        ) { _, shelf ->
            // A long shelf shows its first cards and "Show all" (`DetailScreen.kt`: more than ARTIST_ROW_MAX_ITEMS cards).
            val canShowAll = shelf.items.size > PageMetrics.SHELF_PREVIEW_ITEMS
            val shown = remember(shelf.items) { if (canShowAll) shelf.items.take(PageMetrics.SHELF_PREVIEW_ITEMS) else shelf.items }
            CosmoShelf(
                title = shelf.title,
                items = shown,
                onItemClick = onSectionItemClick,
                onItemLongPress = onSectionItemLongPress,
                onShowAll = if (canShowAll) { { onActiveShelfChange(shelf) } } else null,
                circles = shelf.isArtists(),
            )
        }

        if (description != null || hasStats) {
            item(key = "about", contentType = "about") {
                AboutTile(
                    text = description,
                    modifier = Modifier.padding(top = 28.dp),
                    stats = if (hasStats) {
                        { StatPills(subscribers = page.subscriberCountText, listeners = page.monthlyListenerCount) }
                    } else null,
                )
            }
        }
        if (visuals.signature != null) {
            item(key = "artist-signature") {
                ArtistSignatureMark(visuals.signature, page.title, Modifier.padding(20.dp))
            }
        }
    }
}

/**
 * The hero: [PageMetrics.BANNER_DP] dp of the picture, full bleed, a fade over it and the name at the foot in `headline`, [line] under
 * the name. The fade runs from nothing at 60 % of the height to the ground at the foot, so the picture melts into the page.
 */
@Composable
private fun ArtistBanner(name: String, imageUrl: String?, line: String?, visuals: ArtistVisuals) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Box(Modifier.fillMaxWidth().height(PageMetrics.BANNER_DP.dp).drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) }) {
        ArtistMotionArtwork(visuals.art, imageUrl, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().drawBehind { drawRect(bannerScrim(colors)) })
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = PageMetrics.BANNER_NAME_START_DP.dp, end = CosmoSpacing.margin, bottom = PageMetrics.BANNER_NAME_BOTTOM_DP.dp),
        ) {
            Text(
                text = name,
                style = type.forText(type.headline, name),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!line.isNullOrBlank()) {
                Text(
                    text = line,
                    style = type.forText(type.meta.tabular(), line),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The fade of the hero: clear down to 60 % of the height, the page's ground at the foot. Read while drawing. */
private fun DrawScope.bannerScrim(c: CosmoColors): Brush {
    val ground = c.live(CoverField.BACKGROUND_TOP)
    return Brush.verticalGradient(
        0f to ground.copy(alpha = 0f),
        PageMetrics.BANNER_SCRIM_CLEAR_AT to ground.copy(alpha = 0f),
        1f to ground,
    )
}

/** Follow (a dense tonal pill) and Shuffle at the start; the play circle, the page's one primary action, at the end. */
@Composable
private fun ArtistActions(
    subscription: SubscriptionState?,
    onToggleSubscription: (() -> Unit)?,
    hasSongs: Boolean,
    playing: Boolean,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = CosmoSpacing.margin, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitActionRow(Modifier.weight(1f), contentPadding = PaddingValues(start = CosmoSpacing.margin, end = 8.dp)) {
            if (subscription != null && onToggleSubscription != null) {
                KitButton(
                    text = stringResource(if (subscription.subscribed) Res.string.cosmo_following else Res.string.cosmo_follow),
                    onClick = onToggleSubscription,
                    kind = KitButtonKind.TONAL,
                    dense = true,
                )
            }
            if (hasSongs) {
                KitButton(stringResource(Res.string.cosmo_shuffle), onShuffle, kind = KitButtonKind.TONAL, icon = CosmoIcons.Shuffle, dense = true)
            }
        }
        if (hasSongs) {
            KitPlayCircle(
                label = stringResource(if (playing) Res.string.pause else Res.string.play),
                onClick = onPlay,
                icon = if (playing) CosmoIcons.Pause else CosmoIcons.Play,
            )
        }
    }
}

/**
 * The latest release as a row on the page: its cover (64 dp), "LATEST RELEASE", the title and the year, and a save toggle at the end
 * when the album's state is known ([saved] not null) and saving is offered. The row opens the release; the toggle is a control of its
 * own beside it, not inside it.
 */
@Composable
private fun LatestReleaseTile(
    item: ShelfItem,
    year: Int?,
    saved: Boolean?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    onToggleSaved: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val overline = stringResource(Res.string.cosmo_latest_release)
    Row(
        modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(CosmoRadii.rSm))
                .semantics(mergeDescendants = true) { role = Role.Button }
                .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
                .contextClick(onLongPress),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(url = item.thumbnailUrl, size = PageMetrics.LATEST_COVER_DP.dp, radius = CosmoRadii.rSm, shadow = false)
            Column(Modifier.weight(1f)) {
                Text(
                    text = overline.uppercase(),
                    style = type.forText(type.overline, overline),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.title,
                    style = type.forText(type.rowTitle, item.title),
                    color = Color(palette.textPrimary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (year != null) {
                    Text(text = year.toString(), style = type.meta.tabular(), color = Color(palette.textSecondary))
                }
            }
        }
        if (saved != null && onToggleSaved != null) {
            KitIconButton(
                icon = if (saved) CosmoIcons.HeartFill else CosmoIcons.Heart,
                label = stringResource(if (saved) Res.string.cosmo_saved else Res.string.cosmo_save),
                onClick = onToggleSaved,
                on = saved,
            )
        }
    }
}

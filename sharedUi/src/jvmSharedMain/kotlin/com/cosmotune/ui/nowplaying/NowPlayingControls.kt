package com.cosmotune.ui.nowplaying

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cosmotune.design.LandscapeMetrics
import com.cosmotune.design.NowPlayingMetrics
import com.cosmotune.design.Springs
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.components.WaveScrubber
import com.cosmotune.ui.modes.LocalModeLinks
import com.cosmotune.ui.modes.musicVideoLink
import com.cosmotune.ui.shell.LocalCosmoPlayerDismiss
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.tabular
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.BACK_RESTARTS_AFTER_MS
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.audio_output
import com.cosmotune.music.sharedui.resources.cosmo_collapse_player
import com.cosmotune.music.sharedui.resources.cosmo_playing_from
import com.cosmotune.music.sharedui.resources.cosmo_video_hide
import com.cosmotune.music.sharedui.resources.cosmo_video_show
import com.cosmotune.music.sharedui.resources.cosmo_watch_music_video
import com.cosmotune.music.sharedui.resources.like
import com.cosmotune.music.sharedui.resources.listen_together
import com.cosmotune.music.sharedui.resources.more
import com.cosmotune.music.sharedui.resources.open_lyrics
import com.cosmotune.music.sharedui.resources.played_by
import com.cosmotune.music.sharedui.resources.playing_radio
import com.cosmotune.music.sharedui.resources.queue
import com.cosmotune.music.sharedui.resources.remove_from_liked
import com.cosmotune.music.sharedui.resources.repeat_all
import com.cosmotune.music.sharedui.resources.repeat_off
import com.cosmotune.music.sharedui.resources.repeat_one
import com.cosmotune.music.sharedui.resources.shuffle_off
import com.cosmotune.music.sharedui.resources.shuffle_on
import com.cosmotune.music.sharedui.resources.widget_next
import com.cosmotune.music.sharedui.resources.widget_previous
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerScrub
import com.cosmotune.music.ui.player.RepeatModes
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * The top bar (`.np2-bar`): 12 dp from the sides and 4 dp under the status bar, 6 dp between its four parts. From the left a
 * glass button that closes the player, "Playing from" over the source (tap to go back there), the video button and More.
 * The glass buttons are the solid scrim tint of the glass recipe and never a blur: the video cannot be sampled cheaply.
 *
 * While [blockedByMobileData] the host's one-line "Video is paused on mobile data" with its Allow button sits under the bar,
 * 16 dp from the left, where the board's video tag sits.
 */
@Composable
internal fun NowPlayingTopBar(
    song: Song,
    playedBy: String?,
    hasVideo: Boolean,
    videoOn: Boolean,
    blockedByMobileData: Boolean,
    onToggleVideo: () -> Unit,
    onOpenOrigin: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalCosmoPlayerDismiss.current
    com.cosmotune.ui.shell.WindowCaptionArea(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(com.cosmotune.music.ui.theme.stableStatusBarInsets()).padding(top = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CosmoIconButton(
                    CosmoIcons.ChevronDown, stringResource(Res.string.cosmo_collapse_player), dismiss,
                    style = CosmoIconButtonStyle.GLASS, size = 40.dp,
                )
                PlayingFrom(song, playedBy, onOpenOrigin, Modifier.weight(1f))
                CosmoIconButton(
                    if (videoOn) CosmoIcons.Canvas else CosmoIcons.CanvasOff,
                    stringResource(if (videoOn) Res.string.cosmo_video_hide else Res.string.cosmo_video_show),
                    onToggleVideo,
                    style = CosmoIconButtonStyle.GLASS, size = 40.dp, on = videoOn, enabled = hasVideo,
                )
                CosmoIconButton(CosmoIcons.More, stringResource(Res.string.more), onOpenMenu, style = CosmoIconButtonStyle.GLASS, size = 40.dp)
            }
            if (blockedByMobileData) {
                Box(Modifier.padding(start = 16.dp, top = 8.dp)) {
                    PlayerPlatform.host.CanvasMobileDataHint(Modifier)
                }
            }
        }
    }
}

/**
 * "Playing from" (12 sp, secondary ink) over the source (14 sp, weight 700): the song's own source, else its album, else
 * the queue. A track someone queued in a party and a radio station say so in one line instead, in the words the old caption
 * used. The whole block is one tap target, 48 dp tall.
 */
@Composable
private fun PlayingFrom(song: Song, playedBy: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val whole: String? = playedBy?.let { stringResource(Res.string.played_by, it) }
        ?: song.radioName?.let { stringResource(Res.string.playing_radio, it) }
    val source = song.playbackSource ?: song.albumName ?: stringResource(Res.string.queue)
    val small = remember(type) { TextStyle(fontFamily = type.textFamily, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight(500)) }
    val big = remember(type) { TextStyle(fontFamily = type.textFamily, fontSize = 14.sp, lineHeight = 17.5.sp, fontWeight = FontWeight.Bold) }
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(CosmoRadii.sm)).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (whole == null) {
            Text(stringResource(Res.string.cosmo_playing_from), style = small, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Text(whole ?: source, style = big, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** The top bar's bottom edge below the status bar: 4 dp of air and the 48 dp tall row. The lyrics and the queue start under it. */
internal const val TOP_BAR_BELOW_STATUS_DP = 4 + 48

/**
 * The stack at the foot of the page (`.np2-stack`): 20 dp from the sides, 22 dp above the navigation bar. From the top the
 * title, the artist with the like and More buttons, the wavy scrubber with the two times, the transport and the four tools.
 *
 * Nothing here reads the playhead except [Times] (twice a second, alone). [reveal] opens the chip slot beside the artist;
 * [onChipPlaced] reports where its top-left corner is, in root coordinates, for the cover to land on.
 *
 * The panels: with [lyricsOpen] the title steps down to `title(TITLE_L)` (`TITLE_M` when long) and the page draws the lyrics
 * above the stack; with [queueOpen] the title, the artist and the scrubber fold away and the page draws the queue's card over
 * the room they leave, so the stack is the transport and the tools. In a [landscape] window the stack fills the height it is
 * given: the title (one line) and the artist take the room above the scrubber, and [panel] (the lyrics or the queue) takes it
 * in their place, the scrubber folding away with them; the transport and the tools stay at the foot, the play button 64 dp.
 *
 * [onWatchMusicVideo] ([rememberWatchMusicVideo]) adds "Watch the music video" to the tools when Video mode can play the song's
 * music video; null leaves the four tools as they are.
 */
@Composable
internal fun NowPlayingStack(
    song: Song,
    coverUrl: String?,
    hasVideo: Boolean,
    reveal: State<Float>,
    onChipPlaced: (Offset) -> Unit,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    scrub: PlayerScrub,
    onSeekFraction: (Float) -> Unit,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    controlsLocked: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBlockedControl: () -> Unit,
    onPlayPause: () -> Unit,
    shuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    repeatMode: Int,
    onCycleRepeat: () -> Unit,
    lyricsOpen: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenOutput: () -> Unit,
    onTogether: () -> Unit,
    queueOpen: Boolean,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    panel: (@Composable BoxScope.() -> Unit)? = null,
    onWatchMusicVideo: (() -> Unit)? = null,
) {
    // Back restarts the song once it is a few seconds in; derived, so this scope hears about the playhead once, at that point.
    val pastRestartPoint by remember(position) { derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS } }
    val reduced = LocalReducedMotion.current
    // What the panel's card covers folds away on the move spring and is gone from the tree at the end: in portrait with the
    // queue open the title, the artist and the scrubber; in two panes, with the lyrics or the queue, the scrubber (the panel
    // itself takes the place of the title and the artist).
    val fold = animateFloatAsState(if (if (landscape) panel != null else queueOpen) 1f else 0f, CosmoMotion.move(reduced), label = "stackFold")
    val folded by remember(fold) { derivedStateOf { fold.value >= 0.999f } }
    // The title's arrival runs 0 to 1 on every new song. It lives here, not in the title, so that a title that was folded away
    // and comes back (the queue closing) does not arrive again.
    val arrival = remember(song.videoId) { Animatable(if (reduced || lyricsOpen) 1f else 0f) }
    LaunchedEffect(song.videoId, reduced, lyricsOpen) {
        if (reduced || lyricsOpen) arrival.snapTo(1f) else arrival.animateTo(1f, CosmoMotion.slow(reduced))
    }
    val frame = if (landscape) {
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
    } else {
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
    }
    Column(modifier.then(frame)) {
        if (landscape) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (panel != null) {
                    panel()
                } else {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        NowPlayingTitle(song.title, arrival, lyricsOpen = false, landscape = true)
                        ArtistRow(song, coverUrl, hasVideo, reveal, onChipPlaced, liked, onToggleLike, onOpenMenu, onOpenArtist, verticalPadding = 2.dp)
                        AudioFormatLine()
                    }
                }
            }
        } else if (!folded) {
            Column(Modifier.foldedBy(fold)) {
                NowPlayingTitle(song.title, arrival, lyricsOpen = lyricsOpen, landscape = false)
                ArtistRow(song, coverUrl, hasVideo, reveal, onChipPlaced, liked, onToggleLike, onOpenMenu, onOpenArtist)
                AudioFormatLine()
            }
        }
        if (!folded) {
            Column(Modifier.foldedBy(fold)) {
                WaveScrubber(
                    fraction = { scrub.shown(position.positionMs, durationMs) },
                    playing = isPlaying,
                    onScrub = scrub::drag,
                    onRelease = { scrub.release(onSeekFraction) },
                )
                Times(position, durationMs, scrub)
            }
        }
        Transport(
            isPlaying = isPlaying, isLoading = isLoading, onPlayPause = onPlayPause, locked = controlsLocked,
            previousEnabled = controlsLocked || hasPrevious || pastRestartPoint,
            nextEnabled = controlsLocked || hasNext,
            onPrevious = onPrevious, onNext = onNext, onBlockedControl = onBlockedControl,
            shuffleEnabled = shuffleEnabled, onToggleShuffle = onToggleShuffle, repeatMode = repeatMode, onCycleRepeat = onCycleRepeat,
            playSize = if (landscape) LandscapeMetrics.PLAY_BUTTON_DP.dp else 80.dp,
        )
        Tools(lyricsOpen, onToggleLyrics, onOpenOutput, onTogether, queueOpen, onToggleQueue, onWatchMusicVideo)
    }
}

/**
 * Folds a block away as [share] goes from 0 to 1: it is measured as it is and reports a shorter height (the layout phase
 * reads [share], nothing recomposes), is clipped to it and fades out with it.
 */
private fun Modifier.foldedBy(share: State<Float>): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * (1f - share.value).coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
    .graphicsLayer { alpha = 1f - share.value.coerceIn(0f, 1f) }

/** The move spring as a finite spec for the APIs that want one; reduced motion: a short fade in its place. */
private fun <T> finiteMove(reduced: Boolean): FiniteAnimationSpec<T> =
    if (reduced) tween(Springs.REDUCED_MS) else spring(Springs.MOVE.dampingRatio, Springs.MOVE.stiffness)

/**
 * The title in the display face. On every new song [arrival] runs 0 to 1 on the slow spring; the weight steps through the six
 * instances of the title path (light and wide to heavy and narrow) with the letter spacing in the same steps, and it rises
 * 10 dp as it fades in. Stepping, not gliding: the instances are cut once, and a glide would lay the text out again on every
 * frame. Reduced motion snaps it to its end.
 *
 * With [lyricsOpen] it is `title(TITLE_L)` instead, `TITLE_M` when the title is long (`.np2.lyrics-on .np2-title`), and does
 * not arrive: the lyrics have the page. A [landscape] window sets it at `display(DISPLAY_S)`, on one line; it is shorter there.
 */
@Composable
private fun NowPlayingTitle(title: String, arrival: Animatable<Float, AnimationVector1D>, lyricsOpen: Boolean, landscape: Boolean, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val reduced = LocalReducedMotion.current
    val step by remember(arrival) { derivedStateOf { NowPlayingMetrics.titleStep(arrival.value) } }
    val displayStep = if (landscape) LandscapeMetrics.TITLE_STEP else TypeScale.titleStep(title)
    val style = remember(type, title, step, lyricsOpen, displayStep) {
        val base = if (lyricsOpen) {
            type.title(if (title.length > TypeScale.LONG_TITLE_CHARS) TypeScale.TITLE_M else TypeScale.TITLE_L)
        } else {
            type.display(displayStep).copy(
                fontWeight = type.titlePath(step),
                letterSpacing = NowPlayingMetrics.titleTrackingEm(step).em,
            )
        }
        type.forText(base, title)
    }
    Text(
        text = title,
        style = style,
        color = Color(palette.textPrimary),
        maxLines = if (landscape) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            // The stack gets shorter, or taller, as the title changes size; it eases there on the move spring.
            .animateContentSize(finiteMove(reduced))
            .graphicsLayer {
                val t = arrival.value.coerceIn(0f, 1f)
                alpha = t
                translationY = 10.dp.toPx() * (1f - t)
            },
    )
}

/**
 * The artist row (`.np2-artist`), 10 dp above and below: the chip slot, the artist (16 sp, weight 650, one line, tap to open
 * the artist), then like and More as 40 dp tonal buttons. The slot is 0 wide until the video is in, then 40 dp (the 32 dp
 * cover and a gap): the picture in it is the cover's landing.
 */
@Composable
private fun ArtistRow(
    song: Song,
    coverUrl: String?,
    hasVideo: Boolean,
    reveal: State<Float>,
    onChipPlaced: (Offset) -> Unit,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    verticalPadding: Dp = 10.dp,
) {
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val style = remember(type) { TextStyle(fontFamily = type.textFamily, fontSize = 16.sp, fontWeight = FontWeight(650)) }
    Row(Modifier.fillMaxWidth().padding(vertical = verticalPadding), verticalAlignment = Alignment.CenterVertically) {
        ChipSlot(coverUrl, hasVideo, reveal, onChipPlaced)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text(
                text = song.artist,
                style = style,
                color = Color(palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable(role = Role.Button) { onOpenArtist(song.artistId, song.artist) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
        CosmoIconButton(
            if (liked) CosmoIcons.HeartFill else CosmoIcons.Heart,
            stringResource(if (liked) Res.string.remove_from_liked else Res.string.like),
            onToggleLike,
            style = CosmoIconButtonStyle.TONAL, size = 40.dp, on = liked, accentWhenOn = true,
        )
        CosmoIconButton(CosmoIcons.MoreVertical, stringResource(Res.string.more), onOpenMenu, style = CosmoIconButtonStyle.TONAL, size = 40.dp)
    }
}

/**
 * Room for the 32 dp cover beside the artist, opening from 0 to 40 dp with [reveal] (read in the layout phase: the row is
 * measured again each frame of the reveal, nothing is recomposed). The picture, a real 32 dp [CoverImage] with the chip
 * radius, is composed as soon as the song has a video so that it has loaded when the cover arrives, and is seen only in the
 * last stretch of the trip ([NowPlayingMetrics.chipShare]). Its top-left corner goes to [onPlaced].
 */
@Composable
private fun ChipSlot(url: String?, hasVideo: Boolean, reveal: State<Float>, onPlaced: (Offset) -> Unit) {
    Box(
        Modifier
            .height(NowPlayingMetrics.CHIP_DP.dp)
            .layout { measurable, _ ->
                val chip = NowPlayingMetrics.CHIP_DP.dp.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(chip, chip))
                layout(NowPlayingMetrics.chipSlotDp(reveal.value).dp.roundToPx(), chip) { placeable.place(0, 0) }
            }
            .onGloballyPositioned { onPlaced(it.positionInRoot()) },
    ) {
        if (hasVideo) {
            Box(Modifier.graphicsLayer { alpha = NowPlayingMetrics.chipShare(reveal.value) }) {
                CoverImage(url, NowPlayingMetrics.CHIP_DP.dp, CosmoRadii.coverChip, Modifier, shadow = false)
            }
        }
    }
}

/**
 * Elapsed and remaining (12.5 sp, tabular figures, secondary ink) under the scrubber, pulled up under the wave (its touch area
 * is 48 dp, the wave 28). It reads the playhead itself, so it alone recomposes twice a second; while the handle is dragged it
 * shows where the handle is.
 */
@Composable
private fun Times(position: PlaybackPosition, durationMs: Long, scrub: PlayerScrub, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    val ink = Color(LocalCosmoColors.current.palette.textSecondary)
    val style = remember(type) { TextStyle(fontFamily = type.textFamily, fontSize = 12.5.sp, fontWeight = FontWeight(500)).tabular() }
    val shownMs = (scrub.shown(position.positionMs, durationMs) * durationMs).toLong()
    Row(
        modifier.fillMaxWidth().pullUp(6),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(NowPlayingMetrics.formatTime(shownMs), style = style, color = ink)
        Text(NowPlayingMetrics.formatRemaining(shownMs, durationMs), style = style, color = ink)
    }
}

/** Moves a block up by [dp] and gives the room back: the layout is shorter by as much. */
private fun Modifier.pullUp(dp: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = dp.dp.roundToPx()
    layout(placeable.width, (placeable.height - up).coerceAtLeast(0)) { placeable.place(0, -up) }
}

/**
 * Shuffle, previous, play, next and repeat, spread over the width, 10 dp under the times. Previous and next carry their
 * skip haptics; in a party whose host has taken the controls they stay enabled and say so ([onBlockedControl]) instead of acting.
 * The play button is the page's one hero.
 */
@Composable
private fun Transport(
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    locked: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBlockedControl: () -> Unit,
    shuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    repeatMode: Int,
    onCycleRepeat: () -> Unit,
    playSize: Dp,
) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CosmoIconButton(
            CosmoIcons.Shuffle, stringResource(if (shuffleEnabled) Res.string.shuffle_on else Res.string.shuffle_off), onToggleShuffle,
            style = CosmoIconButtonStyle.PLAIN, size = 48.dp, on = shuffleEnabled,
        )
        CosmoIconButton(
            CosmoIcons.Previous, stringResource(Res.string.widget_previous),
            onClick = { if (locked) onBlockedControl() else { haptics.play(Haptic.SkipPrevious); onPrevious() } },
            style = CosmoIconButtonStyle.TONAL, size = 56.dp, enabled = previousEnabled,
        )
        PlayMorphButton(isPlaying, isLoading, onPlayPause, size = playSize)
        CosmoIconButton(
            CosmoIcons.Next, stringResource(Res.string.widget_next),
            onClick = { if (locked) onBlockedControl() else { haptics.play(Haptic.SkipNext); onNext() } },
            style = CosmoIconButtonStyle.TONAL, size = 56.dp, enabled = nextEnabled,
        )
        CosmoIconButton(
            if (repeatMode == RepeatModes.ONE) CosmoIcons.RepeatOne else CosmoIcons.Repeat,
            stringResource(
                when (repeatMode) {
                    RepeatModes.OFF -> Res.string.repeat_off
                    RepeatModes.ONE -> Res.string.repeat_one
                    else -> Res.string.repeat_all
                },
            ),
            onCycleRepeat, style = CosmoIconButtonStyle.PLAIN, size = 48.dp, on = repeatMode != RepeatModes.OFF,
        )
    }
}

/**
 * Lyrics, output, Listen Together and the queue (`.np2-tools`), 14 dp under the transport, 40 dp plain buttons. Lyrics and the
 * queue are toggles. At rest the icons are in secondary ink, as the board draws them (`.np2-tools .ib { color: var(--c-text-2) }`);
 * a tool that is on keeps the toggle's own ink (`.ib.is-on`). Portrait and landscape both draw this row.
 *
 * With [onWatchMusicVideo] a fifth tool, "Watch the music video" (Video mode's link, spec section 9), stands between Listen
 * Together and the queue, so the two panel toggles keep the ends of the row; it rests like the others.
 */
@Composable
private fun Tools(
    lyricsOpen: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenOutput: () -> Unit,
    onTogether: () -> Unit,
    queueOpen: Boolean,
    onToggleQueue: () -> Unit,
    onWatchMusicVideo: (() -> Unit)?,
) {
    val resting = Color(LocalCosmoColors.current.palette.textSecondary)
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, start = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CosmoIconButton(CosmoIcons.Lyrics, stringResource(Res.string.open_lyrics), onToggleLyrics, style = CosmoIconButtonStyle.PLAIN, size = 40.dp, on = lyricsOpen, tint = resting)
        CosmoIconButton(CosmoIcons.Devices, stringResource(Res.string.audio_output), onOpenOutput, style = CosmoIconButtonStyle.PLAIN, size = 40.dp, tint = resting)
        CosmoIconButton(CosmoIcons.Together, stringResource(Res.string.listen_together), onTogether, style = CosmoIconButtonStyle.PLAIN, size = 40.dp, tint = resting)
        if (onWatchMusicVideo != null) {
            CosmoIconButton(CosmoIcons.Video, stringResource(Res.string.cosmo_watch_music_video), onWatchMusicVideo, style = CosmoIconButtonStyle.PLAIN, size = 40.dp, tint = resting)
        }
        CosmoIconButton(CosmoIcons.Queue, stringResource(Res.string.queue), onToggleQueue, style = CosmoIconButtonStyle.PLAIN, size = 40.dp, on = queueOpen, tint = resting)
    }
}

/**
 * The action of "Watch the music video" for the song that plays, or null when the tool is not drawn ([musicVideoLink]): Video mode
 * is available (its host provides [LocalModeLinks]), and [canvas] is a music video that OpenCanvas found and that follows the song
 * (an id and a map). Decided once per canvas; the playhead is read when the tool is pressed, so the video opens at the song's moment
 * then and nothing here recomposes with the position.
 */
@Composable
internal fun rememberWatchMusicVideo(song: Song, canvas: CanvasArtwork?, position: PlaybackPosition): (() -> Unit)? {
    val links = LocalModeLinks.current
    return remember(links, canvas, song.title, song.artist, position) {
        musicVideoLink(links, canvas, song.title, song.artist) { position.positionMs }
    }
}

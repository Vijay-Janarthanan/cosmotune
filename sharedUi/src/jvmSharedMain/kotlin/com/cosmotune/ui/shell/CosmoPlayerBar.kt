package com.cosmotune.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.NowPlayingMetrics
import com.cosmotune.design.PlayerBarMetrics
import com.cosmotune.design.SliderMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.WaveScrubber
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CapsuleLabel
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.audio_output
import com.music.bitchord.sharedui.resources.like
import com.music.bitchord.sharedui.resources.now_playing
import com.music.bitchord.sharedui.resources.open_lyrics
import com.music.bitchord.sharedui.resources.queue
import com.music.bitchord.sharedui.resources.remove_from_liked
import com.music.bitchord.sharedui.resources.repeat_all
import com.music.bitchord.sharedui.resources.repeat_off
import com.music.bitchord.sharedui.resources.repeat_one
import com.music.bitchord.sharedui.resources.shuffle_off
import com.music.bitchord.sharedui.resources.shuffle_on
import com.music.bitchord.sharedui.resources.widget_next
import com.music.bitchord.sharedui.resources.widget_previous
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.player.PlayerScrub
import com.music.bitchord.ui.player.RepeatModes
import com.music.bitchord.ui.player.rememberPlayerScrub
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop's player capsule (layer C of the B+C system; `V2.deskPlayer`, `screens-v2.css:225-233`): an 80 dp glass
 * capsule with superellipse corners of 28 dp, 16 dp of padding and 12 dp between its parts. From the left: the song's
 * cover (48 dp) and title over artist (14.5 sp weight 700, 12.5 sp, at most 220 dp wide; a click on either expands the
 * player), the like button, the middle column (the transport over the two times and a [WaveScrubber], at most 560 dp wide)
 * and, on the right, lyrics, queue, output and the volume bar ([CosmoSlider] on the host's volume).
 *
 * It sits in the window, not in the page: [DesktopPlayerBarHost][com.cosmotune.desktop] puts it 14 dp above the bottom,
 * clear of the sidebar and of the lyrics or queue column. Like the phone's mini capsule it reports where it is
 * ([com.cosmotune.ui.theme.CosmoShellState.capsuleBounds]) and what it shows ([CapsuleLabel]) for the player that grows out of
 * it, and draws nothing while that player stands in its place.
 *
 * The board draws one arrangement for a window 1440 dp wide; a narrower capsule (a window with the lyrics or the queue
 * column open) follows [PlayerBarMetrics.plan]: first the volume bar goes, then shuffle and repeat, then the scrubber (a
 * 3 dp progress line along the foot takes its place) and the like and output buttons. Lyrics and queue stay, since they put the
 * column away again. Every control is floating (C): glass and ink, with the cover's tint when it is on.
 *
 * [controlsLocked] is a party whose host has taken the controls: previous, next and the scrubber then answer with
 * [onBlockedControl] and are drawn a little dimmer. [volumeLabel] names the volume bar for a screen reader (the desktop has
 * the word in its own catalogue). Nothing here reads the playhead in composition except the elapsed time, alone: the wave and
 * the progress line read it while drawing.
 */
@Composable
fun CosmoPlayerBar(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    liked: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    controlsLocked: Boolean,
    lyricsOn: Boolean,
    queueOn: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onOutput: () -> Unit,
    onExpand: () -> Unit,
    onBlockedControl: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    volumeLabel: String? = null,
) {
    val shell = LocalCosmoShell.current
    val shape = remember { SuperellipseShape(PlayerBarMetrics.RADIUS_DP.dp) }
    val coverUrl = PlayerPlatform.host.rememberRemoteArtworkUrl(song)
    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)

    // What the morph copies: published as a value, so an unchanged song writes nothing (the phone's capsule does the same).
    val label = remember(song.title, song.artist, coverUrl) { CapsuleLabel(song.title, song.artist, coverUrl) }
    if (shell != null) {
        SideEffect { shell.capsuleLabel = label }
        DisposableEffect(shell) { onDispose { shell.capsuleBounds = null; shell.capsuleLabel = null } }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(PlayerBarMetrics.HEIGHT_DP.dp)
            // Invisible while the player stands in its place: decided in the draw phase, where the overlay decides its card.
            .drawWithContent { if (shell?.capsuleHidden != true) drawContent() }
            .onGloballyPositioned { shell?.capsuleBounds = it.boundsInRoot() }
            .cosmoGlass(hazeState, shape),
    ) {
        val plan = remember(maxWidth) { PlayerBarMetrics.plan(maxWidth.value) }
        Row(
            Modifier.fillMaxSize().padding(horizontal = PlayerBarMetrics.PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(PlayerBarMetrics.GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongBlock(song, coverUrl, plan, onExpand)
            if (plan.like) {
                CosmoIconButton(
                    if (liked) CosmoIcons.HeartFill else CosmoIcons.Heart,
                    stringResource(if (liked) Res.string.remove_from_liked else Res.string.like),
                    onToggleLike,
                    style = CosmoIconButtonStyle.TONAL, size = PlayerBarMetrics.BUTTON_DP.dp, on = liked, accentWhenOn = true, floating = true,
                )
            }
            if (plan.scrubber) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Transport(
                        plan.shuffleRepeat, isPlaying, isLoading, shuffle, repeatMode, controlsLocked,
                        onPlayPause, onPrevious, onNext, onToggleShuffle, onCycleRepeat, onBlockedControl,
                    )
                    ScrubRow(position, durationMs, scrub, isPlaying, controlsLocked, onSeekFraction, onBlockedControl)
                }
            } else {
                // Too narrow for the middle column: the play button alone, and a progress line along the foot (below).
                PlayMorphButton(isPlaying, isLoading, onPlayPause, size = PlayerBarMetrics.PLAY_DP.dp, floating = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CosmoIconButton(
                    CosmoIcons.Lyrics, stringResource(Res.string.open_lyrics), onLyrics,
                    style = CosmoIconButtonStyle.PLAIN, size = PlayerBarMetrics.BUTTON_DP.dp, on = lyricsOn, floating = true,
                )
                CosmoIconButton(
                    CosmoIcons.Queue, stringResource(Res.string.queue), onQueue,
                    style = CosmoIconButtonStyle.PLAIN, size = PlayerBarMetrics.BUTTON_DP.dp, on = queueOn, floating = true,
                )
                if (plan.output) {
                    CosmoIconButton(
                        CosmoIcons.Devices, stringResource(Res.string.audio_output), onOutput,
                        style = CosmoIconButtonStyle.PLAIN, size = PlayerBarMetrics.BUTTON_DP.dp, floating = true,
                    )
                }
                if (plan.volume) {
                    Spacer(Modifier.width(PlayerBarMetrics.VOLUME_MARGIN_DP.dp))
                    VolumeBar(volumeLabel)
                }
            }
        }
        if (!plan.scrubber) ProgressLine(position, durationMs, Modifier.matchParentSize())
    }
}

/**
 * The cover and the title over the artist: one click target that expands the player. The column is
 * [PlayerBarMetrics.Plan.textDp] wide (the board's `minmax(0, 220px)`), so the like button stays where it is whatever the title
 * says; in the narrowest plan it takes what the row leaves.
 */
@Composable
private fun RowScope.SongBlock(song: Song, coverUrl: String?, plan: PlayerBarMetrics.Plan, onExpand: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = remember(type, song.title) { type.forText(barTitleStyle(type.textFamily), song.title) }
    val artist = remember(type, song.artist) { type.forText(barArtistStyle(type.textFamily), song.artist) }
    val nowPlaying = stringResource(Res.string.now_playing)
    val size = if (plan.tier == PlayerBarMetrics.Tier.MINIMAL) Modifier.weight(1f) else Modifier.width((PlayerBarMetrics.COVER_DP + PlayerBarMetrics.GAP_DP + plan.textDp).dp)
    Row(
        size
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = nowPlaying, role = Role.Button, onClick = onExpand),
        horizontalArrangement = Arrangement.spacedBy(PlayerBarMetrics.GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(coverUrl, PlayerBarMetrics.COVER_DP.dp, CosmoRadii.coverDesktopBar, shadow = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(song.title, style = title, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = artist, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The title: 14.5 sp, weight 700. */
private fun barTitleStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)

/** The artist: 12.5 sp. */
private fun barArtistStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 12.5.sp, lineHeight = 16.sp)

/**
 * Shuffle, previous, play, next and repeat (or just the three in the middle): 40 dp plain buttons round the 48 dp play button,
 * 8 dp apart on the board. A 40 dp button takes 48 dp of the row (its touch target), which is 8 dp between two of them, so the
 * row has no spacing of its own and 4 dp more on each side of the play button.
 */
@Composable
private fun Transport(
    withShuffleAndRepeat: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    locked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onBlockedControl: () -> Unit,
) {
    val button = PlayerBarMetrics.BUTTON_DP.dp
    val dimmed = Modifier.graphicsLayer { alpha = if (locked) LOCKED_ALPHA else 1f }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (withShuffleAndRepeat) {
            CosmoIconButton(
                CosmoIcons.Shuffle, stringResource(if (shuffle) Res.string.shuffle_on else Res.string.shuffle_off), onToggleShuffle,
                style = CosmoIconButtonStyle.PLAIN, size = button, on = shuffle, floating = true,
            )
        }
        CosmoIconButton(
            CosmoIcons.Previous, stringResource(Res.string.widget_previous), { if (locked) onBlockedControl() else onPrevious() },
            modifier = dimmed, style = CosmoIconButtonStyle.PLAIN, size = button, floating = true,
        )
        Spacer(Modifier.width((PlayerBarMetrics.TRANSPORT_GAP_DP / 2).dp))
        PlayMorphButton(isPlaying, isLoading, onPlayPause, size = PlayerBarMetrics.PLAY_DP.dp, floating = true)
        Spacer(Modifier.width((PlayerBarMetrics.TRANSPORT_GAP_DP / 2).dp))
        CosmoIconButton(
            CosmoIcons.Next, stringResource(Res.string.widget_next), { if (locked) onBlockedControl() else onNext() },
            modifier = dimmed, style = CosmoIconButtonStyle.PLAIN, size = button, floating = true,
        )
        if (withShuffleAndRepeat) {
            CosmoIconButton(
                if (repeatMode == RepeatModes.ONE) CosmoIcons.RepeatOne else CosmoIcons.Repeat,
                stringResource(
                    when (repeatMode) {
                        RepeatModes.OFF -> Res.string.repeat_off
                        RepeatModes.ONE -> Res.string.repeat_one
                        else -> Res.string.repeat_all
                    },
                ),
                onCycleRepeat, style = CosmoIconButtonStyle.PLAIN, size = button, on = repeatMode != RepeatModes.OFF, floating = true,
            )
        }
    }
}

/**
 * The elapsed time, the wave and the song's length (`.d2-p-scrub`): 44 dp time columns with 8 dp between, at most 560 dp
 * wide. The wave draws 16 dp tall; its touch area is 48 dp tall whatever it draws, so the row holds it to 24 dp, which still
 * leaves a hand a comfortable band round the line, and the whole middle column fits the 80 dp capsule. While the controls are
 * locked a drag is not taken up: it answers with [onBlockedControl] when it lets go.
 */
@Composable
private fun ScrubRow(
    position: PlaybackPosition,
    durationMs: Long,
    scrub: PlayerScrub,
    isPlaying: Boolean,
    locked: Boolean,
    onSeekFraction: (Float) -> Unit,
    onBlockedControl: () -> Unit,
) {
    Row(
        Modifier.widthIn(max = PlayerBarMetrics.SCRUB_MAX_DP.dp).fillMaxWidth().height(SCRUB_ROW_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(PlayerBarMetrics.SCRUB_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Elapsed(position, durationMs, scrub, Modifier.width(PlayerBarMetrics.TIME_DP.dp))
        WaveScrubber(
            fraction = { scrub.shown(position.positionMs, durationMs) },
            playing = isPlaying,
            onScrub = { if (!locked) scrub.drag(it) },
            onRelease = { if (locked) onBlockedControl() else scrub.release(onSeekFraction) },
            modifier = Modifier.weight(1f),
            height = PlayerBarMetrics.WAVE_HEIGHT_DP.dp,
        )
        TimeText(NowPlayingMetrics.formatTime(durationMs), Modifier.width(PlayerBarMetrics.TIME_DP.dp), TextAlign.End)
    }
}

/** Where the song is (where the handle is, while it is dragged). It reads the playhead itself, so it alone recomposes with it. */
@Composable
private fun Elapsed(position: PlaybackPosition, durationMs: Long, scrub: PlayerScrub, modifier: Modifier) {
    val shownMs = (scrub.shown(position.positionMs, durationMs) * durationMs).toLong()
    TimeText(NowPlayingMetrics.formatTime(shownMs), modifier, TextAlign.Start)
}

/** A time: 12 sp, tabular figures, secondary ink. */
@Composable
private fun TimeText(text: String, modifier: Modifier, align: TextAlign) {
    val type = LocalCosmoType.current
    val style = remember(type) { TextStyle(fontFamily = type.textFamily, fontSize = 12.sp, fontWeight = FontWeight(500)).tabular() }
    Text(text, modifier, color = Color(LocalCosmoColors.current.palette.textSecondary), style = style, maxLines = 1, textAlign = align)
}

/** The volume bar: a 92 dp [CosmoSlider] on the host's system volume (the desktop's own, kept by its window). */
@Composable
private fun VolumeBar(label: String?) {
    val volume = PlayerPlatform.host.volume
    val level by volume.level.collectAsStateWithLifecycle()
    CosmoSlider(level, onValueChange = volume::set, modifier = Modifier.width(PlayerBarMetrics.VOLUME_DP.dp), contentDescription = label)
}

/**
 * The narrowest capsule's progress: the phone capsule's line, 3 dp tall, 4 dp from the bottom and 18 dp in from both sides.
 * The playhead is read here, in the draw block, and nowhere else, so a tick of the song redraws a line and recomposes nothing.
 */
@Composable
private fun ProgressLine(position: PlaybackPosition, durationMs: Long, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    Box(
        modifier.drawBehind {
            val inset = PROGRESS_INSET.toPx()
            val thickness = PROGRESS_HEIGHT.toPx()
            val width = size.width - 2f * inset
            if (width <= 0f) return@drawBehind
            val top = size.height - PROGRESS_BOTTOM.toPx() - thickness
            val ink = colors.live(CoverField.TEXT_PRIMARY)
            val round = CornerRadius(thickness / 2f)
            drawRoundRect(ink.copy(alpha = SliderMetrics.trackAlpha(colors.dark)), Offset(inset, top), Size(width, thickness), round)
            val played = if (durationMs > 0L) (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            if (played > 0f) drawRoundRect(ink, Offset(inset, top), Size(width * played, thickness), round)
        },
    )
}

private val SCRUB_ROW_HEIGHT = 24.dp
private val PROGRESS_INSET = 18.dp
private val PROGRESS_BOTTOM = 4.dp
private val PROGRESS_HEIGHT = 3.dp
private const val LOCKED_ALPHA = 0.5f

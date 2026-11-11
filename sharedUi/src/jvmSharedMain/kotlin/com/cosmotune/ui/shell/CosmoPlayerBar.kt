package com.cosmotune.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.PlayerBarMetrics
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.playback.rememberLivePlayhead
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.audio_output
import com.cosmotune.music.sharedui.resources.like
import com.cosmotune.music.sharedui.resources.now_playing
import com.cosmotune.music.sharedui.resources.open_lyrics
import com.cosmotune.music.sharedui.resources.queue
import com.cosmotune.music.sharedui.resources.remove_from_liked
import com.cosmotune.music.sharedui.resources.repeat_all
import com.cosmotune.music.sharedui.resources.repeat_off
import com.cosmotune.music.sharedui.resources.repeat_one
import com.cosmotune.music.sharedui.resources.shuffle_off
import com.cosmotune.music.sharedui.resources.shuffle_on
import com.cosmotune.music.sharedui.resources.widget_next
import com.cosmotune.music.sharedui.resources.widget_previous
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerScrub
import com.cosmotune.music.ui.player.RepeatModes
import com.cosmotune.music.ui.player.rememberPlayerScrub
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitBarMetrics
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CapsuleLabel
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop's player bar in Orbit (board v2, section 6): Home's colour card made slim, the full width of the content beside the
 * navigation, filled with the playing cover's deep colour ([coverFill], light ink, in both themes), corners of 30 dp and play in a
 * round bite cut out of its end ([OrbitBarMetrics.shape]). From the start: the cover as a [Disc] in an [OrbitRing] (the played part
 * and a moon at the current second) and the title over the artist (a click on either expands the player), the like button, then
 * the middle column: shuffle, previous, next and repeat, the mono "m:ss / m:ss", and lyrics, queue, output and the volume at its
 * end, over the [OrbitProgressLine] (tap or drag to seek). Play / pause is the [OrbitPlayButton] standing in the bite; while the
 * song loads a moon circles it there.
 *
 * It sits in the window, not in the page: [DesktopPlayerBarHost][com.cosmotune.desktop] puts it 14 dp above the bottom, clear of the
 * sidebar and of the lyrics or queue column. It reports where it is ([com.cosmotune.ui.theme.CosmoShellState.capsuleBounds]) and
 * what it shows ([CapsuleLabel], with [OrbitBarMetrics.geometry]) for the player that grows out of it, and draws nothing while that
 * player stands in its place.
 *
 * A narrower bar follows [OrbitBarMetrics.plan]: first the volume goes, then shuffle and repeat, then the middle column (the orbit
 * round the disc is the progress then) with the like and output buttons. Lyrics and queue stay, since they put the column away.
 * A live radio station has no previous, next, shuffle, repeat, length or lyrics: the bar keeps play, the name, the heart and LIVE.
 *
 * [controlsLocked] is a party whose host has taken the controls: previous, next and the seek line then answer with
 * [onBlockedControl] and are drawn a little dimmer. [volumeLabel] names the volume bar for a screen reader. The playhead is read in
 * composition only by the clock, alone; the orbit and the seek line read it while drawing. [hazeState] is kept for the callers:
 * an Orbit card is flat, so nothing is blurred behind it.
 *
 * [embedded] is the same bar drawn inside the open player rather than as the capsule the player grows out of: it neither reports
 * its bounds nor publishes what it shows, is not hidden while the player stands open, and has no click that expands the player.
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
    embedded: Boolean = false,
) {
    val shell = LocalCosmoShell.current
    val capsuleShell = if (embedded) null else shell
    val coverUrl = PlayerPlatform.host.rememberRemoteArtworkUrl(song)
    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    val playhead = rememberLivePlayhead(position, isPlaying)
    val type = LocalCosmoType.current
    val fonts = orbitFonts()
    val look = orbitLook()
    val (fill, ink) = coverFill(coverUrl, song.videoId)
    val live = song.videoId.startsWith("podio:radio:")
    val bar = BarInk(fill, ink)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(OrbitBarMetrics.HEIGHT_DP.dp)
            // Invisible while the player stands in its place: decided in the draw phase, where the overlay decides its card.
            .drawWithContent { if (capsuleShell?.capsuleHidden != true) drawContent() }
            .onGloballyPositioned { capsuleShell?.capsuleBounds = it.boundsInRoot() },
    ) {
        val plan = remember(maxWidth) { OrbitBarMetrics.plan(maxWidth.value) }
        val titleStyle = remember(fonts, type, song.title) { type.forText(barTitleStyle(fonts), song.title) }
        val artistStyle = remember(fonts, type, song.artist) { type.forText(barArtistStyle(fonts), song.artist) }

        // What the morph copies: the words and the picture, and how this bar is built at this width and plan, published as a value
        // so an unchanged song at an unchanged width writes nothing.
        if (capsuleShell != null) {
            val label = remember(song.title, song.artist, coverUrl, plan, maxWidth, titleStyle, artistStyle) {
                CapsuleLabel(song.title, song.artist, coverUrl, OrbitBarMetrics.geometry(plan, maxWidth.value), titleStyle, artistStyle)
            }
            SideEffect { capsuleShell.capsuleLabel = label }
            DisposableEffect(capsuleShell) { onDispose { capsuleShell.capsuleBounds = null; capsuleShell.capsuleLabel = null } }
        }

        Box(Modifier.matchParentSize().clip(OrbitBarMetrics.shape).background(fill)) {
            Row(
                Modifier.fillMaxSize().padding(start = OrbitBarMetrics.PAD_START_DP.dp, end = OrbitBarMetrics.END_PAD_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(OrbitBarMetrics.GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongBlock(
                    song, coverUrl, plan, titleStyle, artistStyle, bar,
                    progress = { if (live || durationMs <= 0L) null else scrub.shown(playhead.positionMs(), durationMs) },
                    spinning = isPlaying, onExpand = onExpand.takeUnless { embedded },
                )
                if (plan.like) {
                    OrbitRoundButton(
                        if (liked) CosmoIcons.HeartFill else CosmoIcons.Heart,
                        stringResource(if (liked) Res.string.remove_from_liked else Res.string.like),
                        ink, onToggleLike, size = BUTTON, on = liked, onInk = fill,
                    )
                }
                when {
                    live -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                        Spacer(Modifier.width(8.dp))
                        Text("LIVE", style = fonts.label, color = ink.copy(alpha = 0.8f))
                        SleepBadge(fonts, ink, Modifier.padding(start = 8.dp))
                        Spacer(Modifier.weight(1f))
                        Tools(live, plan, bar, lyricsOn, queueOn, onLyrics, onQueue, onOutput, volumeLabel)
                    }
                    plan.scrubber -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Transport(
                                plan.shuffleRepeat, shuffle, repeatMode, controlsLocked, bar,
                                onPrevious, onNext, onToggleShuffle, onCycleRepeat, onBlockedControl,
                            )
                            Spacer(Modifier.width(OrbitBarMetrics.CLOCK_GAP_DP.dp))
                            Clock(position, durationMs, scrub, fonts, ink)
                            SleepBadge(fonts, ink)
                            Spacer(Modifier.weight(1f))
                            Tools(live, plan, bar, lyricsOn, queueOn, onLyrics, onQueue, onOutput, volumeLabel)
                        }
                        OrbitProgressLine(
                            progress = { scrub.shown(playhead.positionMs(), durationMs) },
                            ink = ink,
                            modifier = Modifier.graphicsLayer { alpha = if (controlsLocked) LOCKED_ALPHA else 1f },
                            onSeek = { fraction -> if (controlsLocked) onBlockedControl() else { scrub.drag(fraction); scrub.release(onSeekFraction) } },
                            moon = 5.dp,
                        )
                    }
                    // Too narrow for the middle column: the tools alone; the orbit round the disc is the progress.
                    else -> Tools(live, plan, bar, lyricsOn, queueOn, onLyrics, onQueue, onOutput, volumeLabel)
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = (OrbitBarMetrics.BITE_INSET_DP - OrbitBarMetrics.BITE_DP).dp)
                .size((OrbitBarMetrics.BITE_DP * 2).dp),
            contentAlignment = Alignment.Center,
        ) {
            // While the song loads a moon circles the button in the bite's clear ring.
            if (isLoading) OrbitRing((OrbitBarMetrics.BITE_DP * 2).dp, look.accent, progress = null, spinning = true, stroke = 1.5.dp, moon = 3.dp)
            OrbitPlayButton(isPlaying, onPlayPause, look, size = OrbitBarMetrics.PLAY_DP.dp)
        }
    }
}

/** The bar's two colours: the card's deep fill and its light ink (a button that is on is solid ink with the fill as its glyph). */
private class BarInk(val fill: Color, val ink: Color)

/**
 * The disc in its orbit and the title over the artist: one click target that expands the player. The words are the plan's
 * [PlayerBarMetrics.Plan.textDp] wide, so the controls stay where they are whatever the title says; in the narrowest plan they take
 * what the row leaves.
 */
@Composable
private fun RowScope.SongBlock(
    song: Song,
    coverUrl: String?,
    plan: PlayerBarMetrics.Plan,
    titleStyle: TextStyle,
    artistStyle: TextStyle,
    bar: BarInk,
    progress: () -> Float?,
    spinning: Boolean,
    onExpand: (() -> Unit)?,
) {
    val nowPlaying = stringResource(Res.string.now_playing)
    val width = if (plan.tier == PlayerBarMetrics.Tier.MINIMAL) Modifier.weight(1f)
    else Modifier.width((OrbitBarMetrics.RING_DP + OrbitBarMetrics.GAP_DP + plan.textDp).dp)
    Row(
        // Inside the open player (no [onExpand]) the disc and the words are just that: nothing to expand.
        if (onExpand == null) width else width.clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable(onClickLabel = nowPlaying, role = Role.Button, onClick = onExpand),
        horizontalArrangement = Arrangement.spacedBy(OrbitBarMetrics.GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(OrbitBarMetrics.RING_DP.dp), contentAlignment = Alignment.Center) {
            Disc(coverUrl, OrbitBarMetrics.DISC_DP.dp)
            OrbitRing(OrbitBarMetrics.RING_DP.dp, bar.ink, progress = progress, spinning = spinning, stroke = 2.dp, moon = 4.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(song.title, style = titleStyle, color = bar.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = artistStyle, color = bar.ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The name of the volume bar for a screen reader, for the bar that sits inside the open player: the desktop has the word in its
 * own catalogue and sharedUi has none, so the host that mounts the player provides it (null: the bar is unnamed).
 */
val LocalPlayerVolumeLabel = staticCompositionLocalOf<String?> { null }

/** The title: 14.5 sp, weight 800, the Orbit text face. */
private fun barTitleStyle(fonts: OrbitFonts) = fonts.title.copy(fontSize = 14.5.sp, fontWeight = FontWeight(800), lineHeight = 18.sp)

/** The artist: 12.5 sp. */
private fun barArtistStyle(fonts: OrbitFonts) = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp)

/** Shuffle, previous, next and repeat (or just previous and next): the kit's 36 dp round buttons, 4 dp apart. */
@Composable
private fun Transport(
    withShuffleAndRepeat: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    locked: Boolean,
    bar: BarInk,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onBlockedControl: () -> Unit,
) {
    val dimmed = Modifier.graphicsLayer { alpha = if (locked) LOCKED_ALPHA else 1f }
    Row(horizontalArrangement = Arrangement.spacedBy(OrbitBarMetrics.BUTTON_GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        if (withShuffleAndRepeat) {
            OrbitRoundButton(
                CosmoIcons.Shuffle, stringResource(if (shuffle) Res.string.shuffle_on else Res.string.shuffle_off),
                bar.ink, onToggleShuffle, size = BUTTON, on = shuffle, onInk = bar.fill,
            )
        }
        OrbitRoundButton(CosmoIcons.Previous, stringResource(Res.string.widget_previous), bar.ink, { if (locked) onBlockedControl() else onPrevious() }, dimmed, size = BUTTON)
        OrbitRoundButton(CosmoIcons.Next, stringResource(Res.string.widget_next), bar.ink, { if (locked) onBlockedControl() else onNext() }, dimmed, size = BUTTON)
        if (withShuffleAndRepeat) {
            OrbitRoundButton(
                if (repeatMode == RepeatModes.ONE) CosmoIcons.RepeatOne else CosmoIcons.Repeat,
                stringResource(
                    when (repeatMode) {
                        RepeatModes.OFF -> Res.string.repeat_off
                        RepeatModes.ONE -> Res.string.repeat_one
                        else -> Res.string.repeat_all
                    },
                ),
                bar.ink, onCycleRepeat, size = BUTTON, on = repeatMode != RepeatModes.OFF, onInk = bar.fill,
            )
        }
    }
}

/** Lyrics and queue (not for a live station), output and the volume: the end of the middle column's top row. */
@Composable
private fun Tools(
    live: Boolean,
    plan: PlayerBarMetrics.Plan,
    bar: BarInk,
    lyricsOn: Boolean,
    queueOn: Boolean,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onOutput: () -> Unit,
    volumeLabel: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(OrbitBarMetrics.BUTTON_GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!live) {
            OrbitRoundButton(CosmoIcons.Lyrics, stringResource(Res.string.open_lyrics), bar.ink, onLyrics, size = BUTTON, on = lyricsOn, onInk = bar.fill)
            OrbitRoundButton(CosmoIcons.Queue, stringResource(Res.string.queue), bar.ink, onQueue, size = BUTTON, on = queueOn, onInk = bar.fill)
        }
        if (plan.output) OrbitRoundButton(CosmoIcons.Devices, stringResource(Res.string.audio_output), bar.ink, onOutput, size = BUTTON)
        if (plan.volume) {
            Spacer(Modifier.width(OrbitBarMetrics.VOLUME_GAP_DP.dp))
            VolumeBar(volumeLabel ?: LocalPlayerVolumeLabel.current, bar.ink)
        }
    }
}

/**
 * Where the song is over its length, "1:52 / 4:05", in the mono label face (where the line's moon was dropped, until the player
 * gets there). It reads the playhead itself, so it alone recomposes with it.
 */
@Composable
private fun Clock(position: PlaybackPosition, durationMs: Long, scrub: PlayerScrub, fonts: OrbitFonts, ink: Color) {
    val shownMs = (scrub.shown(position.positionMs, durationMs) * durationMs).toLong()
    Text(
        "${clock(shownMs)} / ${clock(durationMs)}",
        style = fonts.label.copy(letterSpacing = 0.8.sp),
        color = ink.copy(alpha = 0.75f),
        maxLines = 1,
        modifier = Modifier.width(OrbitBarMetrics.CLOCK_DP.dp),
    )
}

/**
 * A running sleep timer, small beside the clock: the moon and "23:41" (or "End of song") in the bar's ink. A click opens the
 * timer's menu (the time left, Extend, Turn off). Nothing while no timer runs.
 */
@Composable
private fun SleepBadge(fonts: OrbitFonts, ink: Color, modifier: Modifier = Modifier) {
    val reading = com.cosmotune.ui.sleep.rememberSleepTimer() ?: return
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CosmoIcons.Timer, contentDescription = "Sleep timer", tint = ink.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(reading.short("End of song"), style = fonts.label.copy(letterSpacing = 0.8.sp), color = ink.copy(alpha = 0.85f), maxLines = 1)
        }
        com.cosmotune.ui.sleep.SleepTimerMenu(open, { open = false }, fonts, orbitLook())
    }
}

/** The volume: its glyph and a 92 dp compact [CosmoSlider] in the card's ink, on the host's system volume. */
@Composable
private fun VolumeBar(label: String?, ink: Color) {
    val volume = PlayerPlatform.host.volume
    val level by volume.level.collectAsStateWithLifecycle()
    Icon(if (level < 0.5f) CosmoIcons.VolumeLow else CosmoIcons.Volume, contentDescription = null, tint = ink, modifier = Modifier.size(OrbitBarMetrics.VOLUME_ICON_DP.dp))
    Spacer(Modifier.width(OrbitBarMetrics.VOLUME_GAP_DP.dp))
    CosmoSlider(level, onValueChange = volume::set, modifier = Modifier.width(OrbitBarMetrics.VOLUME_DP.dp), contentDescription = label, compact = true, ink = ink)
}

private val BUTTON = OrbitBarMetrics.BUTTON_DP.dp
private const val LOCKED_ALPHA = 0.5f

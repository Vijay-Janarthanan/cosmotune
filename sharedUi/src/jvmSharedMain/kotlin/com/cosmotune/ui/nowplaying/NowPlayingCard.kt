package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import com.cosmotune.music.ui.player.SystemVolume
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.audioFormatLabel
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.LivePlayhead
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerScrub
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.AlbumTitleArt
import com.cosmotune.ui.orbit.TitleArtAlbum
import com.cosmotune.design.TitleArt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape

/**
 * What the song card shows and does: the song, the playhead, the transport. Built once per recomposition of the screen; the
 * playhead is read only by the pieces that draw it ([progress], the label).
 */
internal class NowPlayingTransport(
    val song: Song,
    val coverUrl: String?,
    val isPlaying: Boolean,
    val position: PlaybackPosition,
    val durationMs: Long,
    val scrub: PlayerScrub,
    val liked: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val onPlayPause: () -> Unit,
    /** Previous and next as the person presses them: with their haptics, or the party's "the host has the controls". */
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleRepeat: () -> Unit,
    val onToggleLike: () -> Unit,
    val onSeekFraction: (Float) -> Unit,
    val onOpenArtist: () -> Unit,
    /** The song is buffering or its stream is being found: the play button wears a circling moon until it is in. */
    val isLoading: Boolean = false,
    /** The playhead run on between readings, so the ring and moon glide; without one they follow the readings (previews, tests). */
    val live: LivePlayhead? = null,
    /** Where the song is in the queue ("2 of 18"), for the card's eyebrow; null when there is no queue to count. */
    val queueLabel: String? = null,
) {
    /** Where the playhead is drawn (0..1): the dragged handle while scrubbing, else the song. Read it in draw. */
    fun progress(): Float = scrub.shown(live?.positionMs() ?: position.positionMs, durationMs)

    /** A seek from a line or the ring: held where it was dropped until the player catches up. */
    fun seek(fraction: Float) {
        scrub.drag(fraction)
        scrub.release(onSeekFraction)
    }
}

/** One round button of a card: [on] fills it; [menu] is drawn beside it, anchored to it (the screen button's popover). */
internal class CardButton(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val menu: (@Composable () -> Unit)? = null,
    /** May move into the card's More menu when the row has no room (shuffle, repeat, the video view); previous, next and like never do. */
    val movable: Boolean = false,
)

/** Which buttons stay in the row, which go to the More menu, and the size they are drawn at. */
internal class ButtonPlan(val inline: List<Int>, val overflow: List<Int>, val size: Dp, val gap: Dp)

/**
 * The row never overflows its [available] width. All the buttons if they fit; else the [movable] ones go to the More menu, in
 * order, until the rest and the More button fit; else the same at 36 dp with 4 dp gaps. Buttons that are not movable always stay.
 */
internal fun planButtons(movable: List<Boolean>, available: Dp, size: Dp, gap: Dp): ButtonPlan {
    fun fits(count: Int, s: Dp, g: Dp) = s * count + g * (count - 1).coerceAtLeast(0) <= available
    for ((s, g) in listOf(size to gap, 36.dp to 4.dp)) {
        var inline = movable.indices.toList()
        var over = emptyList<Int>()
        while (true) {
            if (fits(inline.size + if (over.isEmpty()) 0 else 1, s, g)) return ButtonPlan(inline, over, s, g)
            val next = inline.firstOrNull { movable[it] } ?: break
            inline = inline - next
            over = over + next
        }
    }
    return ButtonPlan(movable.indices.filter { !movable[it] }, movable.indices.filter { movable[it] }, 36.dp, 4.dp)
}

/**
 * The card's round buttons in one row that never overflows. With [available] (the width the row has) the [movable] ones go into a
 * More menu when it is short of room: each is an item showing its on state (solid), one tap away; the More button is itself solid
 * while any of them is on.
 */
@Composable
private fun CardButtons(buttons: List<CardButton>, ink: Color, fill: Color, size: Dp, gap: Dp, available: Dp? = null) {
    val plan = if (available == null) ButtonPlan(buttons.indices.toList(), emptyList(), size, gap) else planButtons(buttons.map { it.movable }, available, size, gap)
    Row(horizontalArrangement = Arrangement.spacedBy(plan.gap), verticalAlignment = Alignment.CenterVertically) {
        plan.inline.map { buttons[it] }.forEach { b ->
            Box {
                OrbitRoundButton(
                    b.icon, b.label, if (b.enabled) ink else ink.copy(alpha = 0.4f), onClick = { if (b.enabled) b.onClick() },
                    size = plan.size, on = b.on, onInk = fill,
                )
                b.menu?.invoke()
            }
        }
        if (plan.overflow.isNotEmpty()) {
            val moved = plan.overflow.map { buttons[it] }
            var open by remember { mutableStateOf(false) }
            Box(Modifier.testTag("card:more")) {
                OrbitRoundButton(CosmoIcons.More, "More", ink, onClick = { open = !open }, size = plan.size, on = open || moved.any { it.on }, onInk = fill)
                OrbitMenu(
                    open, { open = false },
                    moved.map { b -> OrbitMenuItem(b.label, icon = b.icon, selected = b.on, enabled = b.enabled, keepOpen = b.menu == null, onClick = b.onClick) },
                    orbitFonts(), orbitLook(), width = 220.dp,
                )
                moved.forEach { it.menu?.invoke() }
            }
        }
    }
}

/** "NOW ORBITING · 2 OF 18" (or PAUSED): the card's eyebrow. The time is the seek line's ([CardTimes]). */
@Composable
private fun OrbitingLabel(t: NowPlayingTransport, fonts: OrbitFonts, ink: Color, modifier: Modifier = Modifier) {
    val head = if (t.isPlaying) "NOW ORBITING" else "PAUSED"
    val text = t.queueLabel?.let { "$head · ${it.uppercase()}" } ?: head
    Text(text, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * The seek line's time row (the Now Playing board, B1 and D1): the time played at the start, the measured quality in the middle
 * ([quality], "FLAC · 24/96"; a tap opens the audio details) and the song's length at the end. The only text that reads the
 * playhead, so it alone recomposes as the song moves; while the line is dragged it shows where the drop will land.
 */
@Composable
private fun CardTimes(t: NowPlayingTransport, fonts: OrbitFonts, ink: Color, quality: (@Composable (Color) -> Unit)?, modifier: Modifier = Modifier) {
    val at = t.position.positionMs
    val ms = if (t.durationMs > 0) (t.scrub.shown(at, t.durationMs) * t.durationMs).toLong() else at
    Box(modifier.fillMaxWidth().testTag("nowplaying:card:times")) {
        val style = fonts.label.copy(letterSpacing = 0.4.sp)
        Text(clock(ms), style = style, color = ink.copy(alpha = 0.75f), modifier = Modifier.align(Alignment.CenterStart))
        if (quality != null) Box(Modifier.align(Alignment.Center)) { quality(ink) }
        if (t.durationMs > 0) Text(clock(t.durationMs), style = style, color = ink.copy(alpha = 0.75f), modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** The stream's format in the label face ("FLAC · 44.1 KHZ"); a tap opens the audio details. Nothing until the decoder says. */
@Composable
internal fun OrbitFormatLabel(fonts: OrbitFonts, ink: Color, modifier: Modifier = Modifier) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val label = stats?.audioFormatLabel() ?: return
    val open = LocalOpenAudioDetails.current
    Text(
        label.uppercase(), style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = open != null, role = Role.Button) { open?.invoke() }.padding(vertical = 4.dp),
    )
}

/** How [OrbitVolume] is drawn: the speaker and a 104 dp line, the speaker and a 72 dp line, or the speaker alone as a button. */
internal enum class VolumeStyle { Full, Compact }

/**
 * The mouse wheel over a volume control: a notch up raises it, a notch down lowers it, by 5 %. [level] is read when the wheel
 * turns, not when composed, so the modifier does not restart as the level moves.
 */
internal fun Modifier.volumeWheel(level: () -> Float, set: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) {
                val change = event.changes.firstOrNull() ?: continue
                set((level() - change.scrollDelta.y * VOLUME_WHEEL_STEP).coerceIn(0f, 1f))
                change.consume()
            }
        }
    }
}

private const val VOLUME_WHEEL_STEP = 0.05f

/**
 * The host's volume as a short line with its moon, the speaker before it (desktop). [source] is the volume to drive; null is the
 * host's own. The mouse wheel over it changes the volume.
 */
@Composable
internal fun OrbitVolume(ink: Color, modifier: Modifier = Modifier, style: VolumeStyle = VolumeStyle.Full, source: SystemVolume? = null) {
    val volume = source ?: PlayerPlatform.host.volume
    val level by volume.level.collectAsStateWithLifecycle()
    Row(modifier.volumeWheel({ level }, volume::set).testTag("volume:line"), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (level < 0.4f) CosmoIcons.VolumeLow else CosmoIcons.Volume, "Volume", tint = ink.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        OrbitProgressLine({ level }, ink, Modifier.width(if (style == VolumeStyle.Full) 104.dp else 72.dp), onSeek = volume::set, moon = 5.dp, live = true)
    }
}

/**
 * The speaker as a round button, for a card with no room for even the short line: a press opens the line in a popover under it.
 * The mouse wheel over the speaker changes the volume without opening it.
 */
@Composable
internal fun OrbitVolumeButton(ink: Color, fill: Color, size: Dp, modifier: Modifier = Modifier, source: SystemVolume? = null) {
    val volume = source ?: PlayerPlatform.host.volume
    val level by volume.level.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box(modifier.testTag("volume:button")) {
        OrbitRoundButton(
            if (level < 0.4f) CosmoIcons.VolumeLow else CosmoIcons.Volume, "Volume", ink, onClick = { open = !open },
            size = size, on = open, onInk = fill, modifier = Modifier.volumeWheel({ level }, volume::set),
        )
        if (open) {
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, with(LocalDensity.current) { (size + 6.dp).roundToPx() }), onDismissRequest = { open = false }) {
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(fill.copy(alpha = 1f)).padding(horizontal = 16.dp, vertical = 14.dp).volumeWheel({ level }, volume::set)) {
                    OrbitProgressLine({ level }, ink, Modifier.width(140.dp), onSeek = volume::set, moon = 5.dp, live = true)
                }
            }
        }
    }
}

/**
 * The song card (Home's hero card, board sections 1, 3 and 5): the cover's colour, flat, with play / pause in the bite. The mono
 * "NOW ORBITING" line, the title in the wide face and the artists (a tap opens the artist).
 *
 * [wide] (the desktop with a video, section 1): the title on the left and, on its right, [buttons] with the [volume] at the end of
 * the same row; the [format] under the artists and the seek line across the foot. Otherwise (the record, the phone, sections 3
 * and 5): one column, the format under the artists, the seek line when [showLine], the buttons along the foot with the volume at
 * their end. In both, whatever sits beside the bite (the seek line, its times and the buttons) ends [BITE_GAP] before the bite's
 * ring, so nothing runs under play / pause.
 */
@Composable
internal fun NowPlayingSongCard(
    t: NowPlayingTransport,
    fonts: OrbitFonts,
    look: OrbitLook,
    buttons: List<CardButton>,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    showLine: Boolean = true,
    format: Boolean = false,
    volume: Boolean = false,
    buttonSize: Dp = 40.dp,
    /** The volume to drive; null is the host's own. For tests and hosts that bring their own. */
    volumeSource: SystemVolume? = null,
    /** The one live lyric line above the seek line, drawn in the card's ink; null for none. */
    lyricLine: (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? = null,
    /** The measured quality in the time row under the seek line, in the card's ink; null for none. */
    quality: (@Composable (Color) -> Unit)? = null,
) {
    val (fill, ink) = coverFill(t.coverUrl, t.song.title)
    OrbitBiteCard(fill, modifier, bite = { NowPlayingPlayButton(t, look, 66.dp, HERO_BITE_RING) }) {
        if (wide) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = WIDE_PAD, top = 22.dp, end = WIDE_PAD, bottom = 22.dp)) {
                val clear = BITE_CLEAR - WIDE_PAD
                // The controls' row takes what the title leaves it; the volume shortens, then turns into the speaker button,
                // before any button moves into More.
                val room = maxWidth - clear - WIDE_TITLE_MIN - 16.dp
                val fit = if (volume) volumeFit(room, buttons.size, buttonSize, WIDE_GAP) else null
                Row(Modifier.fillMaxWidth().padding(end = clear)) {
                    Column(Modifier.weight(1f)) {
                        OrbitingLabel(t, fonts, ink)
                        Spacer(Modifier.height(14.dp))
                        CardTitle(t, fonts, ink, fill, 30, maxLines = 1)
                        if (lyricLine != null) Box(Modifier.padding(top = 8.dp)) { lyricLine(ink) }
                        else if (format && quality == null) OrbitFormatLabel(fonts, ink, Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Row(Modifier.padding(top = 12.dp).testTag("nowplaying:card:controls"), verticalAlignment = Alignment.CenterVertically) {
                        CardButtons(buttons, ink, fill, buttonSize, WIDE_GAP, available = room - (fit?.width(buttonSize) ?: 0.dp))
                        if (fit != null) CardVolume(fit, ink, fill, buttonSize, volumeSource)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(end = clear + SEEK_MOON_REACH).testTag("nowplaying:card:seek")) {
                    SongSeekLine(t, ink, fonts)
                    CardTimes(t, fonts, ink, quality, Modifier.padding(top = 6.dp))
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The volume is always there. At the end of the buttons' row where they leave room for it; otherwise at the top
                // right on the label's row, as the short line where that row is wide enough and as the speaker button (its line
                // in a popover) where not.
                val inner = maxWidth - COLUMN_PAD * 2
                val clear = BITE_CLEAR - COLUMN_PAD
                val footer = inner - clear
                val roomBesideButtons = footer >= buttonSize * buttons.size + 8.dp * buttons.size + VOLUME_WIDTH
                val topRight = volume && !roomBesideButtons
                Column(Modifier.fillMaxWidth().padding(COLUMN_PAD)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OrbitingLabel(t, fonts, ink, Modifier.weight(1f))
                        if (topRight) {
                            Spacer(Modifier.width(8.dp))
                            if (inner >= COMPACT_VOLUME_MIN_WIDTH) OrbitVolume(ink, style = VolumeStyle.Compact, source = volumeSource)
                            else OrbitVolumeButton(ink, fill, 32.dp, source = volumeSource)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    CardTitle(t, fonts, ink, fill, 26, maxLines = 2)
                    if (format && quality == null) OrbitFormatLabel(fonts, ink, Modifier.padding(top = 6.dp))
                    if (lyricLine != null) {
                        Spacer(Modifier.height(12.dp))
                        lyricLine(ink)
                    }
                    if (showLine) {
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.padding(end = clear + SEEK_MOON_REACH).testTag("nowplaying:card:seek")) {
                            SongSeekLine(t, ink, fonts)
                            CardTimes(t, fonts, ink, quality, Modifier.padding(top = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().padding(end = clear).testTag("nowplaying:card:controls"), verticalAlignment = Alignment.CenterVertically) {
                        CardButtons(buttons, ink, fill, buttonSize, 8.dp, available = footer)
                        if (volume && roomBesideButtons) {
                            Spacer(Modifier.weight(1f))
                            OrbitVolume(ink, Modifier.padding(start = 12.dp, end = SEEK_MOON_REACH), source = volumeSource)
                        }
                    }
                }
            }
        }
    }
}

/** How the volume fits at the end of the wide card's row: the full line, the short line, or the speaker button. */
internal enum class VolumeFit {
    Line, Short, Button;

    /** What it takes in the row, with the gap before it. */
    fun width(buttonSize: Dp): Dp = when (this) {
        Line -> VOLUME_GAP + 18.dp + 8.dp + 104.dp + SEEK_MOON_REACH
        Short -> VOLUME_GAP + 18.dp + 8.dp + 72.dp + SEEK_MOON_REACH
        Button -> VOLUME_GAP + buttonSize
    }
}

/** The roomiest volume that still leaves [count] buttons of [size] (with [gap]s) in [room]; the speaker button when none does. */
internal fun volumeFit(room: Dp, count: Int, size: Dp, gap: Dp): VolumeFit {
    val buttons = size * count + gap * (count - 1).coerceAtLeast(0)
    return listOf(VolumeFit.Line, VolumeFit.Short).firstOrNull { buttons + it.width(size) <= room } ?: VolumeFit.Button
}

@Composable
private fun CardVolume(fit: VolumeFit, ink: Color, fill: Color, buttonSize: Dp, source: SystemVolume?) {
    Spacer(Modifier.width(VOLUME_GAP))
    when (fit) {
        // Its line ends where the seek line does, its moon's reach short of the row's end.
        VolumeFit.Line -> OrbitVolume(ink, Modifier.padding(end = SEEK_MOON_REACH), source = source)
        VolumeFit.Short -> OrbitVolume(ink, Modifier.padding(end = SEEK_MOON_REACH), style = VolumeStyle.Compact, source = source)
        VolumeFit.Button -> OrbitVolumeButton(ink, fill, buttonSize, source = source)
    }
}

/** The song card's inner padding, wide and in one column, and the gaps in the wide card's row. */
private val WIDE_PAD = 24.dp
private val COLUMN_PAD = 22.dp
private val WIDE_GAP = 6.dp
private val VOLUME_GAP = 12.dp

/** The narrowest the wide card lets its title column get before its controls give way. */
private val WIDE_TITLE_MIN = 240.dp

/** The air between what sits beside the bite and the bite's ring. */
internal val BITE_GAP = 16.dp

/**
 * How far from the card's end edge the seek line, its times and the buttons stop: the bite's ring (its centre 46 dp in, 41 dp
 * round: [com.cosmotune.ui.orbit.HeroBite]) and [BITE_GAP] before it.
 */
internal val BITE_CLEAR = 46.dp + 41.dp + BITE_GAP

/**
 * How far the seek line's moon (6 dp, with its 4 dp halo) reaches past the line's end at the song's last second: the line stops
 * that much sooner, so even then the moon keeps [BITE_GAP] from the ring. Its times stand under the line's own ends.
 */
private val SEEK_MOON_REACH = 10.dp

/**
 * The phone's song card (the Now Playing board, B1 to B3): the cover's colour, flat, no bite. At the top the mono eyebrow, the
 * title and the artists, with [like] and [more] (the rarer actions) beside them; then the line being sung ([lyricLine]), the seek
 * line with the time played, the measured quality ([quality]) and the length; and at the foot back, play and next in one row,
 * play in the middle in a ring of the page's own ground (the bite moved off the corner, so the three read in order).
 */
@Composable
internal fun NowPlayingPhoneCard(
    t: NowPlayingTransport,
    fonts: OrbitFonts,
    look: OrbitLook,
    like: CardButton,
    previous: CardButton,
    next: CardButton,
    more: @Composable (ink: Color, fill: Color) -> Unit,
    modifier: Modifier = Modifier,
    lyricLine: (@Composable (Color) -> Unit)? = null,
    quality: (@Composable (Color) -> Unit)? = null,
) {
    val (fill, ink) = coverFill(t.coverUrl, t.song.title)
    Column(modifier.clip(RoundedCornerShape(PHONE_CARD_RADIUS)).background(fill).padding(20.dp).testTag("nowplaying:phone-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                OrbitingLabel(t, fonts, ink)
                Spacer(Modifier.height(10.dp))
                CardTitle(t, fonts, ink, fill, 26, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CardButtons(listOf(like), ink, fill, 40.dp, 6.dp)
                more(ink, fill)
            }
        }
        if (lyricLine != null) {
            Spacer(Modifier.height(12.dp))
            lyricLine(ink)
        }
        Spacer(Modifier.height(16.dp))
        SongSeekLine(t, ink, fonts)
        CardTimes(t, fonts, ink, quality, Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().testTag("nowplaying:transport"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CardButtons(listOf(previous), ink, fill, 52.dp, 0.dp)
            Spacer(Modifier.width(36.dp))
            // Play in the middle, in a ring of the page round it (the board's bite, moved off the corner).
            Box(Modifier.size(PLAY_RING).background(look.ground, CircleShape), contentAlignment = Alignment.Center) {
                NowPlayingPlayButton(t, look, 72.dp, PLAY_RING)
            }
            Spacer(Modifier.width(36.dp))
            CardButtons(listOf(next), ink, fill, 52.dp, 0.dp)
        }
    }
}

/** The phone card's corner, and the ring of page round its play button. */
private val PHONE_CARD_RADIUS = 30.dp
private val PLAY_RING = 84.dp

/**
 * The card's heading: the song's title as the album's title art (the lyrics-titles board's placement table: Cover Cut, or Voiced
 * type when the album has a voice), in the room the plain title had ([maxLines] lines of [sizeSp] + 2), then the artists.
 */
@Composable
private fun CardTitle(t: NowPlayingTransport, fonts: OrbitFonts, ink: Color, fill: Color, sizeSp: Int, maxLines: Int) {
    AlbumTitleArt(
        TitleArtAlbum(t.song.title, t.coverUrl, artist = t.song.artist, album = t.song.albumName), ink = ink, ground = fill,
        modifier = Modifier.fillMaxWidth().testTag("nowplaying:card:title"), place = TitleArt.Place.HEADING,
        maxSize = (sizeSp + 8).sp, minSize = (sizeSp - 8).sp, maxLines = maxLines, maxHeight = ((sizeSp + 2) * maxLines).dp,
    )
    Text(
        t.song.artist, style = fonts.body.copy(fontSize = 15.sp), color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = t.onOpenArtist),
    )
}

/**
 * Play / pause in a card's bite, and while the song loads ([NowPlayingTransport.isLoading]) the moon circling it in the bite's
 * clear ring, as the player bar and the mini player show it: a press that is still on its way never looks like one that did
 * nothing. [ring] is the bite's own diameter, so the orbit runs in the gap between the button and the card.
 */
@Composable
internal fun NowPlayingPlayButton(t: NowPlayingTransport, look: OrbitLook, size: Dp, ring: Dp) {
    // The button's own size, so the bite places the button where it always was; the ring spills out round it, centred.
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (t.isLoading) {
            OrbitRing(ring, look.accent, progress = null, spinning = true, stroke = 1.5.dp, moon = 3.dp,
                modifier = Modifier.requiredSize(ring).semantics { contentDescription = "Loading" })
        }
        OrbitPlayButton(t.isPlaying, t.onPlayPause, look, size = size)
    }
}

/** The hero bite's diameter: [com.cosmotune.ui.orbit.HeroBite]'s 41 dp radius. */
internal val HERO_BITE_RING = 82.dp

/** What the volume takes beside the buttons: the speaker, the gap and the line. */
private val VOLUME_WIDTH = 152.dp

/** The card width (inside its padding) from which the short volume line fits on the label's row; below it the speaker is a button. */
private val COMPACT_VOLUME_MIN_WIDTH = 300.dp

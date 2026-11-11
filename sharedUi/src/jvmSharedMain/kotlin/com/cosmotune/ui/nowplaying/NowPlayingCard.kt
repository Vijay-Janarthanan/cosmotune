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
import com.cosmotune.ui.orbit.BiteShape
import com.cosmotune.ui.orbit.Disc
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

/** "NOW ORBITING · 0:58 / 4:19" (or PAUSED): the only text that reads the playhead, so it alone recomposes as the song moves. */
@Composable
private fun OrbitingLabel(t: NowPlayingTransport, fonts: OrbitFonts, ink: Color, modifier: Modifier = Modifier) {
    val head = if (t.isPlaying) "NOW ORBITING" else "PAUSED"
    val text = if (t.durationMs > 0) {
        val ms = (t.scrub.shown(t.position.positionMs, t.durationMs) * t.durationMs).toLong()
        "$head · ${clock(ms)} / ${clock(t.durationMs)}"
    } else head
    Text(text, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
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
 * [wide] (the desktop with a video, section 1): the title on the left, [buttons] top right, the [volume] under them, the
 * [format] under the artists and the seek line across the foot. Otherwise (the record, the phone, sections 3 and 5): one column,
 * the format under the artists, the seek line when [showLine], the buttons along the foot beside the bite and the volume after them.
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
) {
    val (fill, ink) = coverFill(t.coverUrl, t.song.title)
    OrbitBiteCard(fill, modifier, bite = { NowPlayingPlayButton(t, look, 66.dp, HERO_BITE_RING) }) {
        if (wide) {
            Box(Modifier.fillMaxSize().padding(start = 24.dp, top = 22.dp, end = 24.dp, bottom = 22.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        OrbitingLabel(t, fonts, ink)
                        Spacer(Modifier.height(14.dp))
                        CardTitle(t, fonts, ink, fill, 30, maxLines = 1)
                        if (format) OrbitFormatLabel(fonts, ink, Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.padding(end = 64.dp, top = 12.dp), horizontalAlignment = Alignment.End) {
                        CardButtons(buttons, ink, fill, buttonSize, 6.dp)
                        if (volume) OrbitVolume(ink, Modifier.padding(top = 14.dp), source = volumeSource)
                    }
                }
                OrbitProgressLine(t::progress, ink, Modifier.align(Alignment.BottomStart).padding(end = 64.dp), onSeek = t::seek)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The volume is always there. Beside the buttons where they leave room for it; otherwise at the top right on the
                // label's row, as the short line where that row is wide enough and as the speaker button (its line in a popover) where not.
                val inner = maxWidth - 44.dp
                val footer = inner - 70.dp
                val roomBesideButtons = footer >= buttonSize * buttons.size + 8.dp * buttons.size + VOLUME_WIDTH
                val topRight = volume && !roomBesideButtons
                Column(Modifier.fillMaxWidth().padding(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 22.dp)) {
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
                    if (format) OrbitFormatLabel(fonts, ink, Modifier.padding(top = 6.dp))
                    if (showLine) {
                        Spacer(Modifier.height(16.dp))
                        OrbitProgressLine(t::progress, ink, onSeek = t::seek)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().padding(end = 70.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardButtons(buttons, ink, fill, buttonSize, 8.dp, available = footer)
                        if (volume && roomBesideButtons) {
                            Spacer(Modifier.weight(1f))
                            OrbitVolume(ink, Modifier.padding(start = 12.dp), source = volumeSource)
                        }
                    }
                }
            }
        }
    }
}

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

/** The bites' diameters: [com.cosmotune.ui.orbit.HeroBite]'s 41 dp and [SlimBite]'s 38 dp radius. */
internal val HERO_BITE_RING = 82.dp
internal val SLIM_BITE_RING = 76.dp

/** What the volume takes beside the buttons: the speaker, the gap and the line. */
private val VOLUME_WIDTH = 150.dp

/** The card width (inside its padding) from which the short volume line fits on the label's row; below it the speaker is a button. */
private val COMPACT_VOLUME_MIN_WIDTH = 300.dp

/** The slim card's bite: in the middle of its end edge (Cinema, board section 2). */
private val SlimBite = BiteShape(corner = 30.dp, bite = 38.dp, inset = 56.dp)

/**
 * The slim song card of Cinema (board section 2): 112 dp, the cover's colour, the disc with its orbit (the progress), the label,
 * the title and the artists, [buttons] at the end and play / pause in the bite in the middle of the end edge.
 */
@Composable
internal fun NowPlayingSlimCard(
    t: NowPlayingTransport, fonts: OrbitFonts, look: OrbitLook, buttons: List<CardButton>, modifier: Modifier = Modifier,
    volume: Boolean = false, volumeSource: SystemVolume? = null,
) {
    val (fill, ink) = coverFill(t.coverUrl, t.song.title)
    OrbitBiteCard(
        fill.copy(alpha = 0.95f), modifier.height(112.dp), shape = SlimBite, biteInset = 24.dp,
        bite = { NowPlayingPlayButton(t, look, 64.dp, SLIM_BITE_RING) },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 104.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Disc(t.coverUrl, 68.dp)
                OrbitRing(84.dp, ink, progress = t::progress, spinning = t.isPlaying, stroke = 2.dp, moon = 4.5.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                OrbitingLabel(t, fonts, ink)
                // Small title art in the slim card (the board's placement table), in the one line the plain title had.
                AlbumTitleArt(
                    TitleArtAlbum(t.song.title, t.coverUrl, artist = t.song.artist, album = t.song.albumName), ink = ink, ground = fill,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(), place = TitleArt.Place.SLIM,
                    maxSize = 26.sp, minSize = 16.sp, maxLines = 1, maxHeight = 26.dp,
                )
                Text(t.song.artist, style = fonts.body.copy(fontSize = 13.sp), color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(12.dp))
            if (volume) {
                OrbitVolumeButton(ink, fill, 40.dp, source = volumeSource)
                Spacer(Modifier.width(6.dp))
            }
            CardButtons(buttons, ink, fill, 40.dp, 6.dp)
        }
    }
}

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.audioFormatLabel
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerScrub
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.BiteShape
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.coverFill
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
) {
    /** Where the playhead is drawn (0..1): the dragged handle while scrubbing, else the song. Read it in draw. */
    fun progress(): Float = scrub.shown(position.positionMs, durationMs)

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
)

@Composable
private fun CardButtons(buttons: List<CardButton>, ink: Color, fill: Color, size: Dp, gap: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        buttons.forEach { b ->
            Box {
                OrbitRoundButton(
                    b.icon, b.label, if (b.enabled) ink else ink.copy(alpha = 0.4f), onClick = { if (b.enabled) b.onClick() },
                    size = size, on = b.on, onInk = fill,
                )
                b.menu?.invoke()
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

/** The host's volume as a short line with its moon, the speaker before it (desktop). */
@Composable
internal fun OrbitVolume(ink: Color, modifier: Modifier = Modifier) {
    val volume = PlayerPlatform.host.volume
    val level by volume.level.collectAsStateWithLifecycle()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (level < 0.4f) CosmoIcons.VolumeLow else CosmoIcons.Volume, "Volume", tint = ink.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        OrbitProgressLine({ level }, ink, Modifier.width(104.dp), onSeek = volume::set, moon = 5.dp, live = true)
    }
}

/**
 * The song card (Home's hero card, board sections 1, 3 and 5): the cover's colour, flat, with play / pause in the bite. The mono
 * "NOW ORBITING" line, the title in the wide face and the artists (a tap opens the artist).
 *
 * [wide] (the desktop with a video, section 1): the title on the left, [buttons] top right, the [volume] under them, the
 * [format] under the artists and the seek line across the foot. Otherwise (the record, the phone, sections 3 and 5): one column,
 * the seek line when [showLine], the buttons along the foot beside the bite and the format after them.
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
) {
    val (fill, ink) = coverFill(t.coverUrl, t.song.title)
    OrbitBiteCard(fill, modifier, bite = { OrbitPlayButton(t.isPlaying, t.onPlayPause, look) }) {
        if (wide) {
            Box(Modifier.fillMaxSize().padding(start = 24.dp, top = 22.dp, end = 24.dp, bottom = 22.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        OrbitingLabel(t, fonts, ink)
                        Spacer(Modifier.height(14.dp))
                        CardTitle(t, fonts, ink, 30, maxLines = 1)
                        if (format) OrbitFormatLabel(fonts, ink, Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.padding(end = 64.dp, top = 12.dp), horizontalAlignment = Alignment.End) {
                        CardButtons(buttons, ink, fill, buttonSize, 6.dp)
                        if (volume) OrbitVolume(ink, Modifier.padding(top = 14.dp))
                    }
                }
                OrbitProgressLine(t::progress, ink, Modifier.align(Alignment.BottomStart).padding(end = 64.dp), onSeek = t::seek)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 22.dp)) {
                OrbitingLabel(t, fonts, ink)
                Spacer(Modifier.height(12.dp))
                CardTitle(t, fonts, ink, 26, maxLines = 2)
                if (showLine) {
                    Spacer(Modifier.height(16.dp))
                    OrbitProgressLine(t::progress, ink, onSeek = t::seek)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(end = 70.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardButtons(buttons, ink, fill, buttonSize, 8.dp)
                    if (format) OrbitFormatLabel(fonts, ink, Modifier.padding(start = 12.dp).weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
private fun CardTitle(t: NowPlayingTransport, fonts: OrbitFonts, ink: Color, sizeSp: Int, maxLines: Int) {
    Text(t.song.title, style = fonts.heroTitle.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp + 2).sp), color = ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    Text(
        t.song.artist, style = fonts.body.copy(fontSize = 15.sp), color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = t.onOpenArtist),
    )
}

/** The slim card's bite: in the middle of its end edge (Cinema, board section 2). */
private val SlimBite = BiteShape(corner = 30.dp, bite = 38.dp, inset = 56.dp)

/**
 * The slim song card of Cinema (board section 2): 112 dp, the cover's colour, the disc with its orbit (the progress), the label,
 * the title and the artists, [buttons] at the end and play / pause in the bite in the middle of the end edge.
 */
@Composable
internal fun NowPlayingSlimCard(t: NowPlayingTransport, fonts: OrbitFonts, look: OrbitLook, buttons: List<CardButton>, modifier: Modifier = Modifier) {
    val (fill, ink) = coverFill(t.coverUrl, t.song.title)
    OrbitBiteCard(
        fill.copy(alpha = 0.95f), modifier.height(112.dp), shape = SlimBite, biteInset = 24.dp,
        bite = { OrbitPlayButton(t.isPlaying, t.onPlayPause, look, size = 64.dp) },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 104.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Disc(t.coverUrl, 68.dp)
                OrbitRing(84.dp, ink, progress = t::progress, spinning = t.isPlaying, stroke = 2.dp, moon = 4.5.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                OrbitingLabel(t, fonts, ink)
                Text(t.song.title, style = fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 24.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(t.song.artist, style = fonts.body.copy(fontSize = 13.sp), color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(12.dp))
            CardButtons(buttons, ink, fill, 40.dp, 6.dp)
        }
    }
}

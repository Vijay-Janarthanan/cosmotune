package com.cosmotune.ui.orbit

import com.cosmotune.music.ui.player.bleedHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalReducedMotion

/**
 * The flat list row's numbers (the shared board's A4): Comfortable rows are 60 dp with a 48 dp disc, Compact rows 52 dp with a 40 dp
 * disc (Look and colours, Row size). The disc stands in a box 8 dp wider, the room of the playing row's orbit and moon.
 */
object OrbitRowMetrics {
    const val COMFORTABLE_DP = 60f
    const val COMPACT_DP = 52f
    const val DISC_DP = 48f
    const val COMPACT_DISC_DP = 40f
    /** The disc's box is this much wider than the disc: the orbit drawn round the playing one. */
    const val ORBIT_ROOM_DP = 8f
    /** Between the disc's box and the words. */
    const val GAP_DP = 10f
}

/** Whether the person chose Compact rows (Look and colours, Row size); false where no shell is provided (a preview, a test). */
@Composable
fun orbitCompactRows(): Boolean = LocalCosmoShell.current?.settings?.compactRows?.collectAsState()?.value == true

/** The height of a list row: 60 dp, or 52 dp with Compact rows. */
@Composable
fun orbitRowHeight(): Dp = (if (orbitCompactRows()) OrbitRowMetrics.COMPACT_DP else OrbitRowMetrics.COMFORTABLE_DP).dp

/** The disc of a list row: 48 dp, or 40 dp with Compact rows. */
@Composable
fun orbitRowDisc(): Dp = (if (orbitCompactRows()) OrbitRowMetrics.COMPACT_DISC_DP else OrbitRowMetrics.DISC_DP).dp

/**
 * The Orbit list row, the only one (Orbit rule 8): flat, with no card behind it, [height] tall (60 dp, 52 with Compact rows); a disc
 * of [cover] at the start ([discSize], 48 or 40 dp, in a box 8 dp wider), or the track's [number] in its place on an album, the title
 * over the subtitle, and [trailing] at the end (one button, a handle) after [trailingText] (a duration). The [now] row (the song that
 * plays) is marked by its peach title and the orbit round its disc, which moves with [progress] when given and circles slowly while
 * [playing] otherwise; a numbered row's number becomes peach bars instead. Nothing is washed or filled, so the list stays flat.
 *
 * [leading] goes before the disc (a drag handle, a rank). [discContent] replaces the disc (a selection mark, a spinner, a logo) and
 * with it the orbit. [icon] stands in for a cover (an artist, an album). [titleContent] replaces the title line (an explicit mark) and
 * [subtitleLeading] goes before the subtitle (a quality pill, a saved mark). A hold, or a right click on the desktop, is [onLongClick];
 * a null [onClick] with no [onLongClick] makes the row inert, and [enabled] false does the same. [endPadding] is the row's end gutter.
 */
@Composable
fun OrbitSongRow(
    title: String,
    subtitle: String,
    cover: String?,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    now: Boolean = false,
    playing: Boolean = false,
    progress: (() -> Float?)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    height: Dp = orbitRowHeight(),
    icon: ImageVector? = null,
    endPadding: Dp = 0.dp,
    discSize: Dp = orbitRowDisc(),
    gap: Dp = OrbitRowMetrics.GAP_DP.dp,
    number: Int? = null,
    titleContent: (@Composable () -> Unit)? = null,
    subtitleLeading: (@Composable () -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    discContent: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        // The card a press (or the desktop's pointer) lights reaches [ROW_CARD_BLEED] past the row on both sides, so the disc and the
        // row's end keep air inside it while the words stay on the page's edge.
        modifier.height(height).bleedHorizontally(ROW_CARD_BLEED).clip(ROW_PRESS_SHAPE)
            .then(
                if (onClick != null || onLongClick != null) Modifier.longPressMenuClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick, enabled = enabled)
                else Modifier,
            )
            .contextClick(onLongClick.takeIf { enabled })
            // A screen reader hears which row plays, as the eye sees its peach.
            .then(if (now) Modifier.semantics { stateDescription = "Now playing" } else Modifier)
            .padding(start = ROW_CARD_BLEED, end = ROW_CARD_BLEED + endPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        val box = discSize + OrbitRowMetrics.ORBIT_ROOM_DP.dp
        Box(Modifier.size(box), contentAlignment = Alignment.Center) {
            when {
                discContent != null -> discContent()
                number != null -> if (now) OrbitPlayingBars(look.accent, playing) else OrbitTrackNumber(number, look)
                icon != null -> {
                    Box(Modifier.size(discSize).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = look.ink, modifier = Modifier.size(20.dp))
                    }
                    if (now) OrbitRing(box, look.accent, progress = progress, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
                }
                else -> {
                    Disc(cover, discSize)
                    if (now) OrbitRing(box, look.accent, progress = progress, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
                }
            }
        }
        Spacer(Modifier.width(gap))
        Column(Modifier.weight(1f)) {
            if (titleContent != null) titleContent()
            else Text(title, style = fonts.title, color = if (now) look.accent else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitleLeading != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subtitleLeading()
                    if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailingText?.let { Text(it, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp)) }
        trailing?.invoke(this)
    }
}

/** What a press lights under a flat row: the row's own bounds, softly rounded (nothing is drawn there at rest). */
private val ROW_PRESS_SHAPE = RoundedCornerShape(16.dp)

/** How far a row's lit card reaches past the row on each side. */
internal val ROW_CARD_BLEED = 8.dp

/** A duration at a row's end, in the mono label face. */
@Composable
fun RowScope.OrbitRowTime(text: String?, fonts: OrbitFonts, look: OrbitLook) {
    if (text != null) Text(text, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp))
}

/** A track's number in a numbered row's disc slot: mono, 13 sp, the page's dim ink, centred. */
@Composable
fun OrbitTrackNumber(number: Int, look: OrbitLook, modifier: Modifier = Modifier) {
    Text(
        "$number", style = TRACK_NUMBER, color = look.dim, textAlign = TextAlign.Center, maxLines = 1,
        modifier = modifier.semantics { stateDescription = "Track $number" },
    )
}

private val TRACK_NUMBER = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

/**
 * The playing mark of a numbered row: four peach bars where the number was. They rise and fall while [playing] (still under reduced
 * motion, and still while paused).
 */
@Composable
fun OrbitPlayingBars(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val moving = playing && !LocalReducedMotion.current
    val phase = if (moving) {
        rememberInfiniteTransition(label = "bars").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "bars-phase")
    } else null
    Canvas(modifier.size(width = 22.dp, height = 16.dp)) {
        val w = 3.5.dp.toPx()
        val gap = (size.width - w * 4) / 3
        BAR_REST.forEachIndexed { i, rest ->
            val p = phase?.value
            val h = if (p == null) rest else (rest + (BAR_SWING[i] * (if (i % 2 == 0) p else 1f - p))).coerceIn(0.2f, 1f)
            val bar = size.height * h
            drawRoundRect(color, Offset(i * (w + gap), size.height - bar), Size(w, bar), CornerRadius(w / 2, w / 2))
        }
    }
}

/** The bars' heights at rest (of 16 dp: 9, 16, 6, 12) and how far each swings while playing. */
private val BAR_REST = floatArrayOf(0.56f, 1f, 0.38f, 0.75f)
private val BAR_SWING = floatArrayOf(0.4f, -0.5f, 0.5f, -0.35f)

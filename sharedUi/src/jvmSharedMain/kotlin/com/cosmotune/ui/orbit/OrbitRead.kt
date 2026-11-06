package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons

/*
 * The Read and Manga pieces of the Orbit kit (Canvas repo, design/cosmotune/batch-be/board.html, section D). Books and manga are
 * tall pictures: their covers keep their shape ([WatchPicture], [WatchCard] with the "continue" moon off the corner), a title is
 * the colour card with its cover at the start and Continue in the bite, and a reader shows nothing on the page until a tap brings
 * up the slim card at its foot.
 */

/** The head of a page opened over a tab: the round back button, the mono [kind] ("Series", "Book"), then [trailing]. */
@Composable
fun OrbitBackHeader(kind: String, onBack: () -> Unit, modifier: Modifier = Modifier, backLabel: String = "Back", trailing: (@Composable RowScope.() -> Unit)? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchRoundButton(CosmoIcons.ChevronLeft, backLabel, look, onClick = onBack)
        OrbitLabel(kind, fonts, look.dim, Modifier.weight(1f))
        if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** The board's top wash: [color] strong at the top of the page, gone by three quarters of [height]. */
@Composable
fun OrbitPageWash(color: Color, modifier: Modifier = Modifier, height: Dp = 560.dp) {
    val look = orbitLook()
    val a = if (look.dark) 0.5f else 0.2f
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(0f to color.copy(alpha = a), 0.35f to color.copy(alpha = a * 0.3f), 0.75f to Color.Transparent)))
}

/**
 * A book's or a series' colour card (board D): flat in the cover's colour ([colourUrl] when it is an address; [key] picks the
 * stand-in), the tall [cover] at the start, the mono [label] ("MANGA · 142 CHAPTERS · ONGOING"), the [title], [line] under it
 * ("Next: Chapter 87 · The comet road"), [below] (a progress line, a note), and the card's [chips] (Library, Download). Continue
 * ([onContinue]) stands in the bite; a tap on the card is [onClick]. From 600 dp the cover is 136 dp wide; narrower it is 96 dp and the chips run under both.
 */
@Composable
fun OrbitCoverTitleCard(
    cover: Any?,
    colourUrl: String?,
    key: String,
    label: String,
    title: String,
    line: String?,
    onContinue: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    below: @Composable ColumnScope.(OrbitCardColors) -> Unit = {},
    chips: @Composable RowScope.(OrbitCardColors) -> Unit = {},
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val (fill, ink) = coverFill(colourUrl, key)
    val colors = OrbitCardColors(fill, ink)
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 600.dp
        OrbitBiteCard(fill, Modifier.fillMaxWidth(), onClick = onClick, bite = {
            if (onContinue != null) OrbitPlayButton(false, onContinue, look, Modifier.testTag("orbit:card:continue"))
        }) {
            val words: @Composable ColumnScope.() -> Unit = {
                Text(label.uppercase(), style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    title, style = if (wide) fonts.greeting.copy(fontSize = 30.sp, lineHeight = 32.sp) else fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 24.sp),
                    color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).semantics { heading() },
                )
                if (!line.isNullOrBlank()) Text(line, style = fonts.body, color = ink.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                below(colors)
            }
            val chipRow: @Composable (Modifier) -> Unit = { m ->
                // The chips scroll sideways rather than squeeze when a narrow card cannot hold them all.
                Row(m.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { chips(colors) }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().heightIn(min = 250.dp).padding(22.dp), verticalAlignment = Alignment.Top) {
                    WatchPicture(cover, key, Modifier.width(136.dp).height(204.dp), radius = 18.dp)
                    Column(Modifier.weight(1f).padding(start = 24.dp, top = 8.dp, end = 90.dp)) {
                        words()
                        chipRow(Modifier.padding(top = 22.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        WatchPicture(cover, key, Modifier.width(96.dp).height(144.dp), radius = 14.dp)
                        Column(Modifier.weight(1f).padding(start = 16.dp, top = 4.dp), content = words)
                    }
                    chipRow(Modifier.padding(top = 16.dp, end = 76.dp))
                }
            }
        }
    }
}

/**
 * The reader's slim card at the foot of the page (board D, the phone): flat [fill] with its [ink], the mono [label] ("CHAPTER 87 ·
 * PAGE 6 OF 22"), the page line with its moon ([progress]; a tap or drag is [onSeek]), and the card's round [buttons] (type,
 * theme, contents). [trailing] stands at the end of the label line (a back button).
 */
@Composable
fun OrbitReaderCard(
    label: String,
    progress: () -> Float,
    fill: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val fonts = orbitFonts()
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(fill).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
            Text(label.uppercase(), style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        OrbitProgressLine(progress, ink, Modifier.padding(top = 8.dp), onSeek = onSeek, moon = 5.dp)
        Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = buttons)
    }
}

/** A round button on a reader's card: the card's [ink] at 12 %, or solid when [on]; 36 dp. */
@Composable
fun OrbitReaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ink: Color, fill: Color, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false) =
    OrbitRoundButton(icon, label, ink, onClick, modifier, size = 36.dp, on = on, onInk = fill)

/** A cover as the lead of a row: the tall picture, [width] wide, in a box the row's disc would take. */
@Composable
fun OrbitRowCover(cover: Any?, key: String, width: Dp = 34.dp) {
    WatchPicture(cover, key, Modifier.size(width, width * 1.5f), radius = 8.dp)
}

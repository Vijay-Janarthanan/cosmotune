package com.cosmotune.ui.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.rememberCosmoType
import kotlin.math.cos
import kotlin.math.sin

/*
 * The desktop's own Orbit pieces (the old-screens revamp, desktop area): the flat panel the lyrics or Up next stand in beside the
 * page and its heading, the two doors of the Listen together landing, and the colours a window outside the app's own theme (the
 * tray flyout) is drawn with.
 */

/** The side panel's corners: the dock's and the player bar's 28. */
val ORBIT_SIDE_PANEL_SHAPE = RoundedCornerShape(28.dp)

/** The side panel's flat fill: the row's ink (5 %) laid over the page's ground, so it is solid and a shade off the page. No glass. */
fun orbitSidePanelFill(look: OrbitLook): Color = look.row.compositeOver(look.ground)

/**
 * The head of the side panel: the [title] ("Lyrics", "Up next") in the wide section face and the round close button at its end,
 * named [closeLabel].
 */
@Composable
fun OrbitSidePanelHeader(title: String, closeLabel: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, style = fonts.section, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        OrbitRoundButton(CosmoIcons.Close, closeLabel, look.ink, onClose, size = 36.dp)
    }
}

/**
 * The Cosmo colours and type for a window the app's theme does not reach (the tray flyout opens in a window of its own): the fixed
 * One palette of [dark], as the chrome's own.
 */
@Composable
fun OrbitWindowColours(dark: Boolean, content: @Composable () -> Unit) {
    val colors = remember(dark) { CosmoColors(CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark), dark)) }
    val type = rememberCosmoType()
    CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, content = content)
}

/** The colours of an [OrbitDoorCard]: its [fill], the [ink] of its title and buttons, the [dim] of its label and line. */
class OrbitDoorColors(val fill: Color, val ink: Color, val dim: Color)

/**
 * The room's own door: the room card's colours ([roomCardColors]), the indigo with its lavender ink in dark and the pale lavender
 * with the indigo as its ink in light, so the door is never a deep slab on a light page.
 */
fun orbitRoomDoor(look: OrbitLook): OrbitDoorColors = roomCardColors(look).let { OrbitDoorColors(it.fill, it.ink, it.text) }

/** A quiet door on the page: the row's ink at 5 % with the page's own ink. */
fun orbitPlainDoor(look: OrbitLook): OrbitDoorColors = OrbitDoorColors(look.row, look.ink, look.dim)

/**
 * One way in of the Listen together landing (board batch-a, the room card made a door): radius 30, the mono [label], the [title] in
 * the wide face and the [text] under it, then [content] (a stepper and a button, a code field and a button). With [orbits] the
 * room's small system is drawn in its top-end corner, as on Home's room card.
 */
@Composable
fun OrbitDoorCard(
    label: String,
    title: String,
    text: String,
    colors: OrbitDoorColors,
    modifier: Modifier = Modifier,
    orbits: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fonts = orbitFonts()
    Box(modifier.clip(RoundedCornerShape(30.dp)).background(colors.fill)) {
        if (orbits) {
            Canvas(Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-70).dp).size(200.dp)) {
                drawCircle(colors.ink.copy(alpha = 0.14f), 44.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                drawCircle(colors.ink.copy(alpha = 0.14f), 78.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                drawCircle(colors.ink.copy(alpha = 0.20f), 20.dp.toPx(), center)
                val moons = listOf(Color(0xFFE9875A), Color(0xFF5B8C6A), Color(0xFF8B6CC9))
                moons.forEachIndexed { i, moon ->
                    val r = if (i % 2 == 0) 78.dp.toPx() else 44.dp.toPx()
                    val a = Math.toRadians(110.0 + i * 47.0)
                    drawCircle(moon.copy(alpha = 0.6f), 10.dp.toPx(), center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
                }
            }
        }
        Column(Modifier.padding(22.dp)) {
            OrbitLabel(label, fonts, colors.dim)
            Spacer(Modifier.height(10.dp))
            Text(title, style = fonts.section.copy(fontSize = 23.sp), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = fonts.body, color = colors.dim, modifier = Modifier.padding(top = 6.dp, end = if (orbits) 56.dp else 0.dp))
            Spacer(Modifier.height(20.dp))
            content()
        }
    }
}
